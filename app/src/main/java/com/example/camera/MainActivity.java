package com.example.camera;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.util.TypedValue;
import android.graphics.RectF;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.ScrollView;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

import com.example.camera.sync.StandardCurveSync;
import androidx.core.content.FileProvider;
import android.hardware.camera2.CaptureRequest;

import com.example.camera.contract.CameraContract;
import com.example.camera.data.CalibrationRepository;
import com.example.camera.data.ImageRepository;
import com.example.camera.debug.DebugCalibrationCsvRecorder;
import com.example.camera.debug.DebugLuxCalibrationAnalyzer;
import com.example.camera.model.CameraModel;
import com.example.camera.model.calibration.CalibrationFactor;
import com.example.camera.model.calibration.LumaMetrics;
import com.example.camera.presenter.CameraPresenter;
import com.example.camera.presenter.ExposureSequenceCapture;
import com.example.camera.presenter.SequenceState;
import com.example.camera.ui.GreyCardSelector;
import com.example.camera.ui.MaxHeightLinearLayout;
import com.example.camera.sensor.AmbientLightReading;
import com.example.camera.sensor.LightSensorManager;
import com.example.camera.sensor.SensorCalibrationStore;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Unit;

public class MainActivity extends AppCompatActivity implements CameraContract.View {

    private static final String TAG = "CameraXGL";
    private static final String PREFS_NAME = "camera_prefs";
    private static final String KEY_RECOMMENDATION_MODE = "recommendation_mode";
    private static final String KEY_STABLE_FRAMES = "stable_frames";
    private static final String KEY_AUTO_STEP_PERCENT = "auto_step_percent";
    private static final String KEY_BV_THRESHOLD_MILLI = "bv_threshold_milli";
    private static final String KEY_ADVANCED_TUNING_EXPANDED = "advanced_tuning_expanded";
    private static final String KEY_DEBEVEC_BLOCK_EXPANDED = "debevec_block_expanded";
    private static final String KEY_METER_CALIBRATION_EXPANDED = "meter_calibration_expanded";
    private static final int[] STABLE_FRAME_OPTIONS = new int[]{3, 4, 5};
    private static final int[] AUTO_STEP_PERCENT_OPTIONS = new int[]{20, 25, 30};
    private static final int[] BV_THRESHOLD_MILLI_OPTIONS = new int[]{150, 200, 250};
    private static final int MAX_DECODE_DIMENSION = 1920;
    private static final long CAMERA_OPTION_THROTTLE_MS_DEBUG = 50L;
    private static final long CAMERA_OPTION_THROTTLE_MS_RELEASE = 80L;

    /** 曝光序列下拉：显示标签（与 {@link #EXPOSURE_SHUTTER_SECS} 一一对应）。 */
    private static final String[] EXPOSURE_SHUTTER_LABELS = new String[]{
            "1/4000s", "1/2000s", "1/1000s", "1/500s", "1/250s", "1/125s",
            "1/60s", "1/30s", "1/15s", "1/8s", "1/4s", "1/2s", "1s",
    };
    private static final double[] EXPOSURE_SHUTTER_SECS = new double[]{
            1.0 / 4000, 1.0 / 2000, 1.0 / 1000, 1.0 / 500, 1.0 / 250, 1.0 / 125,
            1.0 / 60, 1.0 / 30, 1.0 / 15, 1.0 / 8, 1.0 / 4, 1.0 / 2, 1.0,
    };

    // UI 组件
    private GLSurfaceView glSurfaceView;
    private Button captureButton;
    private boolean isCapturing = false;
    private ImageView imageView;
    private TextView tvBrightnessValue;
    private TextView tvCenterLuminance;
    /** 预览左上 HUD：传感器当前照度（lux / 换算 cd/m²） */
    private TextView tvPreviewAmbientLux;
    private TextView tvExposureLabel;
    private TextView tvIsoLabel;
    private TextView tvBrightnessLabel;
    private TextView tvStatus;
    private TextView tvAutoExposureRecommendation;
    private TextView tvCalibrationStatus;
    private TextView tvCurveSource;
    private TextView tvCurveSourceBadge;
    private BroadcastReceiver curveSyncReceiver;
    private FrameLayout previewLoadingOverlay;
    private ProgressBar previewLoadingProgress;
    private TextView previewLoadingText;
    private Button btnRetry;
    private TextView btnUserHelp;
    private Button btnModeToggle;
    private Button btnApplyRecommendation;
    private Button btnAutoTune;
    private Button btnToggleAdvancedTuning;
    private Button btnStableFrames;
    private Button btnAutoStepRatio;
    private Button btnBvThreshold;
    private Button btnAELock;
    private GreyCardSelector greyCardSelector;
    private TextView tvToggleDebevecCalibration;
    private View llDebevecCalibrationContent;
    private TextView tvDebevecStatus;
    private TextView tvGreyCardStatus;
    private TextView tvGreyCardModeHint;
    private LinearLayout llGreyCardModeActions;
    private Button btnGreyCardCancel;
    private Button btnGreyCardConfirm;
    private Button btnSelectGreyCard;
    private EditText etGreyCardLuminance;
    private Button btnAbsoluteCalibrate;
    private EditText etSequenceIso;
    private Spinner spinnerStartExposure;
    private Spinner spinnerEndExposure;
    private TextView tvFrameCount;
    private TextView tvExposureProgress;
    private ProgressBar pbExposureSequence;
    private Button btnStartExposureSequence;
    private TextView tvCalibrationResult;
    @Nullable
    private RectF greyCardNormRect;
    @Nullable
    private ExposureSequenceCapture exposureSequenceCapture;
    private boolean debevecBlockExpanded = false;
    private Button btnSensorCalibration;
    private View panelDebugCalibrationRecorder;
    private Button btnToggleMeterCalibration;
    private View meterCalibrationExpandContent;
    /** 左下预览控制列（Debug 下展开亮度计校准时放宽 maxWidth）。 */
    private View leftPreviewControlsColumn;
    private FrameLayout previewContainer;
    private MaxHeightLinearLayout svLeftPreviewControlsColumn;
    private LinearLayout llDebevecCalibrationContainer;
    private android.widget.ScrollView svDebevecCalibrationScroll;
    private View llPreviewTopHud;
    private LightSensorManager lightSensorManager;
    private DebugCalibrationCsvRecorder debugCsvRecorder;
    @Nullable
    private AmbientLightReading lastAmbientReading;
    private long lastLuxRangeWarningToastMs = 0L;
    private LinearLayout dockMainActionsRow;
    private LinearLayout manualControlsContainer;
    private View advancedTuningContainer;
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
    /** Debug：亮度计校准面板是否展开。 */
    private boolean isMeterCalibrationExpanded = true;
    private boolean isCameraStartPending = false;

    // 相机参数（通过 Camera2 Interop 手动控制）
    private android.util.Range<Integer> isoRange;
    private android.util.Range<Long> exposureRange;
    private float aperture = 1.8f;

    // 当前手动参数值
    private int currentIso = -1;
    private long currentExposure = -1;

    private final Executor captureExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService shareFlowExecutor = Executors.newSingleThreadExecutor();
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
                            scheduleStartCamera();
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

        previewContainer = findViewById(R.id.preview_container);
        svLeftPreviewControlsColumn = findViewById(R.id.sv_left_preview_controls_column);
        llPreviewTopHud = findViewById(R.id.ll_preview_top_hud);
        glSurfaceView     = findViewById(R.id.gl_surface_view);
        captureButton     = findViewById(R.id.btn);
        imageView         = findViewById(R.id.iv);
        tvBrightnessValue = findViewById(R.id.tv_display_value);
        tvCenterLuminance = findViewById(R.id.tvCenterLuminance);
        tvPreviewAmbientLux = findViewById(R.id.tv_preview_ambient_lux);
        tvExposureLabel   = findViewById(R.id.tv_exposure);
        tvIsoLabel        = findViewById(R.id.tv_iso);
        tvBrightnessLabel = findViewById(R.id.tv_brightness_label);
        tvStatus          = findViewById(R.id.tv_status);
        tvAutoExposureRecommendation = findViewById(R.id.tv_auto_exposure_recommendation);
        tvCalibrationStatus = findViewById(R.id.tv_calibration_status);
        tvCurveSource = findViewById(R.id.tv_curve_source);
        tvCurveSourceBadge = findViewById(R.id.tv_curve_source_badge);
        previewLoadingOverlay = findViewById(R.id.preview_loading_overlay);
        previewLoadingProgress = findViewById(R.id.preview_loading_progress);
        previewLoadingText = findViewById(R.id.preview_loading_text);
        btnRetry          = findViewById(R.id.btn_retry);
        btnUserHelp       = findViewById(R.id.btn_user_help);
        btnModeToggle     = findViewById(R.id.btn_mode_toggle);
        btnApplyRecommendation = findViewById(R.id.btn_apply_recommendation);
        btnAutoTune       = findViewById(R.id.btn_auto_tune);
        btnToggleAdvancedTuning = findViewById(R.id.btn_toggle_advanced_tuning);
        btnStableFrames   = findViewById(R.id.btn_stable_frames);
        btnAutoStepRatio  = findViewById(R.id.btn_auto_step_ratio);
        btnBvThreshold    = findViewById(R.id.btn_bv_threshold);
        btnAELock         = findViewById(R.id.btn_ae_lock);
        greyCardSelector = findViewById(R.id.grey_card_selector);
        tvToggleDebevecCalibration = findViewById(R.id.tv_toggle_debevec_calibration);
        llDebevecCalibrationContent = findViewById(R.id.ll_debevec_calibration_content);
        llDebevecCalibrationContainer = findViewById(R.id.ll_debevec_calibration_container);
        svDebevecCalibrationScroll = findViewById(R.id.sv_debevec_calibration_scroll);
        tvDebevecStatus = findViewById(R.id.tv_debevec_status);
        tvGreyCardStatus = findViewById(R.id.tv_grey_card_status);
        tvGreyCardModeHint = findViewById(R.id.tv_grey_card_mode_hint);
        llGreyCardModeActions = findViewById(R.id.ll_grey_card_mode_actions);
        btnGreyCardCancel = findViewById(R.id.btn_grey_card_cancel);
        btnGreyCardConfirm = findViewById(R.id.btn_grey_card_confirm);
        btnSelectGreyCard = findViewById(R.id.btn_select_grey_card);
        etGreyCardLuminance = findViewById(R.id.et_grey_card_luminance);
        btnAbsoluteCalibrate = findViewById(R.id.btn_absolute_calibrate);
        etSequenceIso = findViewById(R.id.et_sequence_iso);
        spinnerStartExposure = findViewById(R.id.spinner_start_exposure);
        spinnerEndExposure = findViewById(R.id.spinner_end_exposure);
        tvFrameCount = findViewById(R.id.tv_frame_count);
        tvExposureProgress = findViewById(R.id.tv_exposure_progress);
        pbExposureSequence = findViewById(R.id.pb_exposure_sequence);
        btnStartExposureSequence = findViewById(R.id.btn_start_exposure_sequence);
        tvCalibrationResult = findViewById(R.id.tv_calibration_result);
        lightSensorManager = new LightSensorManager(this);
        lightSensorManager.getAmbientLightLiveData().observe(this, this::onAmbientLightReading);
        dockMainActionsRow = findViewById(R.id.dock_main_actions_row);
        manualControlsContainer = findViewById(R.id.manual_controls_container);
        advancedTuningContainer = findViewById(R.id.advanced_tuning_container);
        leftPreviewControlsColumn = findViewById(R.id.left_preview_controls_column);
        bindLeftPreviewControlsColumnMaxHeightGate();
        seekBarBrightness = findViewById(R.id.seekBarBrightness);
        seekBarIso        = findViewById(R.id.seekBarIso);
        seekBarExposure   = findViewById(R.id.seekBarExposure);

        setupGLSurfaceView();
        setupSeekBars();
        captureButton.setOnClickListener(v -> takePicture());
        btnRetry.setOnClickListener(v -> retryLastOperation());
        if (btnUserHelp != null) {
            btnUserHelp.setOnClickListener(v -> showUserHelpDialog());
        }
        View btnSettings = findViewById(R.id.btn_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        }
        setupRecommendationControls();
        presenter = new CameraPresenter(this, this);
        imageRepository = new ImageRepository(this);
        presenter.onViewCreated();
        setupGreyCardAbsoluteCalibration();
        refreshGreyCardRegionStatusUi();
        setupDebevecCalibrationPanel();
        curveSyncReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (presenter != null) {
                    presenter.refreshCurveFromDisk();
                    updateAbsoluteCalibrateButtonState();
                    refreshDebevecStatusLine();
                    refreshCalibrationResultPanel();
                }
            }
        };
        ContextCompat.registerReceiver(
                this,
                curveSyncReceiver,
                new IntentFilter(StandardCurveSync.ACTION_CURVE_PROFILE_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        setupSeekBarMicroInteractions();
        loadRecommendationPrefs();
        updateControlModeUI();
        refreshDebevecAbsoluteControls();
        requestCameraPermissionIfNeeded();
        debugCsvRecorder = new DebugCalibrationCsvRecorder(this);
        if (BuildConfig.DEBUG) {
            setupDebugCalibrationRecorder();
            if (panelDebugCalibrationRecorder != null) {
                panelDebugCalibrationRecorder.setVisibility(View.VISIBLE);
            }
        }
    }

    private void setPreviewLoading(boolean loading, String message) {
        if (previewLoadingOverlay == null) return;
        if (loading) {
            previewLoadingOverlay.setVisibility(View.VISIBLE);
            if (message != null && !message.isEmpty() && previewLoadingText != null) {
                previewLoadingText.setText(message);
            }
        } else {
            previewLoadingOverlay.setVisibility(View.GONE);
        }
    }

    private void setupRecommendationControls() {
        btnApplyRecommendation.setOnClickListener(v -> {
            presenter.applyLatestExposureRecommendation();
            presenter.onUserManualAdjustmentStarted();
        });
        btnAutoTune.setOnClickListener(v -> {
            boolean enabled = presenter.toggleAutoTune();
            btnAutoTune.setText(enabled ? "微调:开" : "微调:关");
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
            btnStableFrames.setText("帧:" + nextValue);
            persistStableFrames(nextValue);
            updateStatusAndRecommendationHint("自动微调稳定帧 = " + nextValue);
        });
        btnAutoStepRatio.setOnClickListener(v -> {
            int currentPercent = (int) Math.round(presenter.getAutoApplyStepRatio() * 100.0);
            int nextPercent = getNextAutoStepPercent(sanitizeAutoStepPercent(currentPercent));
            presenter.setAutoApplyStepRatio(nextPercent / 100.0);
            btnAutoStepRatio.setText("步进:" + nextPercent + "%");
            persistAutoStepPercent(nextPercent);
            updateStatusAndRecommendationHint("自动微调步进 = " + nextPercent + "%");
        });
        btnBvThreshold.setOnClickListener(v -> {
            int currentMilli = (int) Math.round(presenter.getAutoApplyBvDeltaThreshold() * 1000.0);
            int nextMilli = getNextBvThresholdMilli(sanitizeBvThresholdMilli(currentMilli));
            presenter.setAutoApplyBvDeltaThreshold(nextMilli / 1000.0);
            btnBvThreshold.setText("BV:" + String.format("%.2f", nextMilli / 1000.0));
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
    }

    private void loadRecommendationPrefs() {
        SharedPreferences sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isRecommendationMode = sharedPreferences.getBoolean(KEY_RECOMMENDATION_MODE, false);
        isAdvancedTuningExpanded = sharedPreferences.getBoolean(KEY_ADVANCED_TUNING_EXPANDED, false);
        int stableFrames = sanitizeStableFrames(sharedPreferences.getInt(KEY_STABLE_FRAMES, STABLE_FRAME_OPTIONS[0]));
        presenter.setAutoApplyStableFrameCount(stableFrames);
        int autoStepPercent = sanitizeAutoStepPercent(
                sharedPreferences.getInt(KEY_AUTO_STEP_PERCENT, AUTO_STEP_PERCENT_OPTIONS[1]));
        presenter.setAutoApplyStepRatio(autoStepPercent / 100.0);
        int bvThresholdMilli = sanitizeBvThresholdMilli(
                sharedPreferences.getInt(KEY_BV_THRESHOLD_MILLI, BV_THRESHOLD_MILLI_OPTIONS[1]));
        presenter.setAutoApplyBvDeltaThreshold(bvThresholdMilli / 1000.0);
        isMeterCalibrationExpanded = sharedPreferences.getBoolean(KEY_METER_CALIBRATION_EXPANDED, true);
        refreshAdvancedTuningButtonLabels();
        applyInitialDebevecBlockExpandedState(sharedPreferences);
    }

    private void refreshAdvancedTuningButtonLabels() {
        if (presenter == null) return;
        btnAutoTune.setText(presenter.isAutoTuneEnabled() ? "微调:开" : "微调:关");
        btnStableFrames.setText("帧:" + presenter.getAutoApplyStableFrameCount());
        int step = (int) Math.round(presenter.getAutoApplyStepRatio() * 100.0);
        btnAutoStepRatio.setText("步进:" + step + "%");
        btnBvThreshold.setText("BV:" + String.format("%.2f", presenter.getAutoApplyBvDeltaThreshold()));
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
        // 避免 onPause 销毁 EGL 后仍持有失效的 SurfaceTexture，导致恢复后预览黑屏
        glSurfaceView.setPreserveEGLContextOnPause(true);

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
                reportError("相机初始化失败，请重试", e, true);
            } finally {
                // Provider 已就绪但 ST 尚未创建时也必须复位，否则会永久拦截后续 startCamera
                isCameraStartPending = false;
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** 在 GLSurfaceView 完成 attach/创建后再启动相机，减少与 GL 线程的竞态 */
    private void scheduleStartCamera() {
        if (glSurfaceView == null) {
            startCamera();
            return;
        }
        glSurfaceView.post(() -> {
            if (hasCameraPermission()) {
                startCamera();
            }
        });
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

        // 必须先 unbind：在仍绑定时 release Surface 会破坏输出并导致恢复后预览黑屏。
        // android.view.Surface 仅在 provideSurface 的 result 回调里 release，避免与 CameraX 生命周期竞态及重复 release。
        cameraProvider.unbindAll();
        releasePreviewSurface();

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
        // WHEN_DIRTY：绑定后立即请求一帧，避免首帧回调到达前长时间黑屏
        glSurfaceView.requestRender();

        // 读取相机参数范围（通过 Camera2 Interop）
        readCameraRanges();
        initExposureSequenceCaptureIfPossible();
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private void initExposureSequenceCaptureIfPossible() {
        if (imageCapture == null || camera == null || isoRange == null || exposureRange == null) {
            if (exposureSequenceCapture != null) {
                exposureSequenceCapture.dispose();
                exposureSequenceCapture = null;
            }
            return;
        }
        if (exposureSequenceCapture != null) {
            exposureSequenceCapture.dispose();
        }
        exposureSequenceCapture = new ExposureSequenceCapture(
                this,
                imageCapture,
                camera,
                exposureRange,
                isoRange,
                captureExecutor,
                () -> {
                    runOnUiThread(this::applyCamera2Options);
                    return Unit.INSTANCE;
                });
        updateDebevecStartSequenceButtonState();
    }

    private void releasePreviewSurface() {
        // 不要在此处 previewSurface.release()：与 provideSurface 回调中的 release 重复，
        // 且可能在 CameraX 尚未完成 unbind 时抢先释放，导致 SurfaceTexture 异常/黑屏。
        if (exposureSequenceCapture != null) {
            exposureSequenceCapture.dispose();
            exposureSequenceCapture = null;
        }
        updateDebevecStartSequenceButtonState();
        previewSurface = null;
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

    private void setupSeekBarMicroInteractions() {
        double evMicro = 1.0 / 3.0;

        // 长按：弹出 +/- 1/3 EV 微调
        seekBarIso.setLongClickable(true);
        seekBarIso.setOnLongClickListener(v -> {
            showEvMicroAdjustDialog("ISO 微调（+/- 1/3 EV）",
                    () -> presenter.adjustIsoByEv(-evMicro),
                    () -> presenter.adjustIsoByEv(evMicro));
            presenter.onUserManualAdjustmentStarted();
            return true;
        });

        seekBarExposure.setLongClickable(true);
        seekBarExposure.setOnLongClickListener(v -> {
            showEvMicroAdjustDialog("曝光微调（+/- 1/3 EV）",
                    () -> presenter.adjustExposureByEv(-evMicro),
                    () -> presenter.adjustExposureByEv(evMicro));
            presenter.onUserManualAdjustmentStarted();
            return true;
        });

        // 亮度增益：长按按“增益倍率”做 +/- 1/3 EV 形式的缩放
        seekBarBrightness.setLongClickable(true);
        seekBarBrightness.setOnLongClickListener(v -> {
            showEvMicroAdjustDialog("亮度增益微调（+/- 1/3 EV）",
                    () -> presenter.adjustBrightnessGainByEv(-evMicro),
                    () -> presenter.adjustBrightnessGainByEv(evMicro));
            presenter.onUserManualAdjustmentStarted();
            return true;
        });

        // 双击：数值输入（ISO / 曝光(ms) / 亮度增益×）
        setupDoubleTapInput(seekBarIso, new Runnable() {
            @Override public void run() {
                showIsoInputDialog();
            }
        });
        setupDoubleTapInput(seekBarExposure, new Runnable() {
            @Override public void run() {
                showExposureInputDialogMs();
            }
        });
        setupDoubleTapInput(seekBarBrightness, new Runnable() {
            @Override public void run() {
                showBrightnessInputDialog();
            }
        });
    }

    private void showEvMicroAdjustDialog(String title, Runnable onMinus, Runnable onPlus) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("确认微调后将更新预览与硬件参数。")
                .setNegativeButton("-1/3 EV", (d, w) -> {
                    onMinus.run();
                })
                .setPositiveButton("+1/3 EV", (d, w) -> {
                    onPlus.run();
                })
                .show();
    }

    private void setupDoubleTapInput(SeekBar target, Runnable onDoubleTap) {
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                onDoubleTap.run();
                return true;
            }
        });

        target.setOnTouchListener((v, event) -> detector.onTouchEvent(event));
    }

    private void showIsoInputDialog() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("例如：400");
        et.setText(String.valueOf(presenter != null ? presenter.getCurrentIsoForDebug() : 400));
        new AlertDialog.Builder(this)
                .setTitle("输入 ISO")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", (d, w) -> {
                    try {
                        int iso = Integer.parseInt(et.getText().toString().trim());
                        presenter.onUserManualAdjustmentStarted();
                        presenter.setIsoByUserValue(iso);
                    } catch (Exception ignore) {
                        reportError("ISO 输入无效", null, false);
                    }
                })
                .show();
    }

    private void showExposureInputDialogMs() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setHint("曝光时间(ms)，例如：10 或 0.5");
        et.setText(String.valueOf(presenter != null ? presenter.getCurrentExposureMsForDebug() : 10.0));
        new AlertDialog.Builder(this)
                .setTitle("输入曝光时间（ms）")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", (d, w) -> {
                    try {
                        double ms = Double.parseDouble(et.getText().toString().trim());
                        presenter.onUserManualAdjustmentStarted();
                        presenter.setExposureByMillis(ms);
                    } catch (Exception ignore) {
                        reportError("曝光时间输入无效", null, false);
                    }
                })
                .show();
    }

    private void showBrightnessInputDialog() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setHint("亮度增益（×），例如：1.0 或 0.8");
        et.setText(String.valueOf(presenter != null ? presenter.getCurrentBrightnessGainForDebug() : 1.0f));
        new AlertDialog.Builder(this)
                .setTitle("输入亮度增益（×）")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", (d, w) -> {
                    try {
                        float gain = Float.parseFloat(et.getText().toString().trim());
                        presenter.onUserManualAdjustmentStarted();
                        presenter.setBrightnessGainByValue(gain);
                    } catch (Exception ignore) {
                        reportError("亮度增益输入无效", null, false);
                    }
                })
                .show();
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
        if (isCapturing) return;
        isCapturing = true;
        runOnUiThread(() -> {
            captureButton.setEnabled(false);
            updateStatus("正在生成伪彩图并保存...", false, false);
        });
        imageCapture.takePicture(captureExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                try {
                    if (processCapture(imageProxy)) {
                        presenter.takePicture();
                    }
                } finally {
                    imageProxy.close();
                    runOnUiThread(() -> {
                        isCapturing = false;
                        captureButton.setEnabled(true);
                    });
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                reportError("拍照失败，请重试", exception, true);
                isCapturing = false;
                runOnUiThread(() -> captureButton.setEnabled(true));
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

        // 生成伪彩色图像并显示
        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();

        // EXIF 从内存 bytes 直接解析，避免先保存到 MediaStore 再读取带来的 IO 延迟
        String exifBrightness = imageRepository.readExifBrightnessFromBytes(bytes);

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

        // 最后再保存原始 JPEG：不阻塞“伪彩图保存成功”的反馈
        String originalName = System.currentTimeMillis() + "_original.jpg";
        imageRepository.saveJpegBytes(bytes, originalName);

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
            scheduleStartCamera();
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

    private void showUserHelpDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_user_help, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .create();
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
    }

    private void retryLastOperation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermissionIfNeeded();
            return;
        }
        scheduleStartCamera();
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
        if (dockMainActionsRow != null) {
            dockMainActionsRow.setGravity(
                    isRecommendationMode
                            ? Gravity.CENTER_VERTICAL
                            : Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
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

    private void persistMeterCalibrationExpanded() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sp.edit().putBoolean(KEY_METER_CALIBRATION_EXPANDED, isMeterCalibrationExpanded).apply();
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

    /**
     * 有环境光传感器时在预览前台注册监听（左上 HUD 照度）；无传感器则停止。
     * onPause 统一 {@link LightSensorManager#stopListening()}。
     */
    private void refreshAmbientLightSensor() {
        if (lightSensorManager == null || tvPreviewAmbientLux == null) {
            return;
        }
        if (!lightSensorManager.hasLightSensor()) {
            lightSensorManager.stopListening();
            String msg = lightSensorManager.getUnavailableDisplayMessage(this);
            String ambientMsg = msg != null ? msg : getString(R.string.calibration_ambient_placeholder);
            tvPreviewAmbientLux.setText(ambientMsg);
            refreshDebevecAbsoluteControls();
            return;
        }
        lightSensorManager.startListening(null);
        tvPreviewAmbientLux.setText(R.string.calibration_ambient_placeholder);
        refreshDebevecAbsoluteControls();
    }

    private void maybeToastLuxRange(float lux) {
        long now = System.currentTimeMillis();
        if (now - lastLuxRangeWarningToastMs < 5000L) {
            return;
        }
        if (lux < 10f) {
            lastLuxRangeWarningToastMs = now;
            showToast(getString(R.string.calibration_lux_too_low));
        } else if (lux > 50000f) {
            lastLuxRangeWarningToastMs = now;
            showToast(getString(R.string.calibration_lux_too_high));
        }
    }

    /**
     * 由 {@link LightSensorManager#getAmbientLightLiveData()} 推送（已节流），主线程回调，无需再 post。
     */
    private void onAmbientLightReading(@Nullable AmbientLightReading reading) {
        if (reading != null
                && Float.isFinite(reading.lux)
                && Double.isFinite(reading.luminanceCdM2)) {
            lastAmbientReading = reading;
            String line = getString(
                    R.string.calibration_ambient_format, reading.lux, reading.luminanceCdM2);
            if (tvPreviewAmbientLux != null) {
                tvPreviewAmbientLux.setText(line);
            }
            maybeToastLuxRange(reading.lux);
        } else {
            if (tvPreviewAmbientLux != null) {
                tvPreviewAmbientLux.setText(R.string.calibration_ambient_placeholder);
            }
        }
    }

    private void refreshDebevecAbsoluteControls() {
        updateAbsoluteCalibrateButtonState();
        updateDebevecStartSequenceButtonState();
    }

    private void setupGreyCardAbsoluteCalibration() {
        if (greyCardSelector == null) {
            updateAbsoluteCalibrateButtonState();
            return;
        }
        if (btnSelectGreyCard != null) {
            btnSelectGreyCard.setOnClickListener(v -> enterGreyCardSelectionMode());
        }
        if (btnGreyCardCancel != null) {
            btnGreyCardCancel.setOnClickListener(v -> hideGreyCardSelectionOverlay());
        }
        if (btnGreyCardConfirm != null) {
            btnGreyCardConfirm.setOnClickListener(v -> confirmGreyCardSelection());
        }
        if (btnAbsoluteCalibrate != null) {
            btnAbsoluteCalibrate.setOnClickListener(v -> runAbsoluteGreyCardCalibration());
        }
        if (etGreyCardLuminance != null) {
            etGreyCardLuminance.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    updateAbsoluteCalibrateButtonState();
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }
        updateAbsoluteCalibrateButtonState();
    }

    private void setupDebevecCalibrationPanel() {
        if (tvToggleDebevecCalibration != null) {
            tvToggleDebevecCalibration.setOnClickListener(v -> {
                debevecBlockExpanded = !debevecBlockExpanded;
                applyDebevecBlockExpanded(debevecBlockExpanded, true);
            });
        }
        populateExposureSpinners();
        if (spinnerStartExposure != null) {
            spinnerStartExposure.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    updateDebevecFrameCountAndProgressUi();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }
        if (spinnerEndExposure != null) {
            spinnerEndExposure.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    updateDebevecFrameCountAndProgressUi();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }
        if (etSequenceIso != null) {
            etSequenceIso.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    updateDebevecFrameCountAndProgressUi();
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }
        if (btnStartExposureSequence != null) {
            btnStartExposureSequence.setOnClickListener(v -> confirmAndStartDebevecExposureSequence());
        }
        updateDebevecToggleLabel();
        updateDebevecFrameCountAndProgressUi();
        refreshDebevecStatusLine();
        refreshCalibrationResultPanel();
    }

    private void applyInitialDebevecBlockExpandedState(SharedPreferences prefs) {
        boolean keyPresent = prefs.contains(KEY_DEBEVEC_BLOCK_EXPANDED);
        boolean expand;
        if (!keyPresent) {
            expand = presenter != null
                    && presenter.getCalibrationRepository() != null
                    && presenter.getCalibrationRepository().isAbsoluteLuminanceCalibrated();
        } else {
            expand = prefs.getBoolean(KEY_DEBEVEC_BLOCK_EXPANDED, false);
        }
        debevecBlockExpanded = expand;
        applyDebevecBlockExpanded(debevecBlockExpanded, false);
    }

    private void applyDebevecBlockExpanded(boolean expanded, boolean persistPrefs) {
        debevecBlockExpanded = expanded;
        int contentVis = expanded ? View.VISIBLE : View.GONE;
        if (llDebevecCalibrationContent != null) {
            llDebevecCalibrationContent.setVisibility(contentVis);
        }
        syncDebevecInnerScrollLayout();
        updateDebevecToggleLabel();
        if (persistPrefs) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(KEY_DEBEVEC_BLOCK_EXPANDED, expanded)
                    .apply();
        }
        applyLeftPreviewControlsColumnMaxWidth();
        refreshDebevecStatusLine();
        refreshCalibrationResultPanel();
    }

    /**
     * Debevec 展开：外壳 {@link #llDebevecCalibrationContainer} 占左列剩余高度，
     * {@link #svDebevecCalibrationScroll} 内滚动；折叠时取消 weight，避免空白占位。
     */
    private void syncDebevecInnerScrollLayout() {
        if (llDebevecCalibrationContainer == null || leftPreviewControlsColumn == null) {
            return;
        }
        LinearLayout.LayoutParams cLp =
                (LinearLayout.LayoutParams) llDebevecCalibrationContainer.getLayoutParams();
        if (cLp == null) {
            return;
        }
        if (debevecBlockExpanded) {
            cLp.height = 0;
            cLp.weight = 1f;
            llDebevecCalibrationContainer.setLayoutParams(cLp);
            if (svDebevecCalibrationScroll != null) {
                svDebevecCalibrationScroll.setVisibility(View.VISIBLE);
                LinearLayout.LayoutParams sLp =
                        (LinearLayout.LayoutParams) svDebevecCalibrationScroll.getLayoutParams();
                if (sLp != null) {
                    sLp.height = 0;
                    sLp.weight = 1f;
                    svDebevecCalibrationScroll.setLayoutParams(sLp);
                }
            }
        } else {
            cLp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            cLp.weight = 0f;
            llDebevecCalibrationContainer.setLayoutParams(cLp);
            if (svDebevecCalibrationScroll != null) {
                svDebevecCalibrationScroll.setVisibility(View.GONE);
                LinearLayout.LayoutParams sLp =
                        (LinearLayout.LayoutParams) svDebevecCalibrationScroll.getLayoutParams();
                if (sLp != null) {
                    sLp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
                    sLp.weight = 0f;
                    svDebevecCalibrationScroll.setLayoutParams(sLp);
                }
            }
        }
    }

    private void updateDebevecToggleLabel() {
        if (tvToggleDebevecCalibration == null) {
            return;
        }
        tvToggleDebevecCalibration.setText(debevecBlockExpanded
                ? "▼ 响应曲线标定 (Debevec)"
                : "▶ 响应曲线标定 (Debevec)");
    }

    private void populateExposureSpinners() {
        if (spinnerStartExposure == null || spinnerEndExposure == null) {
            return;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_debevec_selected, EXPOSURE_SHUTTER_LABELS);
        adapter.setDropDownViewResource(R.layout.spinner_debevec_dropdown);
        spinnerStartExposure.setAdapter(adapter);
        spinnerEndExposure.setAdapter(adapter);
        spinnerStartExposure.setSelection(0);
        int endIdx = Arrays.asList(EXPOSURE_SHUTTER_LABELS).indexOf("1/15s");
        spinnerEndExposure.setSelection(endIdx >= 0 ? endIdx : Math.min(8, EXPOSURE_SHUTTER_LABELS.length - 1));
    }

    private double readSpinnerExposureSeconds(Spinner sp) {
        if (sp == null) {
            return 1.0 / 4000.0;
        }
        int i = sp.getSelectedItemPosition();
        if (i < 0 || i >= EXPOSURE_SHUTTER_SECS.length) {
            return 1.0 / 4000.0;
        }
        return EXPOSURE_SHUTTER_SECS[i];
    }

    /**
     * 按 1EV 几何档从起始到结束生成曝光时间列表（秒），并钳在设备范围内。
     */
    private ArrayList<Double> buildExposureScheduleFromSpinners() {
        if (exposureRange == null) {
            return new ArrayList<>();
        }
        double t0 = readSpinnerExposureSeconds(spinnerStartExposure);
        double t1 = readSpinnerExposureSeconds(spinnerEndExposure);
        double lo = Math.min(t0, t1);
        double hi = Math.max(t0, t1);
        double loD = exposureRange.getLower() / 1_000_000_000.0;
        double hiD = exposureRange.getUpper() / 1_000_000_000.0;
        lo = Math.max(lo, loD);
        hi = Math.min(hi, hiD);
        if (hi < lo) {
            double x = lo;
            lo = hi;
            hi = x;
        }
        ArrayList<Double> list = new ArrayList<>();
        double t = lo;
        for (int guard = 0; guard < 32; guard++) {
            double capped = Math.min(t, hi);
            if (list.isEmpty() || Math.abs(list.get(list.size() - 1) - capped) > 1e-9 * Math.max(1e-9, capped)) {
                list.add(capped);
            }
            if (capped >= hi - 1e-12) {
                break;
            }
            t *= 2.0;
        }
        if (list.size() == 1 && hi > lo + 1e-12) {
            list.add(hi);
        }
        return list;
    }

    private void updateDebevecFrameCountAndProgressUi() {
        if (tvFrameCount == null || pbExposureSequence == null || tvExposureProgress == null) {
            return;
        }
        if (exposureRange == null) {
            tvFrameCount.setText("拍摄张数: —");
            return;
        }
        ArrayList<Double> sched = buildExposureScheduleFromSpinners();
        int n = sched.size();
        tvFrameCount.setText(String.format(Locale.CHINA, "拍摄张数: %d（1EV 档）", n));
        pbExposureSequence.setMax(Math.max(1, n));
        pbExposureSequence.setProgress(0);
        tvExposureProgress.setText(String.format(Locale.CHINA, "进度: 0/%d", n));
        updateDebevecStartSequenceButtonState();
    }

    private void updateDebevecStartSequenceButtonState() {
        if (btnStartExposureSequence == null) {
            return;
        }
        boolean idle = exposureSequenceCapture == null
                || exposureSequenceCapture.getState() == SequenceState.IDLE;
        ArrayList<Double> sched = exposureRange != null ? buildExposureScheduleFromSpinners() : new ArrayList<>();
        boolean okCount = sched.size() >= 4;
        btnStartExposureSequence.setEnabled(idle && okCount && exposureSequenceCapture != null);
    }

    private int parseSequenceIsoFromUi() {
        if (etSequenceIso == null || isoRange == null) {
            return 100;
        }
        try {
            int v = Integer.parseInt(etSequenceIso.getText().toString().trim());
            return Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), v));
        } catch (NumberFormatException e) {
            return Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), 100));
        }
    }

    private void refreshDebevecStatusLine() {
        if (tvDebevecStatus == null || presenter == null || presenter.getCalibrationRepository() == null) {
            return;
        }
        CalibrationRepository repo = presenter.getCalibrationRepository();
        if (repo.isAbsoluteLuminanceCalibrated()) {
            tvDebevecStatus.setText("状态: g(DN) 与绝对亮度均已标定");
        } else if (repo.hasDebevecG()) {
            tvDebevecStatus.setText("状态: g(DN) 已保存，待绝对亮度校准");
        } else {
            tvDebevecStatus.setText("状态: 未标定");
        }
    }

    private void refreshCalibrationResultPanel() {
        if (tvCalibrationResult == null || presenter == null || presenter.getCalibrationRepository() == null) {
            return;
        }
        CalibrationRepository r = presenter.getCalibrationRepository();
        if (r.isAbsoluteLuminanceCalibrated()) {
            CalibrationFactor f = r.loadCalibrationFactor();
            if (f != null && f.isValid()) {
                tvCalibrationResult.setVisibility(View.VISIBLE);
                tvCalibrationResult.setText(String.format(Locale.US,
                        "K = %.4f（灰卡 %.1f cd/m²）",
                        f.k, f.greyCardLuminance));
                return;
            }
        }
        if (r.hasDebevecG()) {
            String s = presenter.getLastDebevecDiagnosticsSummary();
            if (s != null && !s.isEmpty()) {
                tvCalibrationResult.setVisibility(View.VISIBLE);
                tvCalibrationResult.setText(s);
                return;
            }
            tvCalibrationResult.setVisibility(View.VISIBLE);
            tvCalibrationResult.setText("g(DN) 已持久化（本机）");
            return;
        }
        tvCalibrationResult.setVisibility(View.GONE);
        tvCalibrationResult.setText("");
    }

    private void confirmAndStartDebevecExposureSequence() {
        if (exposureSequenceCapture == null) {
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        if (exposureSequenceCapture.getState() != SequenceState.IDLE) {
            Toast.makeText(this, "曝光序列进行中", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isoRange == null || exposureRange == null) {
            Toast.makeText(this, "无法读取曝光/ISO 范围", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<Double> sched = buildExposureScheduleFromSpinners();
        if (sched.size() < 4) {
            Toast.makeText(this, "起始/结束快门跨度不足，请扩大档位范围（至少 4 张）", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Debevec 曝光序列")
                .setMessage(String.format(Locale.CHINA,
                        "将固定 ISO=%d，连拍 %d 张不同快门（1EV）。请保持场景与手机完全稳定。",
                        parseSequenceIsoFromUi(), sched.size()))
                .setNegativeButton("取消", null)
                .setPositiveButton("开始", (d, w) -> startDebevecExposureSequenceConfirmed())
                .show();
    }

    private void startDebevecExposureSequenceConfirmed() {
        if (exposureSequenceCapture == null || isoRange == null || exposureRange == null || presenter == null) {
            return;
        }
        ArrayList<Double> clamped = buildExposureScheduleFromSpinners();
        if (clamped.size() < 4) {
            Toast.makeText(this, "曝光档位数不足", Toast.LENGTH_SHORT).show();
            return;
        }
        if (btnStartExposureSequence != null) {
            btnStartExposureSequence.setEnabled(false);
        }
        int total = clamped.size();
        runOnUiThread(() -> {
            if (pbExposureSequence != null) {
                pbExposureSequence.setMax(Math.max(1, total));
                pbExposureSequence.setProgress(0);
            }
            if (tvExposureProgress != null) {
                tvExposureProgress.setText(String.format(Locale.CHINA, "进度: 0/%d", total));
            }
        });
        final int[] capturedCount = new int[]{0};
        int iso = parseSequenceIsoFromUi();
        ExposureSequenceCapture.SequenceConfig config =
                new ExposureSequenceCapture.SequenceConfig(clamped, iso);
        Toast.makeText(this, "采集中，请勿移动手机…", Toast.LENGTH_SHORT).show();
        exposureSequenceCapture.startSequence(config, new ExposureSequenceCapture.CaptureCallback() {
            @Override
            public void onFrameCaptured(ExposureSequenceCapture.CapturedFrame frame) {
                capturedCount[0]++;
                int c = capturedCount[0];
                runOnUiThread(() -> {
                    if (pbExposureSequence != null) {
                        pbExposureSequence.setProgress(Math.min(c, pbExposureSequence.getMax()));
                    }
                    if (tvExposureProgress != null) {
                        tvExposureProgress.setText(String.format(Locale.CHINA, "进度: %d/%d", c, total));
                    }
                });
            }

            @Override
            public void onSequenceComplete(List<ExposureSequenceCapture.CapturedFrame> frames) {
                runOnUiThread(() -> {
                    if (btnStartExposureSequence != null) {
                        btnStartExposureSequence.setEnabled(true);
                    }
                    if (pbExposureSequence != null) {
                        pbExposureSequence.setProgress(pbExposureSequence.getMax());
                    }
                    if (tvExposureProgress != null) {
                        tvExposureProgress.setText(String.format(Locale.CHINA, "进度: %d/%d", total, total));
                    }
                    applyCamera2Options();
                    updateDebevecStartSequenceButtonState();
                });
                presenter.solveAndSaveDebevecGFromFrames(frames);
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    if (btnStartExposureSequence != null) {
                        btnStartExposureSequence.setEnabled(true);
                    }
                    applyCamera2Options();
                    updateDebevecStartSequenceButtonState();
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onFrameSkipped(int index, String reason) {
            }
        });
    }

    private void enterGreyCardSelectionMode() {
        if (greyCardSelector == null) {
            return;
        }
        if (tvGreyCardModeHint != null) {
            tvGreyCardModeHint.setVisibility(View.VISIBLE);
        }
        if (llGreyCardModeActions != null) {
            llGreyCardModeActions.setVisibility(View.VISIBLE);
        }
        greyCardSelector.setVisibility(View.VISIBLE);
        RectF prev = greyCardNormRect;
        greyCardSelector.post(() -> greyCardSelector.enterEditMode(prev));
    }

    /** 退出圈选界面（不修改已确认的 {@link #greyCardNormRect}）。 */
    private void hideGreyCardSelectionOverlay() {
        if (tvGreyCardModeHint != null) {
            tvGreyCardModeHint.setVisibility(View.GONE);
        }
        if (llGreyCardModeActions != null) {
            llGreyCardModeActions.setVisibility(View.GONE);
        }
        if (greyCardSelector != null) {
            greyCardSelector.setVisibility(View.GONE);
        }
    }

    private void confirmGreyCardSelection() {
        if (greyCardSelector == null) {
            return;
        }
        RectF n = greyCardSelector.getSelectedRectNormalized();
        if (n == null || n.width() <= 0f || n.height() <= 0f) {
            Toast.makeText(this, R.string.grey_card_confirm_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        greyCardNormRect = new RectF(n);
        hideGreyCardSelectionOverlay();
        refreshGreyCardRegionStatusUi();
        updateAbsoluteCalibrateButtonState();
    }

    private void refreshGreyCardRegionStatusUi() {
        if (tvGreyCardStatus == null) {
            return;
        }
        if (greyCardNormRect == null) {
            tvGreyCardStatus.setText(R.string.grey_card_region_unselected);
            tvGreyCardStatus.setTextColor(ContextCompat.getColor(this, R.color.grey_card_status_pending));
            return;
        }
        int aw = presenter != null ? presenter.getLastImageAnalysisWidth() : 0;
        int ah = presenter != null ? presenter.getLastImageAnalysisHeight() : 0;
        if (aw > 0 && ah > 0) {
            int w = Math.max(1, Math.round(greyCardNormRect.width() * aw));
            int h = Math.max(1, Math.round(greyCardNormRect.height() * ah));
            tvGreyCardStatus.setText(getString(R.string.grey_card_region_selected_pixels, w, h));
        } else {
            tvGreyCardStatus.setText(R.string.grey_card_region_selected_no_analysis);
        }
        tvGreyCardStatus.setTextColor(ContextCompat.getColor(this, R.color.grey_card_status_ok));
    }

    private void runAbsoluteGreyCardCalibration() {
        Double lux = parseGreyCardLuminanceInput();
        if (lux == null || greyCardNormRect == null) {
            Toast.makeText(this, "请圈选灰卡区域并输入真实亮度值", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            CalibrationFactor f = presenter.calibrateAbsoluteLuminanceFromNormRect(
                    greyCardNormRect.left,
                    greyCardNormRect.top,
                    greyCardNormRect.right,
                    greyCardNormRect.bottom,
                    lux);
            Toast.makeText(this, String.format(Locale.US,
                    "绝对亮度标定完成，K = %.4f（灰卡处 DN ≈ %.1f）",
                    f.k, f.greyCardPixelValue), Toast.LENGTH_LONG).show();
            presenter.refreshCenterLuminanceDisplay();
            updateAbsoluteCalibrateButtonState();
            refreshDebevecStatusLine();
            refreshCalibrationResultPanel();
            refreshGreyCardRegionStatusUi();
        } catch (IllegalArgumentException | IllegalStateException e) {
            String msg = e.getMessage();
            Toast.makeText(this, msg != null ? msg : "校准失败", Toast.LENGTH_LONG).show();
        }
    }

    @Nullable
    private Double parseGreyCardLuminanceInput() {
        if (etGreyCardLuminance == null) {
            return null;
        }
        String s = etGreyCardLuminance.getText().toString().trim().replace(',', '.');
        if (s.isEmpty()) {
            return null;
        }
        try {
            double v = Double.parseDouble(s);
            if (!Double.isFinite(v) || v <= 0.0) {
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void updateAbsoluteCalibrateButtonState() {
        if (btnAbsoluteCalibrate == null) {
            return;
        }
        boolean hasG = presenter != null
                && presenter.getCalibrationRepository() != null
                && presenter.getCalibrationRepository().hasDebevecG();
        boolean hasRect = greyCardNormRect != null
                && greyCardNormRect.width() > 0f
                && greyCardNormRect.height() > 0f;
        boolean hasLux = parseGreyCardLuminanceInput() != null;
        btnAbsoluteCalibrate.setEnabled(hasG && hasRect && hasLux);
        updateDebevecStartSequenceButtonState();
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

    private void updateMeterCalibrationExpandUi() {
        if (BuildConfig.DEBUG && btnToggleMeterCalibration != null && meterCalibrationExpandContent != null) {
            meterCalibrationExpandContent.setVisibility(
                    isMeterCalibrationExpanded ? View.VISIBLE : View.GONE);
            btnToggleMeterCalibration.setText(isMeterCalibrationExpanded
                    ? getString(R.string.meter_calibration_toggle_collapse)
                    : getString(R.string.meter_calibration_toggle_expand));
        }
        applyLeftPreviewControlsColumnMaxWidth();
    }

    /**
     * 左下滚动列最大高度 = 预览区高度 − 左上角 HUD 底边 − 间距，保证列顶不低于 HUD 最低位置。
     */
    private void bindLeftPreviewControlsColumnMaxHeightGate() {
        if (previewContainer == null || svLeftPreviewControlsColumn == null) {
            return;
        }
        View.OnLayoutChangeListener listener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                updateLeftPreviewControlsColumnMaxHeight();
        previewContainer.addOnLayoutChangeListener(listener);
        if (llPreviewTopHud != null) {
            llPreviewTopHud.addOnLayoutChangeListener(listener);
        }
        previewContainer.post(this::updateLeftPreviewControlsColumnMaxHeight);
    }

    private void updateLeftPreviewControlsColumnMaxHeight() {
        if (previewContainer == null || svLeftPreviewControlsColumn == null) {
            return;
        }
        int ph = previewContainer.getHeight();
        if (ph <= 0) {
            return;
        }
        int hudBottom = 0;
        if (llPreviewTopHud != null && llPreviewTopHud.getVisibility() == View.VISIBLE) {
            hudBottom = llPreviewTopHud.getBottom();
        }
        int gapPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8f, getResources().getDisplayMetrics());
        int minPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 100f, getResources().getDisplayMetrics());
        int bottomMarginPx = 0;
        ViewGroup.LayoutParams lpSv = svLeftPreviewControlsColumn.getLayoutParams();
        if (lpSv instanceof FrameLayout.LayoutParams) {
            bottomMarginPx = ((FrameLayout.LayoutParams) lpSv).bottomMargin;
        }
        int maxH = ph - hudBottom - gapPx - bottomMarginPx;
        if (maxH < minPx) {
            maxH = minPx;
        }
        svLeftPreviewControlsColumn.setMaxHeightPx(maxH);
    }

    /**
     * Debug：Debevec 块或亮度计面板展开时左列拉宽；否则 WRAP_CONTENT（由 XML maxWidth 封顶）。
     * Release 始终 WRAP_CONTENT。
     */
    private void applyLeftPreviewControlsColumnMaxWidth() {
        if (leftPreviewControlsColumn == null) {
            return;
        }
        ViewGroup.LayoutParams lp = leftPreviewControlsColumn.getLayoutParams();
        if (lp == null) {
            return;
        }
        if (!BuildConfig.DEBUG) {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        } else {
            boolean debugRecorderVisible = panelDebugCalibrationRecorder != null
                    && panelDebugCalibrationRecorder.getVisibility() == View.VISIBLE;
            boolean needWideColumn = debevecBlockExpanded
                    || (debugRecorderVisible && isMeterCalibrationExpanded);
            if (needWideColumn) {
                int expandedPx =
                        getResources().getDimensionPixelSize(R.dimen.left_preview_controls_width_expanded_debug);
                lp.width = expandedPx;
            } else {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            }
        }
        leftPreviewControlsColumn.setLayoutParams(lp);
        updateLeftPreviewControlsColumnMaxHeight();
    }

    // -------------------------------------------------------------------------
    // 生命周期
    // -------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        glSurfaceView.onResume();
        presenter.onResume();
        scheduleStartCamera();
        refreshAmbientLightSensor();
        updateAbsoluteCalibrateButtonState();
        refreshDebevecStatusLine();
        updateDebevecStartSequenceButtonState();
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(applyCameraOptionsRunnable);
        if (lightSensorManager != null) {
            lightSensorManager.stopListening();
        }
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
        if (curveSyncReceiver != null) {
            try {
                unregisterReceiver(curveSyncReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            curveSyncReceiver = null;
        }
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
        shareFlowExecutor.shutdown();
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
        boolean ready = status != null && status.contains("就绪");
        // 非就绪状态遮罩提示用户正在等待相机画面
        setPreviewLoading(!ready, status != null ? status : "相机启动中...");
    }

    @Override
    public void updatePhotoCount(int count) {
        runOnUiThread(() -> {
            if (captureButton != null) {
                captureButton.setText("拍照\n" + count);
            }
        });
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
    public void updateCenterLuminance(double lCdPerM2, double centerMeanDn) {
        runOnUiThread(() -> {
            if (!Double.isFinite(lCdPerM2)) {
                if (presenter != null && presenter.isDebevecAwaitingAbsoluteCalibration()) {
                    tvCenterLuminance.setText("L: 未校准");
                } else {
                    tvCenterLuminance.setText(R.string.center_luminance_placeholder);
                }
                tvCenterLuminance.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                return;
            }
            tvCenterLuminance.setText(String.format(Locale.US, "L: %.1f cd/m²", lCdPerM2));
            int colorRes = R.color.text_secondary;
            if (Double.isFinite(centerMeanDn)) {
                if (centerMeanDn < 80.0) {
                    colorRes = R.color.accent_blue;
                } else if (centerMeanDn > 180.0) {
                    colorRes = R.color.accent_orange;
                }
            }
            tvCenterLuminance.setTextColor(ContextCompat.getColor(this, colorRes));
        });
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
    public void updateCurveSourceBadge(String badgeText) {
        runOnUiThread(() -> {
            if (tvCurveSourceBadge != null) {
                tvCurveSourceBadge.setText(badgeText);
            }
        });
    }

    @Override
    public void onDebevecGSaved() {
        runOnUiThread(() -> {
            updateAbsoluteCalibrateButtonState();
            refreshDebevecStatusLine();
            refreshCalibrationResultPanel();
            if (presenter != null) {
                presenter.refreshCenterLuminanceDisplay();
            }
        });
    }

    @Override
    public void onCalibrationLowQualityComplete() {
    }

    @Override
    public void onCalibrationAbnormalComplete() {
    }

    @Override
    public void onCalibrationHighMediumComplete(String qualityTier) {
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
    public void setPreviewPseudoHueRange(float minBoostedN, float maxBoostedN) {
        if (glSurfaceView == null || cameraRenderer == null) return;
        glSurfaceView.queueEvent(() -> cameraRenderer.setPseudoHueRange(minBoostedN, maxBoostedN));
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

    // -------------------------------------------------------------------------
    // Debug：标定原始数据记录（仅 BuildConfig.DEBUG）
    // -------------------------------------------------------------------------

    private void setupDebugCalibrationRecorder() {
        panelDebugCalibrationRecorder = findViewById(R.id.panel_debug_calibration_recorder);
        if (panelDebugCalibrationRecorder == null) {
            return;
        }
        btnToggleMeterCalibration = findViewById(R.id.btn_toggle_meter_calibration);
        meterCalibrationExpandContent = findViewById(R.id.meter_calibration_expand_content);
        if (btnToggleMeterCalibration != null) {
            btnToggleMeterCalibration.setOnClickListener(v -> {
                isMeterCalibrationExpanded = !isMeterCalibrationExpanded;
                persistMeterCalibrationExpanded();
                updateMeterCalibrationExpandUi();
            });
        }
        updateMeterCalibrationExpandUi();

        Button btnRecord = findViewById(R.id.btn_debug_cal_record);
        Button btnExport = findViewById(R.id.btn_debug_cal_export);
        Button btnClear = findViewById(R.id.btn_debug_cal_clear);
        Button btnCompute = findViewById(R.id.btn_debug_cal_compute);
        if (btnRecord != null) {
            btnRecord.setOnClickListener(v -> onDebugRecordCalibrationRow());
        }
        if (btnExport != null) {
            btnExport.setOnClickListener(v -> exportDebugCalibrationCsv());
        }
        if (btnClear != null) {
            btnClear.setOnClickListener(v -> {
                debugCsvRecorder.clear();
                showToast("已清除调试 CSV 会话");
            });
        }
        if (btnCompute != null) {
            btnCompute.setOnClickListener(v -> onDebugComputeCalibrationParams());
        }
        btnSensorCalibration = findViewById(R.id.btn_sensor_calibration);
        if (btnSensorCalibration != null) {
            btnSensorCalibration.setOnClickListener(v ->
                    startActivity(new Intent(this, SensorCalibrationActivity.class)));
        }
    }

    private void openDebugRecordMeterDialogFromCurrentFrame() {
        if (!BuildConfig.DEBUG || lightSensorManager == null || presenter == null || debugCsvRecorder == null) {
            return;
        }
        float lux = lightSensorManager.getRawLux();
        if (!Float.isFinite(lux) && lastAmbientReading != null) {
            lux = lastAmbientReading.rawLux;
        }
        if (!Float.isFinite(lux)) {
            showToast("暂无传感器 lux，请稍候或检查设备");
            return;
        }
        double meanY = presenter.getLatestCenterMeanYForDebug();
        if (!Double.isFinite(meanY)) {
            showToast("暂无中心 ROI 亮度，请等待预览分析");
            return;
        }
        double bv = LumaMetrics.bvFromDn(meanY);
        double appL = LightSensorManager.appDebugLuminanceFromLux(lux);
        openDebugRecordMeterDialog(lux, bv, appL);
    }

    private void openDebugRecordMeterDialog(float luxFinal, double bvFinal, double appLFinal) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("亮度计读数 cd/m²");

        new AlertDialog.Builder(this)
                .setTitle("记录校准数据")
                .setMessage(String.format(Locale.US, "lux=%.2f\nBV=%.4f\nApp L(0.18/π)=%.4f", luxFinal, bvFinal, appLFinal))
                .setView(input)
                .setPositiveButton("追加写入", (d, w) -> {
                    String s = input.getText() != null ? input.getText().toString().trim() : "";
                    double meter;
                    try {
                        meter = Double.parseDouble(s.replace(',', '.'));
                    } catch (NumberFormatException e) {
                        showToast("请输入有效数字");
                        return;
                    }
                    if (!Double.isFinite(meter) || meter <= 0.0) {
                        showToast("亮度计读数须为正数");
                        return;
                    }
                    String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                    try {
                        debugCsvRecorder.appendRow(ts, luxFinal, meter, appLFinal, bvFinal);
                        showToast("已追加到 CSV");
                    } catch (IOException e) {
                        Log.e(TAG, "debug csv append", e);
                        showToast("写入失败: " + e.getMessage());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onDebugRecordCalibrationRow() {
        openDebugRecordMeterDialogFromCurrentFrame();
    }

    private void exportDebugCalibrationCsv() {
        if (!BuildConfig.DEBUG || debugCsvRecorder == null) {
            return;
        }
        java.io.File f = debugCsvRecorder.getCurrentFile();
        if (f == null || !f.isFile()) {
            showToast("当前无 CSV 文件，请先记录数据");
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/csv");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "导出 CSV"));
    }

    private void onDebugComputeCalibrationParams() {
        if (!BuildConfig.DEBUG || debugCsvRecorder == null) {
            return;
        }
        List<DebugLuxCalibrationAnalyzer.Row> rows;
        try {
            rows = debugCsvRecorder.readRowsForAnalysis();
        } catch (IOException e) {
            showToast("读取失败: " + e.getMessage());
            return;
        }
        List<DebugLuxCalibrationAnalyzer.Row> valid = DebugLuxCalibrationAnalyzer.filterValidRows(rows);
        if (valid.size() < 3) {
            showToast("数据不足，至少需要3组");
            return;
        }
        final int totalValid = valid.size();
        DebugLuxCalibrationAnalyzer.OutlierScan scan = DebugLuxCalibrationAnalyzer.scanOutliers(valid);
        int flagged = scan.getOutlierCount();

        if (flagged > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("异常点")
                    .setMessage(String.format(Locale.getDefault(),
                            "已根据 2σ 规则标记 %d 个异常点（|比值−均值|＞2×标准差，比值=亮度计/App亮度）。"
                                    + "是否剔除后再计算校准参数？",
                            flagged))
                    .setPositiveButton("剔除后计算", (d, w) -> {
                        List<DebugLuxCalibrationAnalyzer.Row> kept = scan.rowsWithoutOutliers();
                        if (kept.size() < 3) {
                            showToast("剔除后数据不足，至少需要3组");
                            return;
                        }
                        DebugLuxCalibrationAnalyzer.Result r = DebugLuxCalibrationAnalyzer.analyze(
                                kept, totalValid, flagged, true);
                        showDebugCalibrationResultDialog(r);
                    })
                    .setNegativeButton("使用全部数据", (d, w) -> {
                        DebugLuxCalibrationAnalyzer.Result r = DebugLuxCalibrationAnalyzer.analyze(
                                valid, totalValid, flagged, false);
                        showDebugCalibrationResultDialog(r);
                    })
                    .show();
        } else {
            DebugLuxCalibrationAnalyzer.Result r = DebugLuxCalibrationAnalyzer.analyze(
                    valid, totalValid, 0, false);
            showDebugCalibrationResultDialog(r);
        }
    }

    private void showDebugCalibrationResultDialog(DebugLuxCalibrationAnalyzer.Result r) {
        if (r.effectiveSampleCount < 1) {
            showToast("无有效拟合数据");
            return;
        }
        SensorCalibrationStore store = new SensorCalibrationStore(this);
        store.savePendingRecommendation(r);
        String msg = DebugLuxCalibrationAnalyzer.formatResultForDisplay(r);
        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setPadding(32, 16, 32, 16);
        tv.setTextSize(13);
        tv.setText(msg);
        scroll.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("校准参数（lux×0.18/π）")
                .setView(scroll)
                .setPositiveButton("确认并写入传感器校准", (d, w) -> {
                    store.applyFromDebugAnalyzerResult(r);
                    if (lightSensorManager != null && lightSensorManager.isListening()) {
                        lightSensorManager.stopListening();
                        refreshAmbientLightSensor();
                    } else {
                        refreshAmbientLightSensor();
                    }
                    showToast("已写入传感器校准，环境光将按新参数换算；可在「传感器校准」中查看");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

}
