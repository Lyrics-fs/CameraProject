package com.example.camera;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import android.hardware.camera2.CaptureRequest;

import com.example.camera.contract.CameraContract;
import com.example.camera.data.ImageRepository;
import com.example.camera.model.CameraModel;
import com.example.camera.presenter.CameraPresenter;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements CameraContract.View {

    private static final String TAG = "CameraXGL";
    private static final String PREFS_NAME = "camera_prefs";
    private static final String KEY_RECOMMENDATION_MODE = "recommendation_mode";
    private static final String KEY_STABLE_FRAMES = "stable_frames";
    private static final String KEY_AUTO_STEP_PERCENT = "auto_step_percent";
    private static final String KEY_BV_THRESHOLD_MILLI = "bv_threshold_milli";
    private static final String KEY_ADVANCED_TUNING_EXPANDED = "advanced_tuning_expanded";
    private static final int[] STABLE_FRAME_OPTIONS = new int[]{3, 4, 5};
    private static final int[] AUTO_STEP_PERCENT_OPTIONS = new int[]{20, 25, 30};
    private static final int[] BV_THRESHOLD_MILLI_OPTIONS = new int[]{150, 200, 250};
    private static final int MAX_DECODE_DIMENSION = 1920;
    private static final long CAMERA_OPTION_THROTTLE_MS_DEBUG = 50L;
    private static final long CAMERA_OPTION_THROTTLE_MS_RELEASE = 80L;

    // UI 组件
    private GLSurfaceView glSurfaceView;
    private Button captureButton;
    private ImageView imageView;
    private TextView tvBrightnessValue;
    private TextView tvExposureLabel;
    private TextView tvIsoLabel;
    private TextView tvBrightnessLabel;
    private TextView tvStatus;
    private TextView tvAutoExposureRecommendation;
    private TextView tvCalibrationStatus;
    private TextView tvCurveSource;
    private Button btnRetry;
    private Button btnModeToggle;
    private Button btnApplyRecommendation;
    private Button btnAutoTune;
    private Button btnToggleAdvancedTuning;
    private Button btnStableFrames;
    private Button btnAutoStepRatio;
    private Button btnBvThreshold;
    private Button btnAELock;
    private Button btnStartCalibration;
    private Button btnCaptureCalibration;
    private Button btnFinishCalibration;
    private LinearLayout manualControlsContainer;
    private LinearLayout advancedTuningContainer;
    private SeekBar seekBarBrightness, seekBarIso, seekBarExposure;

    // OpenGL ES 渲染器
    private CameraRenderer cameraRenderer;

    // CameraX 组件
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private Preview preview;
    private android.view.Surface previewSurface;
    private SurfaceTexture boundSurfaceTexture;
    private CameraPresenter presenter;
    private ImageRepository imageRepository;
    private boolean isProgrammaticSeekBarUpdate = false;
    private boolean isRecommendationMode = false;
    private boolean isAdvancedTuningExpanded = false;
    private boolean isCameraStartPending = false;

    // 相机参数（通过 Camera2 Interop 手动控制）
    private android.util.Range<Integer> isoRange;
    private android.util.Range<Long> exposureRange;
    private float aperture = 1.8f;

    // 当前手动参数值
    private int currentIso = -1;
    private long currentExposure = -1;

    private final Executor captureExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable applyCameraOptionsRunnable = this::applyCamera2Options;
    private final long cameraOptionThrottleMs = BuildConfig.DEBUG
            ? CAMERA_OPTION_THROTTLE_MS_DEBUG
            : CAMERA_OPTION_THROTTLE_MS_RELEASE;
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        if (isGranted) {
                            presenter.onPermissionGranted();
                            startCamera();
                        } else {
                            presenter.onPermissionDenied();
                            if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                                showPermissionSettingsDialog();
                            } else {
                                Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        glSurfaceView     = findViewById(R.id.gl_surface_view);
        captureButton     = findViewById(R.id.btn);
        imageView         = findViewById(R.id.iv);
        tvBrightnessValue = findViewById(R.id.tv_display_value);
        tvExposureLabel   = findViewById(R.id.tv_exposure);
        tvIsoLabel        = findViewById(R.id.tv_iso);
        tvBrightnessLabel = findViewById(R.id.tv_brightness_label);
        tvStatus          = findViewById(R.id.tv_status);
        tvAutoExposureRecommendation = findViewById(R.id.tv_auto_exposure_recommendation);
        tvCalibrationStatus = findViewById(R.id.tv_calibration_status);
        tvCurveSource = findViewById(R.id.tv_curve_source);
        btnRetry          = findViewById(R.id.btn_retry);
        btnModeToggle     = findViewById(R.id.btn_mode_toggle);
        btnApplyRecommendation = findViewById(R.id.btn_apply_recommendation);
        btnAutoTune       = findViewById(R.id.btn_auto_tune);
        btnToggleAdvancedTuning = findViewById(R.id.btn_toggle_advanced_tuning);
        btnStableFrames   = findViewById(R.id.btn_stable_frames);
        btnAutoStepRatio  = findViewById(R.id.btn_auto_step_ratio);
        btnBvThreshold    = findViewById(R.id.btn_bv_threshold);
        btnAELock         = findViewById(R.id.btn_ae_lock);
        btnStartCalibration = findViewById(R.id.btn_start_calibration);
        btnCaptureCalibration = findViewById(R.id.btn_capture_calibration);
        btnFinishCalibration = findViewById(R.id.btn_finish_calibration);
        manualControlsContainer = findViewById(R.id.manual_controls_container);
        advancedTuningContainer = findViewById(R.id.advanced_tuning_container);
        seekBarBrightness = findViewById(R.id.seekBarBrightness);
        seekBarIso        = findViewById(R.id.seekBarIso);
        seekBarExposure   = findViewById(R.id.seekBarExposure);

        setupGLSurfaceView();
        setupSeekBars();
        captureButton.setOnClickListener(v -> takePicture());
        btnRetry.setOnClickListener(v -> retryLastOperation());
        setupRecommendationControls();
        presenter = new CameraPresenter(this, this);
        imageRepository = new ImageRepository(this);
        presenter.onViewCreated();
        loadRecommendationPrefs();
        updateControlModeUI();
        updateCalibrationButtons();
        setupPreviewLayoutOnce();
        requestCameraPermissionIfNeeded();
    }

    private void setupRecommendationControls() {
        btnApplyRecommendation.setOnClickListener(v -> {
            presenter.applyLatestExposureRecommendation();
            presenter.onUserManualAdjustmentStarted();
        });
        btnAutoTune.setOnClickListener(v -> {
            boolean enabled = presenter.toggleAutoTune();
            btnAutoTune.setText(enabled ? "自动微调: 开" : "自动微调: 关");
            if (enabled) {
                tvStatus.setText("自动微调中");
            } else {
                tvStatus.setText("自动微调关闭");
            }
            refreshRecommendationHintIfNeeded();
        });
        btnToggleAdvancedTuning.setOnClickListener(v -> {
            isAdvancedTuningExpanded = !isAdvancedTuningExpanded;
            persistAdvancedTuningExpanded();
            updateAdvancedTuningVisibility();
        });
        btnStableFrames.setOnClickListener(v -> {
            int nextValue = getNextStableFrameCount(presenter.getAutoApplyStableFrameCount());
            presenter.setAutoApplyStableFrameCount(nextValue);
            btnStableFrames.setText("稳定帧: " + nextValue);
            persistStableFrames(nextValue);
            updateStatusAndRecommendationHint("自动微调稳定帧 = " + nextValue);
        });
        btnAutoStepRatio.setOnClickListener(v -> {
            int currentPercent = (int) Math.round(presenter.getAutoApplyStepRatio() * 100.0);
            int nextPercent = getNextAutoStepPercent(sanitizeAutoStepPercent(currentPercent));
            presenter.setAutoApplyStepRatio(nextPercent / 100.0);
            btnAutoStepRatio.setText("自动步进: " + nextPercent + "%");
            persistAutoStepPercent(nextPercent);
            updateStatusAndRecommendationHint("自动微调步进 = " + nextPercent + "%");
        });
        btnBvThreshold.setOnClickListener(v -> {
            int currentMilli = (int) Math.round(presenter.getAutoApplyBvDeltaThreshold() * 1000.0);
            int nextMilli = getNextBvThresholdMilli(sanitizeBvThresholdMilli(currentMilli));
            presenter.setAutoApplyBvDeltaThreshold(nextMilli / 1000.0);
            btnBvThreshold.setText("BV阈值: " + String.format("%.2f", nextMilli / 1000.0));
            persistBvThresholdMilli(nextMilli);
            updateStatusAndRecommendationHint("自动微调BV阈值 = " + String.format("%.2f", nextMilli / 1000.0));
        });
        btnModeToggle.setOnClickListener(v -> {
            isRecommendationMode = !isRecommendationMode;
            persistControlMode();
            updateControlModeUI();
        });
        btnAELock.setOnClickListener(v -> {
            boolean locked = presenter.toggleAELock();
            btnAELock.setText(locked ? "取消锁定推荐值" : "锁定当前推荐值");
            if (locked) {
                tvAutoExposureRecommendation.setText("已锁定当前推荐值");
            } else {
                tvAutoExposureRecommendation.setText("已恢复实时推荐");
            }
        });
        btnStartCalibration.setOnClickListener(v -> {
            presenter.startCalibration();
            updateCalibrationButtons();
        });
        btnCaptureCalibration.setOnClickListener(v -> presenter.captureCalibrationSample());
        btnFinishCalibration.setOnClickListener(v -> {
            presenter.finishCalibration();
            updateCalibrationButtons();
        });
    }

    private void loadRecommendationPrefs() {
        SharedPreferences sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isRecommendationMode = sharedPreferences.getBoolean(KEY_RECOMMENDATION_MODE, false);
        isAdvancedTuningExpanded = sharedPreferences.getBoolean(KEY_ADVANCED_TUNING_EXPANDED, false);
        int stableFrames = sanitizeStableFrames(sharedPreferences.getInt(KEY_STABLE_FRAMES, STABLE_FRAME_OPTIONS[0]));
        presenter.setAutoApplyStableFrameCount(stableFrames);
        btnStableFrames.setText("稳定帧: " + stableFrames);
        int autoStepPercent = sanitizeAutoStepPercent(
                sharedPreferences.getInt(KEY_AUTO_STEP_PERCENT, AUTO_STEP_PERCENT_OPTIONS[1]));
        presenter.setAutoApplyStepRatio(autoStepPercent / 100.0);
        btnAutoStepRatio.setText("自动步进: " + autoStepPercent + "%");
        int bvThresholdMilli = sanitizeBvThresholdMilli(
                sharedPreferences.getInt(KEY_BV_THRESHOLD_MILLI, BV_THRESHOLD_MILLI_OPTIONS[1]));
        presenter.setAutoApplyBvDeltaThreshold(bvThresholdMilli / 1000.0);
        btnBvThreshold.setText("BV阈值: " + String.format("%.2f", bvThresholdMilli / 1000.0));
    }

    private void setupPreviewLayoutOnce() {
        // 在布局完成后根据宽度设置预览高度 = 宽度 × 4/3（竖屏相机比例）
        glSurfaceView.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                glSurfaceView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int w = glSurfaceView.getWidth();
                if (w > 0) {
                    android.view.ViewGroup.LayoutParams lp = glSurfaceView.getLayoutParams();
                    lp.height = w * 4 / 3;
                    glSurfaceView.setLayoutParams(lp);
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // OpenGL ES 初始化
    // -------------------------------------------------------------------------

    private void setupGLSurfaceView() {
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glSurfaceView.getHolder().setFormat(android.graphics.PixelFormat.RGBA_8888);

        cameraRenderer = new CameraRenderer();
        cameraRenderer.setOnSurfaceTextureAvailableListener(surfaceTexture -> {
            // GL 线程回调：SurfaceTexture 就绪后绑定到 CameraX Preview
            runOnUiThread(() -> bindCameraPreview(surfaceTexture));
        });
        cameraRenderer.setOnFrameAvailableListener(() -> glSurfaceView.requestRender());

        glSurfaceView.setRenderer(cameraRenderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    // -------------------------------------------------------------------------
    // CameraX 启动与绑定
    // -------------------------------------------------------------------------

    private void startCamera() {
        if (isCameraStartPending) return;
        isCameraStartPending = true;
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                // 如果 GL SurfaceTexture 已就绪则立即绑定，否则等待渲染器回调
                SurfaceTexture st = cameraRenderer.getSurfaceTexture();
                if (st != null) {
                    bindCameraPreview(st);
                }
            } catch (ExecutionException | InterruptedException e) {
                isCameraStartPending = false;
                reportError("相机初始化失败，请重试", e, true);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraPreview(SurfaceTexture surfaceTexture) {
        if (cameraProvider == null) {
            isCameraStartPending = false;
            return;
        }
        if (surfaceTexture == null) {
            isCameraStartPending = false;
            return;
        }
        if (surfaceTexture == boundSurfaceTexture && previewSurface != null && camera != null) {
            isCameraStartPending = false;
            return;
        }

        releasePreviewSurface();
        cameraProvider.unbindAll();

        // 将 GL SurfaceTexture 包装为 CameraX Preview.SurfaceProvider
        surfaceTexture.setDefaultBufferSize(1280, 720);
        final android.view.Surface requestSurface = new android.view.Surface(surfaceTexture);
        previewSurface = requestSurface;
        boundSurfaceTexture = surfaceTexture;

        Preview.SurfaceProvider surfaceProvider = request -> {
            android.util.Size resolution = request.getResolution();
            surfaceTexture.setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
            request.provideSurface(requestSurface,
                    ContextCompat.getMainExecutor(this), result -> {
                        if (previewSurface == requestSurface) {
                            previewSurface = null;
                            boundSurfaceTexture = null;
                        }
                        requestSurface.release();
                    });
            // 通知渲染器相机实际分辨率，用于宽高比校正
            // 后置相机传感器为横向，竖屏时宽高需交换
            glSurfaceView.queueEvent(() ->
                    cameraRenderer.setCameraAspect(resolution.getHeight(), resolution.getWidth()));
        };

        preview = new Preview.Builder().build();
        preview.setSurfaceProvider(surfaceProvider);

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build();
        presenter.startAutoExposureAnalysis();
        imageAnalysis = presenter.getImageAnalysis();

        CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;

        camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture, imageAnalysis);
        isCameraStartPending = false;
        presenter.onCameraOpened();

        // 读取相机参数范围（通过 Camera2 Interop）
        readCameraRanges();
    }

    private void releasePreviewSurface() {
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        boundSurfaceTexture = null;
        camera = null;
        imageCapture = null;
        imageAnalysis = null;
        preview = null;
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private void readCameraRanges() {
        if (camera == null) return;
        try {
            androidx.camera.camera2.interop.Camera2CameraInfo info =
                    androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.getCameraInfo());
            isoRange = info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            exposureRange = info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            float[] apertures = info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
            if (apertures != null && apertures.length > 0) aperture = apertures[0];

            // 初始化默认值
            if (isoRange != null) currentIso = isoRange.getLower();
            if (exposureRange != null) currentExposure = exposureRange.getLower();
            presenter.setCameraRanges(isoRange, exposureRange, aperture);
        } catch (Exception e) {
            reportError("读取相机参数失败，请重试", e, true);
        }
    }

    // -------------------------------------------------------------------------
    // SeekBar 监听
    // -------------------------------------------------------------------------

    private void setupSeekBars() {
        seekBarIso.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                if (isProgrammaticSeekBarUpdate) return;
                if (isRecommendationMode) return;
                presenter.onIsoChanged(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                presenter.onUserManualAdjustmentStarted();
                presenter.setDeferHardwareApply(true);
                tvAutoExposureRecommendation.setText("手动模式: 自动曝光建议暂时暂停");
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                presenter.setDeferHardwareApply(false);
                presenter.onIsoChanged(s.getProgress());
            }
        });
        seekBarExposure.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                if (isProgrammaticSeekBarUpdate) return;
                if (isRecommendationMode) return;
                presenter.onExposureChanged(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                presenter.onUserManualAdjustmentStarted();
                presenter.setDeferHardwareApply(true);
                tvAutoExposureRecommendation.setText("手动模式: 自动曝光建议暂时暂停");
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                presenter.setDeferHardwareApply(false);
                presenter.onExposureChanged(s.getProgress());
            }
        });
        seekBarBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                if (isProgrammaticSeekBarUpdate) return;
                if (isRecommendationMode) return;
                presenter.onBrightnessChanged(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                presenter.onUserManualAdjustmentStarted();
                presenter.setDeferHardwareApply(true);
                tvAutoExposureRecommendation.setText("手动模式: 自动曝光建议暂时暂停");
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                presenter.setDeferHardwareApply(false);
                presenter.onBrightnessChanged(s.getProgress());
            }
        });
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private void applyCamera2Options() {
        if (camera == null) return;
        try {
            CaptureRequestOptions.Builder builder = new CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_OFF);
            if (currentIso > 0)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, currentIso);
            if (currentExposure > 0)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposure);

            Camera2CameraControl.from(camera.getCameraControl())
                    .setCaptureRequestOptions(builder.build());
        } catch (Exception e) {
            reportError("应用相机参数失败，请重试", e, true);
        }
    }

    private void scheduleApplyCamera2Options() {
        mainHandler.removeCallbacks(applyCameraOptionsRunnable);
        mainHandler.postDelayed(applyCameraOptionsRunnable, cameraOptionThrottleMs);
    }

    // -------------------------------------------------------------------------
    // 拍照
    // -------------------------------------------------------------------------

    private void takePicture() {
        if (imageCapture == null) return;
        imageCapture.takePicture(captureExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                try {
                    if (processCapture(imageProxy)) {
                        presenter.takePicture();
                    }
                } finally {
                    imageProxy.close();
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                reportError("拍照失败，请重试", exception, true);
            }
        });
    }

    private boolean processCapture(ImageProxy imageProxy) {
        // 从 ImageProxy 获取 JPEG 字节
        ImageProxy.PlaneProxy plane = imageProxy.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        Bitmap originalBitmap = decodeSampledBitmap(bytes, MAX_DECODE_DIMENSION, MAX_DECODE_DIMENSION);
        if (originalBitmap == null) return false;

        // 保存原始 JPEG 到 MediaStore/DCIM/Camera
        String originalName = System.currentTimeMillis() + "_original.jpg";
        android.net.Uri originalUri = imageRepository.saveJpegBytes(bytes, originalName);
        if (originalUri == null) {
            reportError("保存原始图像失败，请重试", null, true);
        }

        // 读取 EXIF 亮度
        String exifBrightness = imageRepository.readExifBrightness(originalUri);

        // 生成伪彩色图像并显示
        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        Bitmap pseudoBitmap = presenter.createPseudoColorImage(originalBitmap, exifBrightness, rotationDegrees);
        if (pseudoBitmap == null) {
            if (!originalBitmap.isRecycled()) {
                originalBitmap.recycle();
            }
            return false;
        }
        setImageViewBitmapAndRecycleOld(pseudoBitmap);

        // 保存伪彩色图像到 MediaStore/DCIM/Camera
        String pseudoName = System.currentTimeMillis() + "_pseudo.jpg";
        android.net.Uri pseudoUri = imageRepository.saveJpegBitmap(pseudoBitmap, pseudoName);
        if (pseudoUri != null) {
            runOnUiThread(() -> Toast.makeText(this,
                    "已保存: " + pseudoName, Toast.LENGTH_SHORT).show());
            updateStatus("保存成功", false, false);
        } else {
            reportError("保存伪彩色图像失败，请重试", null, true);
        }

        // 释放拍照原图，避免在拍照链路中长期占用大内存。
        if (!originalBitmap.isRecycled()) {
            originalBitmap.recycle();
        }
        return true;
    }

    private Bitmap decodeSampledBitmap(byte[] data, int reqWidth, int reqHeight) {
        BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
        boundsOptions.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, boundsOptions);

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = calculateInSampleSize(boundsOptions, reqWidth, reqHeight);
        return BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private void setImageViewBitmapAndRecycleOld(Bitmap newBitmap) {
        runOnUiThread(() -> {
            Bitmap oldBitmap = null;
            if (imageView.getDrawable() instanceof BitmapDrawable) {
                oldBitmap = ((BitmapDrawable) imageView.getDrawable()).getBitmap();
            }
            imageView.setImageBitmap(newBitmap);
            if (oldBitmap != null && oldBitmap != newBitmap && !oldBitmap.isRecycled()) {
                oldBitmap.recycle();
            }
        });
    }

    private void requestCameraPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            presenter.onPermissionGranted();
            startCamera();
            return;
        }
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void showPermissionSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("需要相机权限")
                .setMessage("相机权限已被永久拒绝，请在设置中手动开启后再使用。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去设置", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                })
                .show();
    }

    private void retryLastOperation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermissionIfNeeded();
            return;
        }
        startCamera();
    }

    private void reportError(String message, Throwable throwable, boolean canRetry) {
        if (throwable != null) {
            Log.e(TAG, message, throwable);
        } else {
            Log.e(TAG, message);
        }
        updateStatus(message, true, canRetry);
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void updateStatus(String message, boolean isError, boolean showRetry) {
        runOnUiThread(() -> {
            tvStatus.setText(message);
            tvStatus.setTextColor(ContextCompat.getColor(this,
                    isError ? R.color.error_text : R.color.status_text));
            btnRetry.setVisibility(showRetry ? android.view.View.VISIBLE : android.view.View.GONE);
        });
    }

    private void updateControlModeUI() {
        if (isRecommendationMode) {
            btnModeToggle.setText("切换到手动模式");
            btnApplyRecommendation.setVisibility(android.view.View.VISIBLE);
            btnToggleAdvancedTuning.setVisibility(android.view.View.VISIBLE);
            btnAELock.setVisibility(android.view.View.VISIBLE);
            manualControlsContainer.setVisibility(android.view.View.GONE);
            updateAdvancedTuningVisibility();
            tvAutoExposureRecommendation.setText(buildRecommendationModeHint());
        } else {
            btnModeToggle.setText("切换到推荐模式");
            btnApplyRecommendation.setVisibility(android.view.View.GONE);
            btnToggleAdvancedTuning.setVisibility(android.view.View.GONE);
            advancedTuningContainer.setVisibility(android.view.View.GONE);
            btnAELock.setVisibility(android.view.View.GONE);
            manualControlsContainer.setVisibility(android.view.View.VISIBLE);
            tvAutoExposureRecommendation.setText("手动模式: 可拖动滑杆调节");
        }
    }

    private void persistControlMode() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sp.edit().putBoolean(KEY_RECOMMENDATION_MODE, isRecommendationMode).apply();
    }

    private void persistAdvancedTuningExpanded() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sp.edit().putBoolean(KEY_ADVANCED_TUNING_EXPANDED, isAdvancedTuningExpanded).apply();
    }

    private void persistStableFrames(int stableFrames) {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sp.edit().putInt(KEY_STABLE_FRAMES, stableFrames).apply();
    }

    private void persistAutoStepPercent(int autoStepPercent) {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sp.edit().putInt(KEY_AUTO_STEP_PERCENT, autoStepPercent).apply();
    }

    private void persistBvThresholdMilli(int bvThresholdMilli) {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sp.edit().putInt(KEY_BV_THRESHOLD_MILLI, bvThresholdMilli).apply();
    }

    private int sanitizeStableFrames(int stableFrames) {
        for (int option : STABLE_FRAME_OPTIONS) {
            if (option == stableFrames) return stableFrames;
        }
        return STABLE_FRAME_OPTIONS[0];
    }

    private int getNextStableFrameCount(int current) {
        for (int i = 0; i < STABLE_FRAME_OPTIONS.length; i++) {
            if (STABLE_FRAME_OPTIONS[i] == current) {
                return STABLE_FRAME_OPTIONS[(i + 1) % STABLE_FRAME_OPTIONS.length];
            }
        }
        return STABLE_FRAME_OPTIONS[0];
    }

    private int sanitizeAutoStepPercent(int autoStepPercent) {
        for (int option : AUTO_STEP_PERCENT_OPTIONS) {
            if (option == autoStepPercent) return autoStepPercent;
        }
        return AUTO_STEP_PERCENT_OPTIONS[1];
    }

    private int getNextAutoStepPercent(int current) {
        for (int i = 0; i < AUTO_STEP_PERCENT_OPTIONS.length; i++) {
            if (AUTO_STEP_PERCENT_OPTIONS[i] == current) {
                return AUTO_STEP_PERCENT_OPTIONS[(i + 1) % AUTO_STEP_PERCENT_OPTIONS.length];
            }
        }
        return AUTO_STEP_PERCENT_OPTIONS[1];
    }

    private int sanitizeBvThresholdMilli(int bvThresholdMilli) {
        for (int option : BV_THRESHOLD_MILLI_OPTIONS) {
            if (option == bvThresholdMilli) return bvThresholdMilli;
        }
        return BV_THRESHOLD_MILLI_OPTIONS[1];
    }

    private int getNextBvThresholdMilli(int current) {
        for (int i = 0; i < BV_THRESHOLD_MILLI_OPTIONS.length; i++) {
            if (BV_THRESHOLD_MILLI_OPTIONS[i] == current) {
                return BV_THRESHOLD_MILLI_OPTIONS[(i + 1) % BV_THRESHOLD_MILLI_OPTIONS.length];
            }
        }
        return BV_THRESHOLD_MILLI_OPTIONS[1];
    }

    private String buildRecommendationModeHint() {
        int stableFrames = presenter != null ? presenter.getAutoApplyStableFrameCount() : STABLE_FRAME_OPTIONS[0];
        int autoStepPercent = presenter != null
                ? (int) Math.round(presenter.getAutoApplyStepRatio() * 100.0)
                : AUTO_STEP_PERCENT_OPTIONS[1];
        double bvThreshold = presenter != null
                ? presenter.getAutoApplyBvDeltaThreshold()
                : BV_THRESHOLD_MILLI_OPTIONS[1] / 1000.0;
        String autoTune = presenter != null && presenter.isAutoTuneEnabled() ? "开" : "关";
        return "推荐模式: 保持不动，多次点击“应用推荐值”，直到曝光量不再变化\n"
                + "自动微调: " + autoTune
                + "    稳定帧: " + stableFrames
                + "    步进: " + autoStepPercent + "%"
                + "    BV阈值: " + String.format("%.2f", bvThreshold);
    }

    private void updateStatusAndRecommendationHint(String statusText) {
        tvStatus.setText(statusText);
        refreshRecommendationHintIfNeeded();
    }

    private void refreshRecommendationHintIfNeeded() {
        if (isRecommendationMode) {
            tvAutoExposureRecommendation.setText(buildRecommendationModeHint());
        }
    }

    private void updateCalibrationButtons() {
        boolean active = presenter != null && presenter.isCalibrationModeActive();
        btnStartCalibration.setEnabled(!active);
        btnCaptureCalibration.setEnabled(active);
        btnFinishCalibration.setEnabled(active);
    }

    private void updateAdvancedTuningVisibility() {
        if (!isRecommendationMode) {
            advancedTuningContainer.setVisibility(android.view.View.GONE);
            return;
        }
        advancedTuningContainer.setVisibility(
                isAdvancedTuningExpanded ? android.view.View.VISIBLE : android.view.View.GONE);
        btnToggleAdvancedTuning.setText(
                isAdvancedTuningExpanded ? "高级参数 ▴" : "高级参数 ▾");
    }

    // -------------------------------------------------------------------------
    // 生命周期
    // -------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        glSurfaceView.onResume();
        presenter.onResume();
        if (hasCameraPermission()) {
            startCamera();
        }
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(applyCameraOptionsRunnable);
        presenter.onPause();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        releasePreviewSurface();
        super.onPause();
        glSurfaceView.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(applyCameraOptionsRunnable);
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        releasePreviewSurface();
        if (cameraRenderer != null && glSurfaceView != null) {
            glSurfaceView.queueEvent(() -> cameraRenderer.release());
        }
        super.onDestroy();
        if (captureExecutor instanceof ExecutorService) {
            ((ExecutorService) captureExecutor).shutdown();
        }
        if (presenter != null) {
            presenter.onDestroy();
        }
    }

    @Override
    public void showLoading(boolean show) {}

    @Override
    public void updateCameraStatus(String status) {
        Log.d(TAG, "Camera status: " + status);
        updateStatus(status, false, false);
    }

    @Override
    public void updatePhotoCount(int count) {
        runOnUiThread(() -> captureButton.setText("拍照 " + count));
    }

    @Override
    public void showError(String error) {
        reportError(error, null, true);
    }

    @Override
    public void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void updateIsoDisplay(int iso) {
        runOnUiThread(() -> tvIsoLabel.setText(iso < 0 ? "—" : String.valueOf(iso)));
    }

    @Override
    public void updateExposureDisplay(String exposureText) {
        runOnUiThread(() -> tvExposureLabel.setText(exposureText));
    }

    @Override
    public void updateBrightnessMode(String mode) {
        runOnUiThread(() -> tvBrightnessLabel.setText(mode));
    }

    @Override
    public void updateExposureValue(String exposureValue) {
        runOnUiThread(() -> tvBrightnessValue.setText(exposureValue));
    }

    @Override
    public void onExposureRecommendationChanged(CameraModel.ExposureRecommendation recommendation) {
        if (recommendation == null) return;
        String text = String.format(
                "推荐 ISO: %d, 曝光: %.2f ms, BV: %.2f",
                recommendation.recommendedIso,
                recommendation.recommendedExposureTime / 1_000_000.0,
                recommendation.currentSceneBV
        );
        runOnUiThread(() -> tvAutoExposureRecommendation.setText(text));
    }

    @Override
    public void requestAutoApplyRecommendation() {
        runOnUiThread(() -> presenter.applyLatestExposureRecommendationInAutoMode());
    }

    @Override
    public void updateCalibrationStatus(String text) {
        runOnUiThread(() -> tvCalibrationStatus.setText(text));
    }

    @Override
    public void updateCurveSource(String source) {
        runOnUiThread(() -> tvCurveSource.setText("曲线来源: " + source));
    }

    @Override
    public void resetSeekBars() {
        isProgrammaticSeekBarUpdate = true;
        seekBarIso.setProgress(0);
        seekBarExposure.setProgress(0);
        seekBarBrightness.setProgress(0);
        isProgrammaticSeekBarUpdate = false;
    }

    @Override
    public void setSeekBarProgress(int seekBarId, int progress) {
        isProgrammaticSeekBarUpdate = true;
        if (seekBarId == R.id.seekBarIso) {
            seekBarIso.setProgress(progress);
        } else if (seekBarId == R.id.seekBarExposure) {
            seekBarExposure.setProgress(progress);
        } else if (seekBarId == R.id.seekBarBrightness) {
            seekBarBrightness.setProgress(progress);
        }
        isProgrammaticSeekBarUpdate = false;
    }

    @Override
    public void displayCapturedImage(Bitmap bitmap) {
        setImageViewBitmapAndRecycleOld(bitmap);
    }

    @Override
    public void applyCameraIsoParameter(int iso) {
        currentIso = iso;
        scheduleApplyCamera2Options();
    }

    @Override
    public void applyCameraExposureParameter(long exposure) {
        currentExposure = exposure;
        scheduleApplyCamera2Options();
    }

    @Override
    public void setPreviewBrightness(float gain) {
        cameraRenderer.setBrightness(gain);
        glSurfaceView.requestRender();
    }

    @Override
    public int getIsoProgress() {
        return seekBarIso.getProgress();
    }

    @Override
    public int getExposureProgress() {
        return seekBarExposure.getProgress();
    }

    @Override
    public int getBrightnessProgress() {
        return seekBarBrightness.getProgress();
    }
}
