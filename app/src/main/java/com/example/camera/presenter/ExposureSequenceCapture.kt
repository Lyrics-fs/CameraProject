package com.example.camera.presenter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Debevec / HDR 风格的**固定场景、固定 ISO、仅变快门**连拍采集器。
 *
 * 使用 [ImageCapture.takePicture] 拉取帧（默认 JPEG），经解码与缩小后转为单通道灰度 [ByteArray]（每像素 0–255），
 * **不写相册**。采集结束后应调用 [restorePipeline] 将相机恢复到自动/日常模式（由调用方注入）。
 *
 * **安全提示**：拍摄期间须提示用户**不要移动手机**，场景与对焦需保持稳定。
 *
 * @param context 用于主线程回调与（Android 10+）热节流检测。
 * @param imageCapture 已绑定到生命周期的 [ImageCapture]（与预览共用同一相机）。
 * @param camera 与 [imageCapture] 对应的 [Camera]，用于 Camera2 Interop 与对焦测光。
 * @param exposureRangeNs 设备支持的 [CaptureRequest.SENSOR_EXPOSURE_TIME] 范围（纳秒）。
 * @param isoRange 设备支持的 [CaptureRequest.SENSOR_SENSITIVITY] 范围。
 * @param captureExecutor [ImageCapture.takePicture] 的回调线程（勿与主线程混用）。
 * @param restorePipeline 序列结束、取消或异常时恢复预览/自动曝光等（例如重新打开 AE、恢复滑杆）。
 */
@ExperimentalCamera2Interop
class ExposureSequenceCapture(
    private val context: Context,
    private val imageCapture: ImageCapture,
    private val camera: Camera,
    private val exposureRangeNs: Range<Long>,
    private val isoRange: Range<Int>,
    private val captureExecutor: Executor,
    private val restorePipeline: () -> Unit,
) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.Main.immediate)

    private val stateRef = AtomicReference(SequenceState.IDLE)
    private var sequenceJob: Job? = null

    private val capturedFrames = mutableListOf<CapturedFrame>()
    private var currentIndex: Int = 0
    private val lockObject = Any()

    /** 当前状态（线程安全）。 */
    val state: SequenceState get() = stateRef.get()

    /**
     * 曝光序列配置：每张的**目标**曝光时间（秒）与锁定 **ISO**。
     * 实际下发时会将曝光钳在 [exposureRangeNs] 内。
     */
    data class SequenceConfig(
        val exposureTimes: List<Double>,
        val iso: Int = 100,
    )

    /**
     * 已采集的一帧：缩小后的**灰度**像素（行优先，长度 = `width * height`）、对应曝光（秒）、在序列中的下标。
     */
    data class CapturedFrame(
        val pixels: ByteArray,
        val exposureTime: Double,
        val index: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * 序列进度与结果回调（均在 [mainExecutor] 上触发）。
     */
    interface CaptureCallback {
        fun onFrameCaptured(frame: CapturedFrame)
        fun onSequenceComplete(frames: List<CapturedFrame>)
        fun onError(message: String)
        /** 单帧失败但序列继续时调用（例如解码失败）。 */
        fun onFrameSkipped(index: Int, reason: String) {}
    }

    /**
     * 开始曝光序列：锁定手动曝光/ISO → 居中 AF 测光锁定 → 逐张设置曝光、[STABILIZE_DELAY_MS] 稳定 → [takePicture] → 转灰度。
     *
     * 若已在 [SequenceState.CAPTURING]，则 [CaptureCallback.onError] 提示并返回。
     * 调用 [cancel] 可中途终止；终止后仍会 [restorePipeline]。
     */
    fun startSequence(config: SequenceConfig, callback: CaptureCallback) {
        synchronized(lockObject) {
            if (!stateRef.compareAndSet(SequenceState.IDLE, SequenceState.CAPTURING)) {
                postError(callback, "曝光序列已在进行中")
                return
            }
            capturedFrames.clear()
            currentIndex = 0
        }
        sequenceJob = scope.launch {
            try {
                runSequenceInternal(config, callback)
                if (stateRef.get() == SequenceState.CAPTURING) {
                    stateRef.set(SequenceState.COMPLETED)
                    val copy = synchronized(lockObject) { capturedFrames.toList() }
                    postComplete(callback, copy)
                }
            } catch (e: CancellationException) {
                stateRef.set(SequenceState.CANCELLED)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exposure sequence failed", e)
                stateRef.set(SequenceState.IDLE)
                postError(callback, e.message ?: e.toString())
            } finally {
                withContext(NonCancellable) {
                    try {
                        restorePipeline()
                    } catch (t: Throwable) {
                        Log.e(TAG, "restorePipeline failed", t)
                    }
                    stateRef.set(SequenceState.IDLE)
                }
            }
        }
    }

    /** 取消进行中的序列（若存在）。 */
    fun cancel() {
        sequenceJob?.cancel()
        if (stateRef.get() == SequenceState.CAPTURING) {
            stateRef.set(SequenceState.CANCELLED)
        }
    }

    /** 释放协程作用域（在 Activity [onDestroy] 等时机调用）。 */
    fun dispose() {
        supervisorJob.cancel()
    }

    private suspend fun runSequenceInternal(config: SequenceConfig, callback: CaptureCallback) {
        require(config.exposureTimes.isNotEmpty()) { "exposureTimes must not be empty" }
        supervisorScope {
            lockAeAfAndIso(config.iso)
            delay(AF_LOCK_SETTLE_MS)

            val times = config.exposureTimes
            for (i in times.indices) {
                pauseForThermalIfNeeded()

                val targetSec = times[i]
                val exposureNs = (targetSec * 1_000_000_000.0).roundToInt().toLong().coerceIn(
                    exposureRangeNs.lower,
                    exposureRangeNs.upper,
                )

                if (!applyExposureWithRetries(config.iso, exposureNs)) {
                    postError(callback, "无法设置曝光时间（已重试）: index=$i")
                    stateRef.set(SequenceState.IDLE)
                    return@supervisorScope
                }

                ensureActive()

                delay(STABILIZE_DELAY_MS)

                val frameBytes: ByteArray
                val w: Int
                val h: Int
                try {
                    val triple = suspendCaptureGrayscale()
                    frameBytes = triple.first
                    w = triple.second
                    h = triple.third
                } catch (e: Exception) {
                    Log.w(TAG, "Frame capture failed, skip index=$i", e)
                    postSkip(callback, i, e.message ?: e.toString())
                    continue
                }

                val actualSec = exposureNs / 1_000_000_000.0
                val frame = CapturedFrame(
                    pixels = frameBytes,
                    exposureTime = actualSec,
                    index = currentIndex,
                    width = w,
                    height = h,
                )
                synchronized(lockObject) {
                    capturedFrames.add(frame)
                    currentIndex++
                }
                postFrame(callback, frame)
            }

            val empty = synchronized(lockObject) { capturedFrames.isEmpty() }
            if (empty) {
                postError(callback, "序列无有效帧（全部捕获失败）")
                stateRef.set(SequenceState.IDLE)
            }
        }
    }

    private suspend fun pauseForThermalIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            delay(THERMAL_COOLDOWN_MS)
        }
    }

    private fun lockAeAfAndIso(iso: Int) {
        try {
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            val center = factory.createPoint(0.5f, 0.5f)
            val action = FocusMeteringAction.Builder(center, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(10, TimeUnit.MINUTES)
                .build()
            camera.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.w(TAG, "AF lock metering skipped", e)
        }
        applyExposureWithRetries(iso, exposureRangeNs.lower.coerceAtLeast(1L))
    }

    private fun applyExposureWithRetries(iso: Int, exposureNs: Long): Boolean {
        repeat(EXPOSURE_APPLY_RETRIES) { attempt ->
            if (applyManualCaptureRequest(iso, exposureNs)) {
                return true
            }
            try {
                Thread.sleep(50L * (attempt + 1))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return false
    }

    private fun applyManualCaptureRequest(iso: Int, exposureNs: Long): Boolean {
        return try {
            val isoClamped = iso.coerceIn(isoRange.lower, isoRange.upper)
            val expClamped = exposureNs.coerceIn(exposureRangeNs.lower, exposureRangeNs.upper)
            val builder = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, isoClamped)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, expClamped)
            Camera2CameraControl.from(camera.cameraControl)
                .setCaptureRequestOptions(builder.build())
            true
        } catch (e: Exception) {
            Log.w(TAG, "setCaptureRequestOptions failed", e)
            false
        }
    }

    private suspend fun suspendCaptureGrayscale(): Triple<ByteArray, Int, Int> =
        suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val (gray, w, h) = convertToGrayscale(image)
                            cont.resume(Triple(gray, w, h))
                        } catch (e: Throwable) {
                            cont.resumeWithException(e)
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                },
            )
        }

    private fun postFrame(callback: CaptureCallback, frame: CapturedFrame) {
        mainExecutor.execute { callback.onFrameCaptured(frame) }
    }

    private fun postComplete(callback: CaptureCallback, frames: List<CapturedFrame>) {
        mainExecutor.execute { callback.onSequenceComplete(frames) }
    }

    private fun postError(callback: CaptureCallback, message: String) {
        mainExecutor.execute { callback.onError(message) }
    }

    private fun postSkip(callback: CaptureCallback, index: Int, reason: String) {
        mainExecutor.execute { callback.onFrameSkipped(index, reason) }
    }

    companion object {
        private const val TAG = "ExposureSequence"

        /** 设置曝光后的稳定等待（毫秒）。 */
        const val STABILIZE_DELAY_MS = 200L

        private const val AF_LOCK_SETTLE_MS = 150L
        private const val THERMAL_COOLDOWN_MS = 5000L
        private const val EXPOSURE_APPLY_RETRIES = 3

        /** 灰度图最大宽度，控制单帧内存（默认 &lt; 2MB）。 */
        const val MAX_GRAY_WIDTH = 512

        /**
         * 生成等比（公比 **2^stops**）曝光时间列表，从 [minExposure] 开始共 [count] 项，每项钳在 [[minExposure], [maxExposure]]。
         *
         * 若公比推进过快导致多数项顶到 [maxExposure]，仍返回 [count] 个时间点（与 Debevec 多档包围兼容）。
         */
        @JvmStatic
        fun generateExposureSequence(
            minExposure: Double = 1.0 / 4000.0,
            maxExposure: Double = 1.0 / 15.0,
            count: Int = 12,
            stops: Double = 1.0,
        ): List<Double> {
            require(minExposure > 0.0) { "minExposure must be > 0" }
            require(maxExposure >= minExposure) { "maxExposure must be >= minExposure" }
            require(count > 0) { "count must be > 0" }
            if (count == 1) {
                return listOf(minExposure.coerceIn(minExposure, maxExposure))
            }
            val ratio = 2.0.pow(stops)
            val out = ArrayList<Double>(count)
            var t = minExposure
            repeat(count) {
                out.add(t.coerceIn(minExposure, maxExposure))
                t *= ratio
            }
            return out
        }

        /**
         * 在 **[minExposure], [maxExposure]** 上对数轴均匀取 [count] 个点（常用作公比序列顶满后的替代分布）。
         */
        @JvmStatic
        fun generateLogEvenlySpacedSequence(
            minExposure: Double,
            maxExposure: Double,
            count: Int,
        ): List<Double> {
            require(minExposure > 0.0 && maxExposure >= minExposure && count > 0)
            if (count == 1) return listOf(minExposure)
            val logMin = ln(minExposure)
            val logMax = ln(maxExposure)
            return List(count) { i ->
                val a = i.toDouble() / (count - 1)
                exp(logMin * (1.0 - a) + logMax * a)
            }
        }
    }
}

/**
 * 序列生命周期状态。
 */
enum class SequenceState {
    /** 空闲，可启动新序列。 */
    IDLE,

    /** 正在逐张采集。 */
    CAPTURING,

    /** 正常完成（最后一帧已处理）。 */
    COMPLETED,

    /** 用户取消。 */
    CANCELLED,
}

/**
 * 将 [ImageProxy] 转为缩小后的灰度 [ByteArray]（**无 alpha**，每字节 0–255）。
 *
 * - **JPEG**：解码后按宽度缩放到不超过 [ExposureSequenceCapture.MAX_GRAY_WIDTH]，再按 BT.601 算亮度。
 * - **YUV_420_888**：读 Y 平面，再最近邻缩放到目标宽度。
 */
private fun convertToGrayscale(image: ImageProxy): Triple<ByteArray, Int, Int> {
    return when (image.format) {
        ImageFormat.JPEG -> jpegToGrayscale(image)
        ImageFormat.YUV_420_888 -> yuv420ToGrayscale(image)
        else -> {
            val plane = image.planes[0]
            val buf = plane.buffer.duplicate()
            val raw = ByteArray(buf.remaining())
            buf.get(raw)
            jpegToGrayscaleFromBytes(raw, image.width, image.height)
        }
    }
}

private fun jpegToGrayscale(image: ImageProxy): Triple<ByteArray, Int, Int> {
    val plane = image.planes[0]
    val buf = plane.buffer.duplicate()
    val jpeg = ByteArray(buf.remaining())
    buf.get(jpeg)
    return jpegToGrayscaleFromBytes(jpeg, image.width, image.height)
}

private fun jpegToGrayscaleFromBytes(jpeg: ByteArray, reportedW: Int, reportedH: Int): Triple<ByteArray, Int, Int> {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    var inSampleSize = 1
    while (bounds.outWidth / inSampleSize > ExposureSequenceCapture.MAX_GRAY_WIDTH * 2) {
        inSampleSize *= 2
    }
    val opts = BitmapFactory.Options().apply {
        this.inSampleSize = inSampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        ?: error("JPEG decode failed (${reportedW}x$reportedH)")
    return bitmapToGrayMaxWidth(bmp)
}

private fun bitmapToGrayMaxWidth(bmp: Bitmap): Triple<ByteArray, Int, Int> {
    val scaled = if (bmp.width > ExposureSequenceCapture.MAX_GRAY_WIDTH) {
        val nw = ExposureSequenceCapture.MAX_GRAY_WIDTH
        val nh = max(1, (bmp.height * (nw.toFloat() / bmp.width)).roundToInt())
        val s = Bitmap.createScaledBitmap(bmp, nw, nh, true)
        if (s != bmp) bmp.recycle()
        s
    } else {
        bmp
    }
    val w = scaled.width
    val h = scaled.height
    val pixels = IntArray(w * h)
    scaled.getPixels(pixels, 0, w, 0, 0, w, h)
    if (scaled != bmp) scaled.recycle() else bmp.recycle()
    val gray = ByteArray(w * h)
    var i = 0
    while (i < pixels.size) {
        val c = pixels[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val y = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255)
        gray[i] = y.toByte()
        i++
    }
    return Triple(gray, w, h)
}

private fun yuv420ToGrayscale(image: ImageProxy): Triple<ByteArray, Int, Int> {
    val yPlane = image.planes[0]
    val yBuf = yPlane.buffer.duplicate()
    val rowStride = yPlane.rowStride
    val pixStride = yPlane.pixelStride
    val w = image.width
    val h = image.height
    val full = ByteArray(w * h)
    var idx = 0
    for (row in 0 until h) {
        val rowStart = row * rowStride
        for (col in 0 until w) {
            full[idx++] = yBuf.get(rowStart + col * pixStride)
        }
    }
    if (w <= ExposureSequenceCapture.MAX_GRAY_WIDTH) {
        return Triple(full, w, h)
    }
    val nw = ExposureSequenceCapture.MAX_GRAY_WIDTH
    val nh = max(1, (h * (nw.toFloat() / w)).roundToInt())
    val scaled = ByteArray(nw * nh)
    val xScale = w.toFloat() / nw
    val yScale = h.toFloat() / nh
    for (yr in 0 until nh) {
        val srcY = min(h - 1, (yr * yScale).toInt())
        for (xr in 0 until nw) {
            val srcX = min(w - 1, (xr * xScale).toInt())
            scaled[yr * nw + xr] = full[srcY * w + srcX]
        }
    }
    return Triple(scaled, nw, nh)
}
