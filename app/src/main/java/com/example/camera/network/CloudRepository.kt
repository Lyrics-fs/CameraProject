package com.example.camera.network

import android.content.Context
import android.util.Log
import com.example.camera.BuildConfig
import com.example.camera.model.calibration.LookupEntry
import com.google.gson.Gson
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 阿里云函数计算 HTTP 触发器根地址；与 [BuildConfig.CALIBRATION_API_BASE_URL] 同源（`app/build.gradle`），末尾 **必须** 为 `/`。
 * Retrofit [ApiService] / [CalibrationApiService] 的路径（如 `query`、`upload-table`）相对此根拼接。
 */
object CloudRepository {

    /** 单一数据源：修改请在 `app/build.gradle` 的 `CALIBRATION_API_BASE_URL`。 */
    val BASE_URL: String = BuildConfig.CALIBRATION_API_BASE_URL

    private const val TAG = "CloudRepository"
    private val gson = Gson()
    private val jsonUtf8 = "application/json; charset=utf-8".toMediaType()

    /**
     * 上传本机 Debevec 曲线与绝对标定元数据（须在后台线程调用）。
     *
     * @return 是否 HTTP 成功（2xx）
     */
    @JvmStatic
    fun uploadCurve(
        context: Context,
        model: String,
        gCurve: DoubleArray,
        k: Double,
        greyCardDn: Double,
        rSquared: Double,
        dnMin: Int,
        dnMax: Int,
        sampleCount: Int,
        iso: Int,
        appVersion: String,
    ): Boolean {
        val app = context.applicationContext
        val api = NetworkModule.apiService(app)
        val payload = linkedMapOf<String, Any?>(
            "model" to model,
            "g_curve" to gCurve.toList(),
            "k" to k,
            "grey_card_dn" to greyCardDn,
            "r_squared" to rSquared,
            "dn_min" to dnMin,
            "dn_max" to dnMax,
            "sample_count" to sampleCount,
            "iso" to iso,
            "app_version" to appVersion,
        )
        val json = gson.toJson(payload)
        val body = json.toRequestBody(jsonUtf8)
        return try {
            val resp = api.uploadCurve(body).execute()
            try {
                val ok = resp.isSuccessful
                Log.i(
                    TAG,
                    "uploadCurve model=$model samples=$sampleCount r2=$rSquared iso=$iso " +
                        "dn=[$dnMin,$dnMax] http=${resp.code()} ok=$ok",
                )
                if (!ok) {
                    val err = resp.errorBody()?.string()?.take(500)
                    Log.w(TAG, "uploadCurve error body: $err")
                }
                ok
            } finally {
                resp.body()?.close()
                resp.errorBody()?.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadCurve failed model=$model samples=$sampleCount", e)
            false
        }
    }

    /**
     * 同步上传查表（Level1）到 FC `/upload-table`。
     *
     * 供 [com.example.camera.upload.UploadLookupTableWorker] 等在 **后台线程** 调用；勿在主线程调用。
     * 业务契约与 [UploadTableResponseResolver] 一致；日常入队请用 [com.example.camera.upload.LookupTableUploadScheduler]。
     */
    @JvmStatic
    fun uploadLookupTableBlocking(
        context: Context,
        model: String,
        entries: List<LookupEntry>,
        iso: Int,
        calibratedAtMillis: Long,
        uploadId: String,
    ): LookupTableUploadWorkResult {
        if (entries.size < 5) {
            return LookupTableUploadWorkResult.SkippedInvalidTable
        }
        val secret = BuildConfig.LAB_UPLOAD_SECRET
        if (secret.isBlank()) {
            Log.w(TAG, "uploadLookupTableBlocking skipped: LAB_UPLOAD_SECRET empty")
            return LookupTableUploadWorkResult.SkippedNoSecret
        }
        val app = context.applicationContext
        val api = NetworkModule.calibrationApiService(app)
        val entryList = entries.map { e ->
            mapOf("dn" to e.dn, "luminance" to e.luminance)
        }
        val bodyMap = mutableMapOf<String, Any?>(
            "secret" to secret,
            "model" to model,
            "iso" to iso,
            "app_version" to BuildConfig.VERSION_NAME,
            "calibrated_at" to calibratedAtMillis,
            "upload_id" to uploadId,
            "entries" to entryList,
        )
        Log.i(
            TAG,
            "uploadLookupTableBlocking model=$model uploadId=$uploadId calibrated_at=$calibratedAtMillis " +
                "entries=${entries.size} iso=$iso",
        )
        val resp = try {
            api.uploadTable(bodyMap).execute()
        } catch (e: IOException) {
            Log.e(TAG, "uploadLookupTableBlocking IO model=$model uploadId=$uploadId", e)
            return LookupTableUploadWorkResult.NetworkError(e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            Log.e(TAG, "uploadLookupTableBlocking failed model=$model uploadId=$uploadId", e)
            return LookupTableUploadWorkResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
        return try {
            val http = resp.code()
            val httpOk = resp.isSuccessful
            val errRaw = try {
                resp.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            val body = resp.body()
            val env = UploadTableResponseResolver.resolveUploadTableEnvelope(httpOk, http, body, errRaw)
            when {
                env == null && http == 403 -> {
                    Log.w(TAG, "uploadLookupTableBlocking: HTTP 403 no JSON")
                    LookupTableUploadWorkResult.Forbidden("密钥错误或无权访问（HTTP 403）")
                }
                env == null -> {
                    Log.w(TAG, "uploadLookupTableBlocking: HTTP=$http unparsable err=${errRaw?.take(200)}")
                    LookupTableUploadWorkResult.UnparsedResponse(
                        http,
                        errRaw?.take(120)?.trim().orEmpty(),
                    )
                }
                env.code == 200 -> {
                    Log.i(TAG, "uploadLookupTableBlocking success HTTP=$http uploadId=$uploadId")
                    LookupTableUploadWorkResult.Success
                }
                env.code == 403 -> {
                    Log.w(TAG, "uploadLookupTableBlocking JSON code=403 HTTP=$http")
                    LookupTableUploadWorkResult.Forbidden("密钥错误，无上传权限")
                }
                else -> {
                    val msg = env.message?.takeIf { it.isNotBlank() }
                        ?: "上传失败（code=${env.code}）"
                    LookupTableUploadWorkResult.BusinessError(msg)
                }
            }
        } finally {
            RetrofitResponses.closeRawQuietly(resp)
        }
    }
}
