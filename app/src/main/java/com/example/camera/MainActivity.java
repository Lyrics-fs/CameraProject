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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.graphics.Rect;
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
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.example.camera.sync.StandardCurveSync;
import android.hardware.camera2.CaptureRequest;

import com.example.camera.contract.CameraContract;
import com.example.camera.data.CalibrationRepository;
import com.example.camera.data.ImageRepository;
import com.example.camera.model.CameraModel;
import com.example.camera.model.calibration.CalibrationFactor;
import com.example.camera.presenter.CameraPresenter;
import com.example.camera.upload.LookupTableUploadScheduler;
import com.example.camera.upload.UploadLookupTableWorker;
import com.example.camera.presenter.ExposureSequenceCapture;
import com.example.camera.presenter.SequenceState;
import com.example.camera.ui.GreyCardSelector;
import com.example.camera.ui.MaxHeightLinearLayout;
import com.example.camera.sensor.AmbientLightReading;
import com.example.camera.sensor.LightSensorManager;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
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
    /** 与文档「level1_expanded」一致；缺失时再读旧键 {@code level1_block_expanded}。 */
    private static final String KEY_LEVEL1_EXPANDED = "level1_expanded";
    private static final String KEY_LEVEL1_EXPANDED_LEGACY = "level1_block_expanded";
    /** 绝对标定第三步：是否使用环境光传感器估算 L3（否则为亮度计输入）。 */
    private static final String KEY_ABS_INPUT_SENSOR_MODE = "abs_input_sensor_mode";
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
    /** 预览左上 HUD：环境光两行（照度 lux / 亮度 cd/m²） */
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
    private RadioGroup rgAbsLuminanceSource;
    private RadioButton rbAbsSourceMeter;
    private RadioButton rbAbsSourceSensor;
    private LinearLayout llAbsMeterInput;
    private LinearLayout llAbsSensorEstimate;
    private TextView tvAbsSensorLuminanceEstimate;
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
    private TextView tvToggleLevel1;
    /** 查表法展开区纵向滚动；与 {@link #svDebevecCalibrationScroll} 在 {@link #syncDebevecInnerScrollLayout()} 中分配 height/weight。 */
    private android.widget.ScrollView svLevel1Scroll;
    /** Debevec / 绝对亮度等；可与 Level 1 同屏，仅一方展开时另一方 GONE。 */
    private View llDebevecDetails;
    private TextView tvLevel1Dn;
    private TextView tvLevel1GreyStatus;
    private Button btnLevel1SelectGreyCard;
    private EditText etLevel1Luminance;
    private Button btnLevel1Sample;
    private TextView tvLevel1Progress;
    private Button btnLevel1UndoSample;
    private Button btnLevel1ClearSamples;
    private Button btnLevel1Save;
    private TextView tvLevel1TableInfo;
    private TextView tvLevel1UploadHint;
    private TextView tvLevel1CloudStatus;
    private Button btnLevel1RetryUpload;
    private boolean level1Expanded = false;
    /** 左下预览控制列（Debug 下 Debevec 展开时可拉宽）。 */
    private View leftPreviewControlsColumn;
    private FrameLayout previewContainer;
    private MaxHeightLinearLayout svLeftPreviewControlsColumn;
    private LinearLayout llDebevecCalibrationContainer;
    private android.widget.ScrollView svDebevecCalibrationScroll;
    private View llPreviewTopHud;
    private LightSensorManager lightSensorManager;
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
        rgAbsLuminanceSource = findViewById(R.id.rg_abs_luminance_source);
        rbAbsSourceMeter = findViewById(R.id.rb_abs_source_meter);
        rbAbsSourceSensor = findViewById(R.id.rb_abs_source_sensor);
        llAbsMeterInput = findViewById(R.id.ll_abs_meter_input);
        llAbsSensorEstimate = findViewById(R.id.ll_abs_sensor_estimate);
        tvAbsSensorLuminanceEstimate = findViewById(R.id.tv_abs_sensor_luminance_estimate);
        btnAbsoluteCalibrate = findViewById(R.id.btn_absolute_calibrate);
        etSequenceIso = findViewById(R.id.et_sequence_iso);
        spinnerStartExposure = findViewById(R.id.spinner_start_exposure);
        spinnerEndExposure = findViewById(R.id.spinner_end_exposure);
        tvFrameCount = findViewById(R.id.tv_frame_count);
        tvExposureProgress = findViewById(R.id.tv_exposure_progress);
        pbExposureSequence = findViewById(R.id.pb_exposure_sequence);
        btnStartExposureSequence = findViewById(R.id.btn_start_exposure_sequence);
        tvCalibrationResult = findViewById(R.id.tv_calibration_result);
        tvToggleLevel1 = findViewById(R.id.tv_toggle_level1);
        svLevel1Scroll = findViewById(R.id.sv_level1_scroll);
        llDebevecDetails = findViewById(R.id.ll_debevec_details);
        tvLevel1Dn = findViewById(R.id.tv_level1_dn);
        tvLevel1GreyStatus = findViewById(R.id.tv_level1_grey_status);
        btnLevel1SelectGreyCard = findViewById(R.id.btn_level1_select_grey_card);
        etLevel1Luminance = findViewById(R.id.et_level1_luminance);
        btnLevel1Sample = findViewById(R.id.btn_level1_sample);
        tvLevel1Progress = findViewById(R.id.tv_level1_progress);
        btnLevel1UndoSample = findViewById(R.id.btn_level1_undo_sample);
        btnLevel1ClearSamples = findViewById(R.id.btn_level1_clear_samples);
        btnLevel1Save = findViewById(R.id.btn_level1_save);
        tvLevel1TableInfo = findViewById(R.id.tv_level1_table_info);
        tvLevel1UploadHint = findViewById(R.id.tv_level1_upload_hint);
        tvLevel1CloudStatus = findViewById(R.id.tv_level1_cloud_status);
        btnLevel1RetryUpload = findViewById(R.id.btn_level1_retry_upload);
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
        setupLevel1Panel();
        curveSyncReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (presenter != null) {
                    presenter.refreshCurveFromDisk();
                    updateAbsoluteCalibrateButtonState();
                    refreshDebevecStatusLine();
                    refreshCalibrationResultPanel();
                    refreshLevel1TableInfoAndSaveButton();
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
        refreshAdvancedTuningButtonLabels();
        applyInitialDebevecBlockExpandedState(sharedPreferences);
        restoreAbsInputSourceFromPrefs(sharedPreferences);
        applyInitialLevel1BlockExpandedState(sharedPreferences);
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
            if (isAbsInputSensorMode()) {
                refreshAbsSensorEstimateDisplay();
                updateAbsoluteCalibrateButtonState();
            }
        } else {
            if (tvPreviewAmbientLux != null) {
                tvPreviewAmbientLux.setText(R.string.calibration_ambient_placeholder);
            }
            if (isAbsInputSensorMode()) {
                refreshAbsSensorEstimateDisplay();
                updateAbsoluteCalibrateButtonState();
            }
        }
    }

    private void refreshDebevecAbsoluteControls() {
        refreshAbsSensorEstimateDisplay();
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
        if (llDebevecDetails != null) {
            llDebevecDetails.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        updateCalibrationContentWrapperVisibility();
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

    private void setupLevel1Panel() {
        if (tvToggleLevel1 != null) {
            tvToggleLevel1.setOnClickListener(v -> {
                level1Expanded = !level1Expanded;
                applyLevel1BlockExpanded(level1Expanded, true);
            });
        }
        updateLevel1ToggleLabel();
        if (presenter != null) {
            presenter.getLevel1State().observe(this, s -> {
                if (tvLevel1Progress == null) {
                    return;
                }
                if (s == null || s.isEmpty()) {
                    tvLevel1Progress.setText("已采集: 0 组");
                } else {
                    tvLevel1Progress.setText(s);
                }
            });
        }
        if (btnLevel1SelectGreyCard != null) {
            btnLevel1SelectGreyCard.setOnClickListener(v -> enterGreyCardSelectionMode());
        }
        if (btnLevel1Sample != null) {
            btnLevel1Sample.setOnClickListener(v -> runLevel1Sample());
        }
        if (btnLevel1UndoSample != null) {
            btnLevel1UndoSample.setOnClickListener(v -> undoLastLevel1Sample());
        }
        if (btnLevel1ClearSamples != null) {
            btnLevel1ClearSamples.setOnClickListener(v -> confirmClearLevel1Samples());
        }
        if (btnLevel1Save != null) {
            btnLevel1Save.setOnClickListener(v -> confirmAndSaveLevel1Table());
        }
        if (btnLevel1RetryUpload != null) {
            btnLevel1RetryUpload.setOnClickListener(v -> {
                if (presenter != null) {
                    presenter.retryUploadLevel1Table();
                }
            });
        }
        if (etLevel1Luminance != null && svLevel1Scroll != null) {
            etLevel1Luminance.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    return;
                }
                svLevel1Scroll.post(() -> {
                    Rect r = new Rect();
                    v.getDrawingRect(r);
                    svLevel1Scroll.requestChildRectangleOnScreen(v, r, true);
                });
            });
        }
        refreshLevel1TableInfoAndSaveButton();
        refreshLevel1DnDisplay();
        WorkManager.getInstance(this)
                .getWorkInfosForUniqueWorkLiveData(LookupTableUploadScheduler.UNIQUE_WORK_NAME)
                .observe(this, this::onLookupTableUploadWorkFinished);
    }

    private void onLookupTableUploadWorkFinished(java.util.List<WorkInfo> workInfos) {
        if (workInfos == null || workInfos.isEmpty()) {
            return;
        }
        WorkInfo info = workInfos.get(0);
        WorkInfo.State s = info.getState();
        if (s == WorkInfo.State.SUCCEEDED) {
            if (info.getOutputData().getBoolean(UploadLookupTableWorker.KEY_OUTPUT_UPLOADED, false)) {
                showToast(getString(R.string.level1_lookup_upload_success));
            }
            refreshLevel1CloudUploadUi();
        } else if (s == WorkInfo.State.FAILED) {
            String err = info.getOutputData().getString(UploadLookupTableWorker.KEY_OUTPUT_ERROR);
            if (err != null && !err.isEmpty()) {
                showToastLong(err);
            }
            refreshLevel1CloudUploadUi();
        }
    }

    /** Level 1 面板内灰卡选区状态行（与 {@link #greyCardNormRect}、ImageAnalysis 尺寸一致）。 */
    private void refreshLevel1GreyRegionStatus() {
        if (tvLevel1GreyStatus == null) {
            return;
        }
        if (greyCardNormRect == null
                || greyCardNormRect.width() <= 0f
                || greyCardNormRect.height() <= 0f) {
            tvLevel1GreyStatus.setText(R.string.level1_grey_status_pending);
            tvLevel1GreyStatus.setTextColor(ContextCompat.getColor(this, R.color.grey_card_status_pending));
            return;
        }
        int aw = presenter != null ? presenter.getLastImageAnalysisWidth() : 0;
        int ah = presenter != null ? presenter.getLastImageAnalysisHeight() : 0;
        if (aw > 0 && ah > 0) {
            int w = Math.max(1, Math.round(greyCardNormRect.width() * aw));
            int h = Math.max(1, Math.round(greyCardNormRect.height() * ah));
            tvLevel1GreyStatus.setText(getString(R.string.grey_card_region_selected_pixels, w, h));
        } else {
            tvLevel1GreyStatus.setText(R.string.grey_card_region_selected_no_analysis);
        }
        tvLevel1GreyStatus.setTextColor(ContextCompat.getColor(this, R.color.grey_card_status_ok));
    }

    private void applyInitialLevel1BlockExpandedState(SharedPreferences prefs) {
        boolean expand = prefs.contains(KEY_LEVEL1_EXPANDED)
                ? prefs.getBoolean(KEY_LEVEL1_EXPANDED, false)
                : prefs.getBoolean(KEY_LEVEL1_EXPANDED_LEGACY, false);
        level1Expanded = expand;
        applyLevel1BlockExpanded(level1Expanded, false);
    }

    private void applyLevel1BlockExpanded(boolean expanded, boolean persistPrefs) {
        level1Expanded = expanded;
        if (svLevel1Scroll != null) {
            svLevel1Scroll.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        updateCalibrationContentWrapperVisibility();
        syncDebevecInnerScrollLayout();
        updateLevel1ToggleLabel();
        if (persistPrefs) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(KEY_LEVEL1_EXPANDED, expanded)
                    .apply();
        }
        applyLeftPreviewControlsColumnMaxWidth();
    }

    /** 仅响应曲线展开时显示 {@link #svDebevecCalibrationScroll} 内根布局。 */
    private void updateCalibrationContentWrapperVisibility() {
        if (llDebevecCalibrationContent == null) {
            return;
        }
        llDebevecCalibrationContent.setVisibility(debevecBlockExpanded ? View.VISIBLE : View.GONE);
    }

    private void updateLevel1ToggleLabel() {
        if (tvToggleLevel1 == null) {
            return;
        }
        tvToggleLevel1.setText(level1Expanded
                ? getString(R.string.level1_toggle_label_expanded)
                : getString(R.string.level1_toggle_label_collapsed));
    }

    /**
     * 当前灰卡归一化选区内平均 Y（0～255）；未圈选或尚无有效分析帧时返回 {@code null}。
     */
    @Nullable
    private Double getGreyCardAverageDN() {
        if (presenter == null) {
            return null;
        }
        if (greyCardNormRect == null
                || greyCardNormRect.width() <= 0f
                || greyCardNormRect.height() <= 0f) {
            return null;
        }
        double dn = presenter.computeMeanYInNormalizedRect(
                greyCardNormRect.left,
                greyCardNormRect.top,
                greyCardNormRect.right,
                greyCardNormRect.bottom);
        return Double.isFinite(dn) ? dn : null;
    }

    private void refreshLevel1DnDisplay() {
        refreshLevel1GreyRegionStatus();
        if (tvLevel1Dn == null || presenter == null) {
            return;
        }
        Double dn = getGreyCardAverageDN();
        if (dn == null) {
            if (greyCardNormRect == null
                    || greyCardNormRect.width() <= 0f
                    || greyCardNormRect.height() <= 0f) {
                tvLevel1Dn.setText("灰卡 DN: (请先圈选)");
            } else {
                tvLevel1Dn.setText("灰卡 DN: ---");
            }
            return;
        }
        tvLevel1Dn.setText(String.format(Locale.US, "灰卡 DN: %.1f", dn));
    }

    private void refreshLevel1TableInfoAndSaveButton() {
        if (presenter == null) {
            return;
        }
        if (tvLevel1TableInfo != null) {
            tvLevel1TableInfo.setText(presenter.getLevel1TableInfo());
        }
        boolean hasPendingSamples = presenter.getLevel1Samples().size() > 0;
        if (btnLevel1UndoSample != null) {
            btnLevel1UndoSample.setEnabled(hasPendingSamples);
        }
        if (btnLevel1ClearSamples != null) {
            btnLevel1ClearSamples.setEnabled(hasPendingSamples);
        }
        if (btnLevel1Save != null) {
            btnLevel1Save.setEnabled(presenter.getLevel1Samples().size() >= 5);
        }
        refreshLevel1CloudUploadUi();
    }

    /** Level1 查表云端：已上传状态、说明文案与「再次上传」按钮。 */
    private void refreshLevel1CloudUploadUi() {
        if (presenter == null) {
            if (tvLevel1UploadHint != null) {
                tvLevel1UploadHint.setVisibility(View.GONE);
            }
            if (tvLevel1CloudStatus != null) {
                tvLevel1CloudStatus.setVisibility(View.GONE);
            }
            if (btnLevel1RetryUpload != null) {
                btnLevel1RetryUpload.setVisibility(View.GONE);
            }
            return;
        }
        boolean hasTable = presenter.hasLevel1Table();
        boolean uploaded = presenter.isLevel1LookupUploadedToCloud();
        if (tvLevel1UploadHint != null) {
            if (hasTable) {
                tvLevel1UploadHint.setVisibility(View.VISIBLE);
                tvLevel1UploadHint.setText(R.string.level1_lookup_upload_hint);
            } else {
                tvLevel1UploadHint.setVisibility(View.GONE);
                tvLevel1UploadHint.setText("");
            }
        }
        if (tvLevel1CloudStatus != null) {
            if (!hasTable) {
                tvLevel1CloudStatus.setVisibility(View.GONE);
                tvLevel1CloudStatus.setText("");
            } else {
                tvLevel1CloudStatus.setVisibility(View.VISIBLE);
                tvLevel1CloudStatus.setText(uploaded
                        ? getString(R.string.level1_lookup_cloud_status_uploaded)
                        : getString(R.string.level1_lookup_cloud_status_pending));
                tvLevel1CloudStatus.setTextColor(ContextCompat.getColor(this,
                        uploaded ? R.color.accent_green : R.color.text_secondary));
            }
        }
        if (btnLevel1RetryUpload != null) {
            btnLevel1RetryUpload.setVisibility(hasTable ? View.VISIBLE : View.GONE);
            btnLevel1RetryUpload.setEnabled(hasTable);
        }
    }

    private void undoLastLevel1Sample() {
        if (presenter == null) {
            return;
        }
        if (!presenter.removeLastLevel1Sample()) {
            Toast.makeText(this, R.string.level1_undo_nothing_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        refreshLevel1TableInfoAndSaveButton();
    }

    private void confirmClearLevel1Samples() {
        if (presenter == null) {
            return;
        }
        int n = presenter.getLevel1Samples().size();
        if (n <= 0) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.level1_clear_samples)
                .setMessage(getString(R.string.level1_clear_samples_confirm, n))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    presenter.clearLevel1Samples();
                    refreshLevel1TableInfoAndSaveButton();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void runLevel1Sample() {
        if (presenter == null || etLevel1Luminance == null) {
            return;
        }
        String lumStr = etLevel1Luminance.getText().toString().trim();
        if (lumStr.isEmpty()) {
            Toast.makeText(this, "请输入亮度计读数", Toast.LENGTH_SHORT).show();
            return;
        }
        double luminance;
        try {
            luminance = Double.parseDouble(lumStr.replace(',', '.'));
        } catch (NumberFormatException e) {
            Toast.makeText(this, "亮度计读数格式无效", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Double.isFinite(luminance) || luminance <= 0.0) {
            Toast.makeText(this, "请输入有效的亮度值", Toast.LENGTH_SHORT).show();
            return;
        }
        Double dn = getGreyCardAverageDN();
        if (dn == null) {
            if (greyCardNormRect == null
                    || greyCardNormRect.width() <= 0f
                    || greyCardNormRect.height() <= 0f) {
                Toast.makeText(this, "请先圈选灰卡区域", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "暂无分析帧，请稍候再试", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        presenter.addLevel1Sample(dn, luminance);
        etLevel1Luminance.setText("");
        Toast.makeText(this,
                String.format(Locale.US, "已采集: DN=%.1f, L=%.1f cd/m²", dn, luminance),
                Toast.LENGTH_SHORT).show();
        refreshLevel1TableInfoAndSaveButton();
        refreshLevel1DnDisplay();
    }

    private void confirmAndSaveLevel1Table() {
        if (presenter == null) {
            return;
        }
        int n = presenter.getLevel1Samples().size();
        if (n < 5) {
            Toast.makeText(this, "至少需要 5 组数据", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("保存查表")
                .setMessage("确认保存 " + n + " 组数据？\n保存后可用于亮度查询。")
                .setPositiveButton("保存", (dialog, which) -> {
                    presenter.saveLevel1Table(Build.MODEL);
                    if (tvLevel1Progress != null) {
                        tvLevel1Progress.setText("已采集: 0 组");
                    }
                    refreshLevel1TableInfoAndSaveButton();
                    refreshLevel1CloudUploadUi();
                    presenter.refreshCenterLuminanceDisplay();
                    Toast.makeText(this, R.string.level1_lookup_saved_local, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 任一方展开时外壳占剩余高度；查表与响应曲线各自 ScrollView 在剩余空间内按 weight 分配以便纵向滚动。
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
        boolean eitherExpanded = debevecBlockExpanded || level1Expanded;
        if (eitherExpanded) {
            cLp.height = 0;
            cLp.weight = 1f;
            llDebevecCalibrationContainer.setLayoutParams(cLp);
        } else {
            cLp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            cLp.weight = 0f;
            llDebevecCalibrationContainer.setLayoutParams(cLp);
        }
        if (svLevel1Scroll != null) {
            LinearLayout.LayoutParams l1Lp =
                    (LinearLayout.LayoutParams) svLevel1Scroll.getLayoutParams();
            if (level1Expanded && eitherExpanded) {
                svLevel1Scroll.setVisibility(View.VISIBLE);
                if (l1Lp != null) {
                    l1Lp.height = 0;
                    l1Lp.weight = 1f;
                    svLevel1Scroll.setLayoutParams(l1Lp);
                }
            } else {
                svLevel1Scroll.setVisibility(View.GONE);
                if (l1Lp != null) {
                    l1Lp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
                    l1Lp.weight = 0f;
                    svLevel1Scroll.setLayoutParams(l1Lp);
                }
            }
        }
        if (svDebevecCalibrationScroll != null) {
            LinearLayout.LayoutParams sLp =
                    (LinearLayout.LayoutParams) svDebevecCalibrationScroll.getLayoutParams();
            if (debevecBlockExpanded) {
                svDebevecCalibrationScroll.setVisibility(View.VISIBLE);
                if (sLp != null) {
                    sLp.height = 0;
                    sLp.weight = 1f;
                    svDebevecCalibrationScroll.setLayoutParams(sLp);
                }
            } else {
                svDebevecCalibrationScroll.setVisibility(View.GONE);
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
                ? getString(R.string.level2_debevec_toggle_expanded)
                : getString(R.string.level2_debevec_toggle_collapsed));
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
            CalibrationFactor fac = repo.loadCalibrationFactor();
            if (fac != null && fac.isSensorLuxEstimate()) {
                tvDebevecStatus.setText(R.string.level2_debevec_status_abs_l3);
            } else {
                tvDebevecStatus.setText(R.string.level2_debevec_status_abs_meter);
            }
        } else if (repo.hasDebevecG()) {
            tvDebevecStatus.setText(R.string.level2_debevec_status_g_only);
        } else {
            tvDebevecStatus.setText(R.string.level2_debevec_status_none);
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
                if (f.isSensorLuxEstimate()) {
                    tvCalibrationResult.setText(String.format(Locale.US,
                            "%s\n\n%s",
                            getString(R.string.abs_calibration_result_l3_line, f.k, f.greyCardLuminance),
                            getString(R.string.abs_l3_precision_notice)));
                } else {
                    tvCalibrationResult.setText(String.format(Locale.US,
                            "%s\n\n%s",
                            getString(R.string.abs_calibration_result_meter_line, f.k, f.greyCardLuminance),
                            getString(R.string.calibration_upload_privacy_notice)));
                }
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
        int iso = parseSequenceIsoFromUi();
        new AlertDialog.Builder(this)
                .setTitle(R.string.debevec_sequence_dialog_title)
                .setMessage(getString(R.string.debevec_sequence_dialog_message, iso, sched.size()))
                .setNegativeButton(R.string.debevec_sequence_dialog_cancel, null)
                .setPositiveButton(R.string.debevec_sequence_dialog_start, (d, w) -> startDebevecExposureSequenceConfirmed())
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
        Toast.makeText(this, R.string.debevec_sequence_capture_toast, Toast.LENGTH_LONG).show();
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
                presenter.solveAndSaveDebevecGFromFrames(frames, iso);
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
        Toast.makeText(this, R.string.grey_card_selection_enter_toast, Toast.LENGTH_LONG).show();
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
        refreshLevel1DnDisplay();
    }

    private void restoreAbsInputSourceFromPrefs(SharedPreferences prefs) {
        if (rgAbsLuminanceSource == null || rbAbsSourceMeter == null || rbAbsSourceSensor == null) {
            return;
        }
        boolean sensor = prefs.getBoolean(KEY_ABS_INPUT_SENSOR_MODE, false);
        rgAbsLuminanceSource.setOnCheckedChangeListener(null);
        if (sensor) {
            rbAbsSourceSensor.setChecked(true);
        } else {
            rbAbsSourceMeter.setChecked(true);
        }
        rgAbsLuminanceSource.setOnCheckedChangeListener(this::onAbsLuminanceSourceChanged);
        applyAbsLuminanceSourceUi();
    }

    private void onAbsLuminanceSourceChanged(RadioGroup group, int checkedId) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_ABS_INPUT_SENSOR_MODE, checkedId == R.id.rb_abs_source_sensor)
                .apply();
        applyAbsLuminanceSourceUi();
    }

    private boolean isAbsInputSensorMode() {
        return rbAbsSourceSensor != null && rbAbsSourceSensor.isChecked();
    }

    private void applyAbsLuminanceSourceUi() {
        boolean sensor = isAbsInputSensorMode();
        if (llAbsMeterInput != null) {
            llAbsMeterInput.setVisibility(sensor ? View.GONE : View.VISIBLE);
        }
        if (llAbsSensorEstimate != null) {
            llAbsSensorEstimate.setVisibility(sensor ? View.VISIBLE : View.GONE);
        }
        if (btnAbsoluteCalibrate != null) {
            btnAbsoluteCalibrate.setText(sensor
                    ? getString(R.string.abs_btn_save_sensor)
                    : getString(R.string.abs_btn_save_meter));
        }
        refreshAbsSensorEstimateDisplay();
        updateAbsoluteCalibrateButtonState();
    }

    /**
     * L3：展示 lux→cd/m² 估算（与左上 HUD 同源：校准后 lux 经应用内换算系数得到 cd/m²）。
     */
    private void refreshAbsSensorEstimateDisplay() {
        if (tvAbsSensorLuminanceEstimate == null) {
            return;
        }
        if (!isAbsInputSensorMode()) {
            return;
        }
        if (lightSensorManager == null || !lightSensorManager.hasLightSensor()) {
            tvAbsSensorLuminanceEstimate.setText(R.string.abs_l3_sensor_unavailable);
            return;
        }
        float calLux = lightSensorManager.getCurrentLux();
        double l = Double.NaN;
        if (Float.isFinite(calLux)) {
            l = LightSensorManager.appDebugLuminanceFromLux(calLux);
        }
        if ((!Double.isFinite(l) || l <= 0) && lastAmbientReading != null) {
            l = lastAmbientReading.luminanceCdM2;
            calLux = lastAmbientReading.lux;
        }
        if (Double.isFinite(l) && l > 0 && Float.isFinite(calLux)) {
            tvAbsSensorLuminanceEstimate.setText(
                    getString(R.string.abs_l3_estimate_format, l, calLux));
        } else {
            tvAbsSensorLuminanceEstimate.setText(R.string.abs_l3_waiting_sensor);
        }
    }

    /** 点击保存时采用的估算 cd/m²（优先当前校准 lux 快照）。 */
    private double resolveSensorLuminanceEstimateCdM2() {
        if (lightSensorManager == null || !lightSensorManager.hasLightSensor()) {
            return Double.NaN;
        }
        float calLux = lightSensorManager.getCurrentLux();
        if (Float.isFinite(calLux)) {
            double l = LightSensorManager.appDebugLuminanceFromLux(calLux);
            if (Double.isFinite(l) && l > 0) {
                return l;
            }
        }
        if (lastAmbientReading != null
                && Double.isFinite(lastAmbientReading.luminanceCdM2)
                && lastAmbientReading.luminanceCdM2 > 0) {
            return lastAmbientReading.luminanceCdM2;
        }
        return Double.NaN;
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
        refreshLevel1GreyRegionStatus();
    }

    private void runAbsoluteGreyCardCalibration() {
        if (greyCardNormRect == null) {
            Toast.makeText(this, "请先圈选灰卡区域", Toast.LENGTH_SHORT).show();
            return;
        }
        final double lKnown;
        final int absLevel;
        if (isAbsInputSensorMode()) {
            lKnown = resolveSensorLuminanceEstimateCdM2();
            if (!Double.isFinite(lKnown) || lKnown <= 0) {
                Toast.makeText(this, "环境光估算无效，请稍候或改用亮度计", Toast.LENGTH_SHORT).show();
                return;
            }
            absLevel = CalibrationFactor.ABS_LEVEL_SENSOR_LUX_ESTIMATE;
        } else {
            Double lux = parseGreyCardLuminanceInput();
            if (lux == null) {
                Toast.makeText(this, "请输入灰卡亮度计实测 cd/m²", Toast.LENGTH_SHORT).show();
                return;
            }
            lKnown = lux;
            absLevel = CalibrationFactor.ABS_LEVEL_BRIGHTNESS_METER;
        }
        try {
            CalibrationFactor f = presenter.calibrateAbsoluteLuminanceFromNormRect(
                    greyCardNormRect.left,
                    greyCardNormRect.top,
                    greyCardNormRect.right,
                    greyCardNormRect.bottom,
                    lKnown,
                    absLevel);
            if (absLevel == CalibrationFactor.ABS_LEVEL_SENSOR_LUX_ESTIMATE) {
                String l3Done = getString(R.string.abs_l3_calibrate_done_toast, f.k, lKnown, f.greyCardPixelValue)
                        + "\n\n"
                        + getString(R.string.abs_l3_precision_notice);
                Toast.makeText(this, l3Done, Toast.LENGTH_LONG).show();
            } else {
                String meterDone = getString(R.string.level2_meter_calibrate_done_toast, f.k, f.greyCardPixelValue)
                        + "\n\n"
                        + getString(R.string.calibration_upload_privacy_notice);
                Toast.makeText(this, meterDone, Toast.LENGTH_LONG).show();
                if (presenter != null) {
                    presenter.scheduleLevel2CurveUploadIfEligibleAfterMeterCalibration();
                }
            }
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
        boolean canSave;
        if (isAbsInputSensorMode()) {
            boolean sensorOk = lightSensorManager != null && lightSensorManager.hasLightSensor();
            double lEst = resolveSensorLuminanceEstimateCdM2();
            canSave = hasG && hasRect && sensorOk && Double.isFinite(lEst) && lEst > 0;
        } else {
            boolean hasLux = parseGreyCardLuminanceInput() != null;
            canSave = hasG && hasRect && hasLux;
        }
        btnAbsoluteCalibrate.setEnabled(canSave);
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
     * Debug：Debevec 块展开时左列拉宽；否则 WRAP_CONTENT（由 XML maxWidth 封顶）。Release 始终 WRAP_CONTENT。
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
            if (debevecBlockExpanded || level1Expanded) {
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
    public void showToastLong(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
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
    public void updateCenterLuminance(double lCdPerM2, double centerMeanDn,
            @Nullable String luminanceSourceTag) {
        runOnUiThread(() -> {
            refreshLevel1DnDisplay();
            if (!Double.isFinite(lCdPerM2)) {
                if (presenter != null && presenter.isDebevecAwaitingAbsoluteCalibration()) {
                    tvCenterLuminance.setText("L: 未校准");
                } else {
                    tvCenterLuminance.setText(R.string.center_luminance_placeholder);
                }
                tvCenterLuminance.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                return;
            }
            if (luminanceSourceTag != null && !luminanceSourceTag.isEmpty()) {
                tvCenterLuminance.setText(String.format(Locale.US, "L: %.1f cd/m² [%s]",
                        lCdPerM2, luminanceSourceTag));
            } else {
                tvCenterLuminance.setText(String.format(Locale.US, "L: %.1f cd/m²", lCdPerM2));
            }
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
            refreshLevel1TableInfoAndSaveButton();
            refreshLevel1CloudUploadUi();
        });
    }

    @Override
    public void onLevel1LookupUploadStateChanged() {
        runOnUiThread(this::refreshLevel1CloudUploadUi);
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

}
