package com.example.camera.presenter;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Size;
import android.util.Range;

import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.example.camera.R;
import com.example.camera.calibration.model.CalibrationUploadData;
import com.example.camera.calibration.model.CalibrationUploadPolicy;
import com.example.camera.contract.CameraContract;
import com.example.camera.data.CalibrationRepository;
import com.example.camera.model.AppState;
import com.example.camera.model.CameraModel;
import com.example.camera.model.CameraSettings;
import com.example.camera.model.calibration.CalibrationSample;
import com.example.camera.model.calibration.CalibrationSession;
import com.example.camera.model.calibration.CalibrationFitter;
import com.example.camera.model.calibration.CurveParams;
import com.example.camera.presenter.state.AutoTuneState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraPresenter implements CameraContract.Presenter {
    private static final int SEEK_MAX = 5000;
    private static final int AUTO_EXPOSURE_ANALYSIS_WIDTH = 640;
    private static final int AUTO_EXPOSURE_ANALYSIS_HEIGHT = 480;
    private static final int ISO_DEADBAND = 30;
    private static final long EXPOSURE_DEADBAND_NS = 500_000L;
    private static final double BV_DEADBAND = 0.15;
    /**
     * 预览中心 L 单独节流：比 {@link #BV_DEADBAND} 更敏感，避免与曝光推荐绑定导致数值长时间不动。
     */
    private static final double CENTER_L_DISPLAY_BV_DEADBAND = 0.04;
    private static final double AUTO_APPLY_BV_DELTA_THRESHOLD_DEFAULT = 0.2;
    private static final int AUTO_APPLY_STABLE_FRAME_COUNT_DEFAULT = 3;
    private static final long AUTO_APPLY_MIN_INTERVAL_MS = 500L;
    private static final long MANUAL_MODE_HOLD_MS = 5000L;
    private static final double AUTO_APPLY_STEP_RATIO_DEFAULT = 0.25; // 自动微调每次仅走25%
    private static final double APPLY_SMOOTHING = 0.6; // 多次点击逐步收敛
    // 预览阶段手动调节的安全范围，避免卡顿。
    private static final int MANUAL_SAFE_MIN_ISO = 100;
    private static final int MANUAL_SAFE_MAX_ISO = 800;
    private static final long MANUAL_SAFE_MIN_EXPOSURE_NS = 2_000_000L;   // 2ms
    private static final long MANUAL_SAFE_MAX_EXPOSURE_NS = 33_000_000L;  // ~1/30s
    private static final int PREVIEW_SAFE_MIN_ISO = 100;
    private static final int PREVIEW_SAFE_MAX_ISO = 800;
    private static final long PREVIEW_SAFE_MIN_EXPOSURE_NS = 2_000_000L;
    private static final long PREVIEW_SAFE_MAX_EXPOSURE_NS = 33_000_000L;
    private static final int CALIBRATION_MIN_SAMPLES = 8;
    private static final long CALIBRATION_SAMPLE_INTERVAL_MS = 700L;
    private static final double CALIBRATION_MIN_BV_SPREAD = 0.5;
    private static final int CALIBRATION_STABILITY_WINDOW = 8;
    private static final double CALIBRATION_STABILITY_STD_MAX = 2.0;
    /** 标定前最近若干次 lux 采样，用于方差稳定判定与拒绝突变。 */
    private static final int CALIBRATION_LUX_STABILITY_WINDOW = 5;
    private static final int CALIBRATION_LUX_STABILITY_MIN_SAMPLES = 3;
    /** 允许变异系数 std/mean 的上限（与方差阈值 (relStd·mean)² 对应）。 */
    private static final double CALIBRATION_LUX_REL_STD_MAX = 0.12;
    /**
     * 方差绝对上限（lux²），避免极暗环境下仅按相对阈值过严；约等价 σ≤8 lux。
     */
    private static final double CALIBRATION_LUX_MAX_VARIANCE_ABS = 64.0;
    private final CameraContract.View view;
    private final CameraContract.Model model;
    private final Context appContext;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private float aperture = 1.8f;
    private ImageAnalysis imageAnalysis;
    private CameraModel.ExposureRecommendation lastRecommendation;
    private CameraModel.ExposureRecommendation lastEmittedRecommendation;
    private long manualModeUntilMs = 0L;
    private boolean aeLocked = false;
    private boolean deferHardwareApply = false;
    private boolean calibrationModeActive = false;
    private final CalibrationSession calibrationSession = new CalibrationSession();
    private final Deque<Double> recentLuxCalib = new ArrayDeque<>();
    private final Object luxStabilityLock = new Object();
    private final Deque<Double> recentMeanY = new ArrayDeque<>();
    private volatile double latestMeanY = Double.NaN;
    private volatile double latestSceneBv = Double.NaN;
    /** 与 {@link #latestMeanY} / {@link #latestSceneBv} 同一帧分析时刻的墙上时钟（ms），用于与传感器时间对齐。 */
    private volatile long latestFrameStatsWallTimeMs = 0L;
    private long lastCalibrationSampleTs = 0L;
    /**
     * 用于“拍照离线伪彩色”和“预览端伪彩色”保持一致：
     * 只有在这里（onBrightnessChanged）会调用 view.setPreviewBrightness(gain)。
     * 因此推荐/自动流程即使程序性修改 SeekBar，也不会破坏这个值。
     */
    private float lastPreviewBrightnessGain = 1.0f;
    /** 预览 Hue 全局拉伸：与离线 createPseudoColorImage 中 globalMin/globalMax 对齐（由分析帧估计） */
    private float previewHueMinSmoothed = 0f;
    private float previewHueMaxSmoothed = 1f;
    private boolean previewHueRangeInitialized = false;
    /** 上一档已显示的中心 L 对应 BV（{@link #CENTER_L_DISPLAY_BV_DEADBAND} 节流）。 */
    private double lastEmittedCenterLBv = Double.NaN;
    private static final float PREVIEW_HUE_RANGE_EMA = 0.2f;
    private final AutoTuneState autoTuneState = new AutoTuneState(
            AUTO_APPLY_STABLE_FRAME_COUNT_DEFAULT,
            AUTO_APPLY_STEP_RATIO_DEFAULT,
            AUTO_APPLY_BV_DELTA_THRESHOLD_DEFAULT
    );
    
    public CameraPresenter(CameraContract.View view, Context context) {
        this.view = view;
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.model = new CameraModel(context);
    }

    public void setCameraRanges(Range<Integer> isoRange, Range<Long> exposureRange, float aperture) {
        CameraSettings settings = model.getCameraSettings();
        settings.setIsoRange(isoRange);
        settings.setExposureRange(exposureRange);
        this.aperture = aperture;
        settings.setAperture(aperture);
        if (isoRange != null) settings.setIso(isoRange.getLower());
        if (exposureRange != null) settings.setExposureTime(exposureRange.getLower());
        model.updateCameraSettings(settings);
    }

    @Override
    public void onViewCreated() {
        AppState appState = model.getAppState();
        view.updateCameraStatus(appState.getCameraStatus());
        view.updatePhotoCount(appState.getPhotoCount());
        view.updateExposureValue("E = --");
        view.updateCurveSource(getCurveSourceLabel());
        view.updateCurveSourceBadge(getModel().getCurveBadgeShortLabel());
        String summary = getModel().getCalibrationSummary();
        view.updateCalibrationStatus(summary.isEmpty() ? "标定模式未开始" : summary);
    }

    @Override public void onResume() {}

    @Override
    public void onPause() {
        closeCamera();
        calibrationModeActive = false;
        calibrationSession.setStatus(CalibrationSession.Status.IDLE);
        previewHueRangeInitialized = false;
        previewHueMinSmoothed = 0f;
        previewHueMaxSmoothed = 1f;
        view.setPreviewPseudoHueRange(0f, 1f);
    }

    @Override
    public void onDestroy() {
        closeCamera();
        analysisExecutor.shutdown();
    }
    @Override public void initializeCamera() {}

    @Override
    public void startPreview() {
        AppState appState = model.getAppState();
        appState.setCameraStatus("相机就绪");
        model.updateAppState(appState);
        view.updateCameraStatus(appState.getCameraStatus());
    }

    @Override
    public void takePicture() {
        AppState appState = model.getAppState();
        appState.incrementPhotoCount();
        model.updateAppState(appState);
        view.updatePhotoCount(appState.getPhotoCount());
    }

    @Override
    public void closeCamera() {
        AppState appState = model.getAppState();
        appState.setCameraStatus("相机已关闭");
        model.updateAppState(appState);
        view.updateCameraStatus(appState.getCameraStatus());
    }

    @Override public void onPermissionGranted() {}

    @Override
    public void onPermissionDenied() {
        AppState appState = model.getAppState();
        appState.setCameraStatus("权限被拒绝");
        model.updateAppState(appState);
        view.updateCameraStatus(appState.getCameraStatus());
        view.showError("需要相机权限");
    }

    @Override
    public void onCameraOpened() {
        startPreview();
    }

    @Override
    public void onCameraDisconnected() {
        AppState appState = model.getAppState();
        appState.setCameraStatus("相机断开");
        model.updateAppState(appState);
        view.updateCameraStatus(appState.getCameraStatus());
    }

    @Override
    public void onCameraError(int error) {
        AppState appState = model.getAppState();
        appState.setCameraStatus("相机错误");
        appState.setLastError("错误码: " + error);
        model.updateAppState(appState);
        view.updateCameraStatus(appState.getCameraStatus());
        view.showError("相机错误: " + error);
    }

    @Override
    public void onImageCaptured(byte[] imageData) {
        takePicture();
    }

    public Bitmap createPseudoColorImage(Bitmap originalBitmap, String exifBrightness) {
        return model.createPseudoColorImage(originalBitmap, exifBrightness);
    }

    public Bitmap createPseudoColorImage(Bitmap originalBitmap, String exifBrightness, int rotationDegrees) {
        if (model instanceof CameraModel) {
            float gain = lastPreviewBrightnessGain;
            return ((CameraModel) model).createPseudoColorImage(originalBitmap, exifBrightness, rotationDegrees, gain);
        }
        return model.createPseudoColorImage(originalBitmap, exifBrightness);
    }

    // computeCurrentBrightnessGain() 已弃用：改为使用 lastPreviewBrightnessGain

    public void startAutoExposureAnalysis() {
        imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(new Size(AUTO_EXPOSURE_ANALYSIS_WIDTH, AUTO_EXPOSURE_ANALYSIS_HEIGHT))
                .build();

        imageAnalysis.setAnalyzer(analysisExecutor, image -> {
            try {
                CameraModel.ExposureRecommendation recommendation = analyzeExposureRecommendation(image);
                double meanY = estimateMeanLuma(image);
                latestMeanY = meanY;
                long frameWallMs = System.currentTimeMillis();
                latestFrameStatsWallTimeMs = frameWallMs;
                updatePreviewHueRangeFromAnalysis(image);
                if (recommendation != null) {
                    latestSceneBv = recommendation.currentSceneBV;
                    updateStability(meanY);
                    handleExposureRecommendation(recommendation, meanY);
                } else {
                    maybeEmitCenterLDisplayThrottled(meanY);
                }
            } finally {
                image.close();
            }
        });
    }

    public ImageAnalysis getImageAnalysis() {
        return imageAnalysis;
    }

    /** Debug：ImageAnalysis 估计的中心 ROI 平均 Y（0–255）。 */
    public double getLatestCenterMeanYForDebug() {
        return latestMeanY;
    }

    @Override
    public void onIsoChanged(int progress) {
        CameraSettings settings = model.getCameraSettings();
        Range<Integer> isoRange = settings.getIsoRange();
        if (isoRange == null) return;

        int minIso = Math.max(isoRange.getLower(), MANUAL_SAFE_MIN_ISO);
        int maxIso = Math.min(isoRange.getUpper(), MANUAL_SAFE_MAX_ISO);
        if (maxIso <= minIso) {
            minIso = isoRange.getLower();
            maxIso = isoRange.getUpper();
        }
        int iso = minIso + (int) ((maxIso - minIso) * (progress / (float) SEEK_MAX));
        settings.setIso(iso);
        model.updateCameraSettings(settings);

        view.setSeekBarProgress(R.id.seekBarBrightness, 0);
        view.updateIsoDisplay(iso);
        if (!deferHardwareApply) {
            view.applyCameraIsoParameter(iso);
        }
        updateExposureValueDisplay(settings);
    }

    @Override
    public void onExposureChanged(int progress) {
        CameraSettings settings = model.getCameraSettings();
        Range<Long> exposureRange = settings.getExposureRange();
        if (exposureRange == null) return;

        long minExposure = Math.max(exposureRange.getLower(), MANUAL_SAFE_MIN_EXPOSURE_NS);
        long maxExposure = Math.min(exposureRange.getUpper(), MANUAL_SAFE_MAX_EXPOSURE_NS);
        if (maxExposure <= minExposure) {
            minExposure = exposureRange.getLower();
            maxExposure = exposureRange.getUpper();
        }
        long exposure = minExposure
                + (long) ((maxExposure - minExposure) * (progress / (float) SEEK_MAX));
        settings.setExposureTime(exposure);
        model.updateCameraSettings(settings);

        view.setSeekBarProgress(R.id.seekBarBrightness, 0);
        view.updateExposureDisplay(formatExposureTime(exposure));
        if (!deferHardwareApply) {
            view.applyCameraExposureParameter(exposure);
        }
        updateExposureValueDisplay(settings);
    }

    @Override
    public void onBrightnessChanged(int progress) {
        CameraSettings settings = model.getCameraSettings();
        Range<Integer> isoRange = settings.getIsoRange();
        Range<Long> exposureRange = settings.getExposureRange();
        if (isoRange == null || exposureRange == null) return;

        int minIso = Math.max(isoRange.getLower(), MANUAL_SAFE_MIN_ISO);
        int maxIso = Math.min(isoRange.getUpper(), MANUAL_SAFE_MAX_ISO);
        if (maxIso <= minIso) {
            minIso = isoRange.getLower();
            maxIso = isoRange.getUpper();
        }
        long minExposure = Math.max(exposureRange.getLower(), MANUAL_SAFE_MIN_EXPOSURE_NS);
        long maxExposure = Math.min(exposureRange.getUpper(), MANUAL_SAFE_MAX_EXPOSURE_NS);
        if (maxExposure <= minExposure) {
            minExposure = exposureRange.getLower();
            maxExposure = exposureRange.getUpper();
        }

        int iso = minIso
                + (int) ((maxIso - minIso) * (progress / (float) SEEK_MAX));
        long exposure = minExposure
                + (long) ((maxExposure - minExposure) * (progress / (float) SEEK_MAX));

        settings.setIso(iso);
        settings.setExposureTime(exposure);
        model.updateCameraSettings(settings);

        view.setSeekBarProgress(R.id.seekBarIso, 0);
        view.setSeekBarProgress(R.id.seekBarExposure, 0);
        view.updateIsoDisplay(-1);
        view.updateExposureDisplay("—");

        if (!deferHardwareApply) {
            view.applyCameraIsoParameter(iso);
            view.applyCameraExposureParameter(exposure);
        }

        float gain = 0.5f + (progress / (float) SEEK_MAX) * 1.5f;
        lastPreviewBrightnessGain = gain;
        view.setPreviewBrightness(gain);
        view.updateBrightnessMode(String.format("×%.1f", gain));
        updateExposureValueDisplay(settings);
    }

    private void updateExposureValueDisplay(CameraSettings settings) {
        double exposureValue = model.calculateExposureValue(
                settings.getIso(), settings.getExposureTime(), aperture);
        view.updateExposureValue(String.format("E = %.2f", exposureValue));
    }

    private String formatExposureTime(long exposureNs) {
        double expSec = exposureNs / 1_000_000_000.0;
        if (expSec >= 1.0) {
            return String.format("%.2f s", expSec);
        }
        return String.format("1/%.0f s", 1.0 / expSec);
    }

    private boolean shouldEmitRecommendation(CameraModel.ExposureRecommendation candidate) {
        if (lastEmittedRecommendation == null) return true;
        return Math.abs(candidate.recommendedIso - lastEmittedRecommendation.recommendedIso) >= ISO_DEADBAND
                || Math.abs(candidate.recommendedExposureTime - lastEmittedRecommendation.recommendedExposureTime) >= EXPOSURE_DEADBAND_NS
                || Math.abs(candidate.currentSceneBV - lastEmittedRecommendation.currentSceneBV) >= BV_DEADBAND;
    }

    private CameraModel.ExposureRecommendation analyzeExposureRecommendation(ImageProxy image) {
        if (!(model instanceof CameraModel)) return null;
        return ((CameraModel) model).analyzeFrameForAutoExposure(image);
    }

    private void handleExposureRecommendation(CameraModel.ExposureRecommendation recommendation, double meanY) {
        lastRecommendation = recommendation;
        if (shouldEmitRecommendation(recommendation)) {
            lastEmittedRecommendation = recommendation;
            if (!isManualModeActive() && !aeLocked) {
                view.onExposureRecommendationChanged(recommendation);
            }
        }
        maybeEmitCenterLDisplayThrottled(meanY);
        maybeAutoApplyFromAnalyzer(recommendation);
    }

    /** 中心 L 与曝光推荐解耦，仅用较小 BV 死区节流，避免 UI 每帧刷新。 */
    private void maybeEmitCenterLDisplayThrottled(double meanY) {
        if (!(model instanceof CameraModel) || !Double.isFinite(meanY)) {
            return;
        }
        double bv = CurveParams.bvFromDn(meanY);
        if (!Double.isFinite(bv)) {
            return;
        }
        if (!Double.isFinite(lastEmittedCenterLBv)
                || Math.abs(bv - lastEmittedCenterLBv) >= CENTER_L_DISPLAY_BV_DEADBAND) {
            emitCenterLFromMeanY(meanY);
        }
    }

    private void emitCenterLFromMeanY(double meanY) {
        double bv = CurveParams.bvFromDn(meanY);
        if (Double.isFinite(bv)) {
            lastEmittedCenterLBv = bv;
        }
        if (!(model instanceof CameraModel)) {
            view.updateCenterLuminance(Double.NaN, meanY);
            return;
        }
        double l = ((CameraModel) model).computeLFromDn(meanY);
        view.updateCenterLuminance(l, meanY);
    }

    public void applyLatestExposureRecommendation() {
        applyLatestExposureRecommendationWithRatio(APPLY_SMOOTHING, false);
    }

    public void applyLatestExposureRecommendationInAutoMode() {
        applyLatestExposureRecommendationWithRatio(autoTuneState.stepRatio, true);
    }

    private void applyLatestExposureRecommendationWithRatio(double ratio, boolean forcePreviewSafeRange) {
        if (lastRecommendation == null) return;
        CameraSettings settings = model.getCameraSettings();
        Range<Integer> isoRange = settings.getIsoRange();
        Range<Long> exposureRange = settings.getExposureRange();
        if (isoRange == null || exposureRange == null) return;

        double safeRatio = Math.max(0.0, Math.min(1.0, ratio));
        int smoothedIso = (int) Math.round(
                settings.getIso() + (lastRecommendation.recommendedIso - settings.getIso()) * safeRatio);
        long smoothedExposure = Math.round(
                settings.getExposureTime() + (lastRecommendation.recommendedExposureTime - settings.getExposureTime()) * safeRatio);
        smoothedIso = Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), smoothedIso));
        smoothedExposure = Math.max(exposureRange.getLower(), Math.min(exposureRange.getUpper(), smoothedExposure));
        if (forcePreviewSafeRange) {
            smoothedIso = Math.max(PREVIEW_SAFE_MIN_ISO, Math.min(PREVIEW_SAFE_MAX_ISO, smoothedIso));
            smoothedExposure = Math.max(PREVIEW_SAFE_MIN_EXPOSURE_NS,
                    Math.min(PREVIEW_SAFE_MAX_EXPOSURE_NS, smoothedExposure));
        }

        int isoProgress = toProgressInt(
                smoothedIso, isoRange.getLower(), isoRange.getUpper());
        int exposureProgress = toProgressLong(
                smoothedExposure,
                exposureRange.getLower(),
                exposureRange.getUpper()
        );

        settings.setIso(smoothedIso);
        settings.setExposureTime(smoothedExposure);
        model.updateCameraSettings(settings);

        view.setSeekBarProgress(R.id.seekBarBrightness, 0);
        view.setSeekBarProgress(R.id.seekBarIso, isoProgress);
        view.setSeekBarProgress(R.id.seekBarExposure, exposureProgress);
        view.updateIsoDisplay(smoothedIso);
        view.updateExposureDisplay(formatExposureTime(smoothedExposure));
        view.applyCameraIsoParameter(smoothedIso);
        view.applyCameraExposureParameter(smoothedExposure);
        updateExposureValueDisplay(settings);
    }

    public void onUserManualAdjustmentStarted() {
        manualModeUntilMs = System.currentTimeMillis() + MANUAL_MODE_HOLD_MS;
        resetAutoTuneStability();
    }

    public boolean isManualModeActive() {
        return System.currentTimeMillis() < manualModeUntilMs;
    }

    public boolean toggleAELock() {
        aeLocked = !aeLocked;
        return aeLocked;
    }

    public void setDeferHardwareApply(boolean deferHardwareApply) {
        this.deferHardwareApply = deferHardwareApply;
    }

    // -------------------------------------------------------------------------
    // Auto Tune Controls
    // -------------------------------------------------------------------------

    public boolean toggleAutoTune() {
        autoTuneState.enabled = !autoTuneState.enabled;
        if (!autoTuneState.enabled) {
            resetAutoTuneStability();
        }
        return autoTuneState.enabled;
    }

    public boolean isAutoTuneEnabled() {
        return autoTuneState.enabled;
    }

    public void setAutoApplyStableFrameCount(int stableFrameCount) {
        if (stableFrameCount < 1) {
            stableFrameCount = AUTO_APPLY_STABLE_FRAME_COUNT_DEFAULT;
        }
        autoTuneState.requiredStableFrameCount = stableFrameCount;
        resetAutoTuneStability();
    }

    public int getAutoApplyStableFrameCount() {
        return autoTuneState.requiredStableFrameCount;
    }

    public void setAutoApplyStepRatio(double stepRatio) {
        if (stepRatio <= 0.0 || stepRatio > 1.0) {
            stepRatio = AUTO_APPLY_STEP_RATIO_DEFAULT;
        }
        autoTuneState.stepRatio = stepRatio;
    }

    public double getAutoApplyStepRatio() {
        return autoTuneState.stepRatio;
    }

    public void setAutoApplyBvDeltaThreshold(double threshold) {
        if (threshold <= 0.0) {
            threshold = AUTO_APPLY_BV_DELTA_THRESHOLD_DEFAULT;
        }
        autoTuneState.bvDeltaThreshold = threshold;
        resetAutoTuneStability();
    }

    public double getAutoApplyBvDeltaThreshold() {
        return autoTuneState.bvDeltaThreshold;
    }

    // -------------------------------------------------------------------------
    // Internal Helpers
    // -------------------------------------------------------------------------

    private int toProgressInt(int value, int min, int max) {
        if (max <= min) return 0;
        float ratio = (value - min) * 1f / (max - min);
        return Math.max(0, Math.min(SEEK_MAX, Math.round(ratio * SEEK_MAX)));
    }

    private int toProgressLong(long value, long min, long max) {
        if (max <= min) return 0;
        double ratio = (value - min) * 1.0 / (max - min);
        return Math.max(0, Math.min(SEEK_MAX, (int) Math.round(ratio * SEEK_MAX)));
    }

    private void maybeAutoApplyFromAnalyzer(CameraModel.ExposureRecommendation recommendation) {
        if (!autoTuneState.enabled || recommendation == null || isManualModeActive() || aeLocked) return;
        double bvDelta = recommendation.targetBV - recommendation.currentSceneBV;
        if (Math.abs(bvDelta) < autoTuneState.bvDeltaThreshold) {
            resetAutoTuneStability();
            return;
        }

        int direction = bvDelta > 0 ? 1 : -1;
        if (direction == autoTuneState.lastDirection) {
            autoTuneState.stableFrameCount++;
        } else {
            autoTuneState.lastDirection = direction;
            autoTuneState.stableFrameCount = 1;
        }
        if (autoTuneState.stableFrameCount < autoTuneState.requiredStableFrameCount) return;

        long now = System.currentTimeMillis();
        if (now - autoTuneState.lastAutoApplyTs < AUTO_APPLY_MIN_INTERVAL_MS) return;
        autoTuneState.lastAutoApplyTs = now;
        resetAutoTuneStability();
        view.requestAutoApplyRecommendation();
    }

    private void resetAutoTuneStability() {
        autoTuneState.lastDirection = 0;
        autoTuneState.stableFrameCount = 0;
    }

    @Override
    public void startCalibration() {
        calibrationModeActive = true;
        calibrationSession.clear();
        calibrationSession.setStatus(CalibrationSession.Status.SAMPLING);
        lastCalibrationSampleTs = 0L;
        recentMeanY.clear();
        synchronized (luxStabilityLock) {
            recentLuxCalib.clear();
        }
        view.updateCalibrationStatus("标定中：请对准灰卡并点击“采样”至少8次，建议缓慢调整曝光后再采样");
    }

    /**
     * 由界面层 {@link com.example.camera.sensor.LightSensorManager} 回调传入；仅在标定模式写入会话参考亮度并维护 lux 稳定窗口。
     */
    public void onAmbientLightSampleForCalibration(float lux, double luminanceCdM2) {
        if (!calibrationModeActive || !Float.isFinite(lux) || !Double.isFinite(luminanceCdM2)) {
            return;
        }
        calibrationSession.updateReferenceL(luminanceCdM2);
        synchronized (luxStabilityLock) {
            recentLuxCalib.addLast((double) lux);
            while (recentLuxCalib.size() > CALIBRATION_LUX_STABILITY_WINDOW) {
                recentLuxCalib.removeFirst();
            }
        }
    }

    /**
     * 使用窗口内 lux 的样本方差判定稳定：s² = Σ(x-μ)²/(n-1)，要求 s² ≤ max(绝对上限, (relStd·μ)²)。
     */
    private boolean isAmbientLuxStableForCalibration() {
        synchronized (luxStabilityLock) {
            int n = recentLuxCalib.size();
            if (n < CALIBRATION_LUX_STABILITY_MIN_SAMPLES) {
                return false;
            }
            double sum = 0.0;
            for (double v : recentLuxCalib) {
                sum += v;
            }
            double mean = sum / n;
            if (mean <= 1e-6) {
                return false;
            }
            double sumSqDiff = 0.0;
            for (double v : recentLuxCalib) {
                double d = v - mean;
                sumSqDiff += d * d;
            }
            double sampleVar = sumSqDiff / (n - 1);
            if (!Double.isFinite(sampleVar)) {
                return false;
            }
            double varThreshold = Math.max(
                    CALIBRATION_LUX_MAX_VARIANCE_ABS,
                    CALIBRATION_LUX_REL_STD_MAX * mean * CALIBRATION_LUX_REL_STD_MAX * mean
            );
            return sampleVar <= varThreshold;
        }
    }

    @Override
    public boolean isAmbientLuxStable() {
        if (!calibrationModeActive) {
            return true;
        }
        return isAmbientLuxStableForCalibration();
    }

    @Override
    public void captureCalibrationSample() {
        if (!calibrationModeActive) {
            view.updateCalibrationStatus("请先开始标定");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCalibrationSampleTs < CALIBRATION_SAMPLE_INTERVAL_MS) {
            view.updateCalibrationStatus("采样过快，请稍等再采样");
            return;
        }
        if (!Double.isFinite(latestSceneBv) || !Double.isFinite(latestMeanY)) {
            view.updateCalibrationStatus("当前帧数据无效，请稍后重试");
            return;
        }
        double refL = calibrationSession.getCurrentReferenceL();
        if (!Double.isFinite(refL) || refL <= 0.0) {
            if (appContext != null) {
                view.showToast(appContext.getString(R.string.calibration_capture_no_ambient));
            } else {
                view.showToast("无法获取环境光照度，请检查光线传感器或移动手机位置");
            }
            return;
        }
        if (!isAmbientLuxStableForCalibration()) {
            if (appContext != null) {
                view.updateCalibrationStatus(appContext.getString(R.string.calibration_ambient_unstable));
            } else {
                view.updateCalibrationStatus("环境光照度变化较快，请保持稳定后重试采样");
            }
            return;
        }
        boolean stable = isFrameStable();
        boolean validExposure = latestMeanY > 12 && latestMeanY < 245;
        boolean valid = stable && validExposure;
        long frameAt = latestFrameStatsWallTimeMs;
        String err = calibrationSession.captureSample(latestMeanY, latestSceneBv, frameAt, valid);
        if (err != null) {
            view.updateCalibrationStatus(err);
            return;
        }
        lastCalibrationSampleTs = now;
        int validCount = calibrationSession.getValidSampleCount();
        String quality = !stable ? "抖动" : (validExposure ? "有效" : "过曝/欠曝");
        view.updateCalibrationStatus("采样完成: " + validCount + "/" + CALIBRATION_MIN_SAMPLES + "，质量=" + quality);
    }

    @Override
    public void finishCalibration() {
        if (!calibrationModeActive) {
            view.updateCalibrationStatus("当前不在标定模式");
            return;
        }
        calibrationSession.setStatus(CalibrationSession.Status.FITTING);
        CurveParams fitted = fitCurveParams(calibrationSession.getSamples());
        if (fitted == null || !fitted.isValid()) {
            calibrationSession.setStatus(CalibrationSession.Status.FAILED);
            calibrationSession.setFailReason("样本不足或分布不够，拟合失败");
            view.updateCalibrationStatus("标定失败：样本不足或变化范围过小");
            return;
        }
        calibrationSession.setStatus(CalibrationSession.Status.DONE);
        calibrationSession.setFittedParams(fitted);
        getModel().setActiveCurveParams(fitted);
        int removed = fitted.getCalibrationOutliersRemoved();
        int kept = calibrationSession.getSamples().size();
        String summary = buildCalibrationSuccessMessage(fitted, kept, removed);
        getModel().saveCalibrationSummary(summary);
        view.updateCurveSource(getCurveSourceLabel());
        view.updateCurveSourceBadge(getModel().getCurveBadgeShortLabel());
        view.updateCalibrationStatus(summary);
        int fitN = fitted.getFitSampleCount();
        double r2 = fitted.getRSquared();
        double pa = fitted.a;
        double pb = fitted.b;
        // 步骤 5.5：先筛参数异常，再筛 LOW（R² / 样本数），最后才进入 HIGH/MEDIUM 分享流程
        if (CalibrationUploadPolicy.INSTANCE.isAbnormalParams(pa, pb)) {
            view.onCalibrationAbnormalComplete(fitted);
        } else if (fitN < CalibrationUploadPolicy.MIN_SAMPLE_COUNT_FOR_UPLOAD
                || !Double.isFinite(r2)
                || r2 < CalibrationUploadPolicy.MIN_R_SQUARED_FOR_UPLOAD) {
            view.onCalibrationLowQualityComplete(fitted);
        } else {
            String qualityTier = CalibrationUploadPolicy.INSTANCE.qualityFor(fitN, r2);
            view.onCalibrationHighMediumComplete(fitted, qualityTier);
        }
        calibrationModeActive = false;
    }

    @Override
    public boolean isCalibrationModeActive() {
        return calibrationModeActive;
    }

    // -------------------------------------------------------------------------
    // SeekBar 精确微调交互（长按 +/- 1/3 EV、双击数值输入）
    // -------------------------------------------------------------------------
    private static final double EV_MICRO_DELTA = 1.0 / 3.0;
    private static final float BRIGHTNESS_GAIN_MIN = 0.5f;
    private static final float BRIGHTNESS_GAIN_MAX = 2.0f;

    public int getCurrentIsoForDebug() {
        CameraSettings settings = model.getCameraSettings();
        return settings != null ? settings.getIso() : -1;
    }

    public double getCurrentExposureMsForDebug() {
        CameraSettings settings = model.getCameraSettings();
        return settings != null ? (settings.getExposureTime() / 1_000_000.0) : 0.0;
    }

    public float getCurrentBrightnessGainForDebug() {
        int progress = view != null ? view.getBrightnessProgress() : 0;
        float gain = BRIGHTNESS_GAIN_MIN + (progress / (float) SEEK_MAX) * (BRIGHTNESS_GAIN_MAX - BRIGHTNESS_GAIN_MIN);
        if (!Float.isFinite(gain)) return 1.0f;
        return gain;
    }

    public void adjustIsoByEv(double evDelta) {
        CameraSettings settings = model.getCameraSettings();
        Range<Integer> isoRange = settings.getIsoRange();
        if (isoRange == null) return;

        int minIso = Math.max(isoRange.getLower(), MANUAL_SAFE_MIN_ISO);
        int maxIso = Math.min(isoRange.getUpper(), MANUAL_SAFE_MAX_ISO);
        if (maxIso <= minIso) {
            minIso = isoRange.getLower();
            maxIso = isoRange.getUpper();
        }
        int current = settings.getIso();
        if (current <= 0) current = minIso;

        double factor = Math.pow(2.0, evDelta);
        int target = (int) Math.round(current * factor);
        setIsoByUserValue(target);
    }

    public void setIsoByUserValue(int iso) {
        CameraSettings settings = model.getCameraSettings();
        Range<Integer> isoRange = settings.getIsoRange();
        if (isoRange == null) return;

        int minIso = Math.max(isoRange.getLower(), MANUAL_SAFE_MIN_ISO);
        int maxIso = Math.min(isoRange.getUpper(), MANUAL_SAFE_MAX_ISO);
        if (maxIso <= minIso) {
            minIso = isoRange.getLower();
            maxIso = isoRange.getUpper();
        }

        int clamped = Math.max(minIso, Math.min(maxIso, iso));
        int progress = toProgressInt(clamped, minIso, maxIso);
        view.setSeekBarProgress(R.id.seekBarIso, progress);
        onIsoChanged(progress);
    }

    public void adjustExposureByEv(double evDelta) {
        CameraSettings settings = model.getCameraSettings();
        Range<Long> exposureRange = settings.getExposureRange();
        if (exposureRange == null) return;

        long minExposure = Math.max(exposureRange.getLower(), MANUAL_SAFE_MIN_EXPOSURE_NS);
        long maxExposure = Math.min(exposureRange.getUpper(), MANUAL_SAFE_MAX_EXPOSURE_NS);
        if (maxExposure <= minExposure) {
            minExposure = exposureRange.getLower();
            maxExposure = exposureRange.getUpper();
        }
        long current = settings.getExposureTime();
        if (current <= 0) current = minExposure;

        double factor = Math.pow(2.0, evDelta);
        long target = Math.round(current * factor);
        setExposureByNs(target);
    }

    public void setExposureByMillis(double exposureMs) {
        if (!Double.isFinite(exposureMs)) return;
        double ns = exposureMs * 1_000_000.0;
        if (!Double.isFinite(ns)) return;
        setExposureByNs((long) Math.round(ns));
    }

    private void setExposureByNs(long exposureNs) {
        CameraSettings settings = model.getCameraSettings();
        Range<Long> exposureRange = settings.getExposureRange();
        if (exposureRange == null) return;

        long minExposure = Math.max(exposureRange.getLower(), MANUAL_SAFE_MIN_EXPOSURE_NS);
        long maxExposure = Math.min(exposureRange.getUpper(), MANUAL_SAFE_MAX_EXPOSURE_NS);
        if (maxExposure <= minExposure) {
            minExposure = exposureRange.getLower();
            maxExposure = exposureRange.getUpper();
        }

        long clamped = Math.max(minExposure, Math.min(maxExposure, exposureNs));
        int progress = toProgressLong(clamped, minExposure, maxExposure);
        view.setSeekBarProgress(R.id.seekBarExposure, progress);
        onExposureChanged(progress);
    }

    public void adjustBrightnessGainByEv(double evDelta) {
        // 将“亮度增益”当作可缩放系数：gain *= 2^(evDelta)
        float currentGain = getCurrentBrightnessGainForDebug();
        float factor = (float) Math.pow(2.0, evDelta);
        float targetGain = currentGain * factor;
        setBrightnessGainByValue(targetGain);
    }

    public void setBrightnessGainByValue(float gain) {
        float clamped = gain;
        if (!Float.isFinite(clamped)) return;
        clamped = Math.max(BRIGHTNESS_GAIN_MIN, Math.min(BRIGHTNESS_GAIN_MAX, clamped));

        int progress = (int) Math.round(((clamped - BRIGHTNESS_GAIN_MIN) / (BRIGHTNESS_GAIN_MAX - BRIGHTNESS_GAIN_MIN)) * SEEK_MAX);
        progress = Math.max(0, Math.min(SEEK_MAX, progress));
        view.setSeekBarProgress(R.id.seekBarBrightness, progress);
        onBrightnessChanged(progress);
    }

    private CurveParams fitCurveParams(List<CalibrationSample> samples) {
        CalibrationFitter.FitResult result = CalibrationFitter.fitExpCurveResult(
                samples, CALIBRATION_MIN_SAMPLES, CALIBRATION_MIN_BV_SPREAD);
        if (result == null) {
            return null;
        }
        CurveParams params = result.getCurveParams();
        if (params != null && params.isValid()) {
            calibrationSession.retainOnlySamplesUsed(result.getSamplesUsed());
        }
        return params;
    }

    /**
     * 标定成功后的多行说明：拟合公式（BV–L，与 CameraModel 一致）、R²、保留样本数及 DN 示例亮度（cd/m²）。
     */
    private String buildCalibrationSuccessMessage(CurveParams fitted, int kept, int removed) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US,
                "拟合公式: L = %.4f × exp(%.4f × BV) cd/m²\nBV = log₂(DN/255)，DN 为 0–255 灰度\n",
                fitted.a, fitted.b));
        sb.append(String.format(Locale.US, "拟合优度 R² = %.3f\n", fitted.getRSquared()));
        sb.append(String.format(Locale.getDefault(), "保留样本=%d", kept));
        if (removed > 0) {
            sb.append(String.format(Locale.getDefault(), "，已剔除异常点=%d", removed));
        }
        if (fitted.isValid()) {
            int[] dns = new int[]{50, 128, 200};
            String[] labels = new String[]{"暗部", "中灰", "高光"};
            StringBuilder examples = new StringBuilder();
            for (int i = 0; i < dns.length; i++) {
                double l = fitted.computeL(dns[i]);
                if (Double.isFinite(l)) {
                    examples.append(String.format(Locale.US, "\n· DN=%d → %.1f cd/m² (%s)",
                            dns[i], l, labels[i]));
                }
            }
            if (examples.length() > 0) {
                sb.append("\n\n示例亮度:").append(examples);
            }
        }
        return "标定完成\n\n" + sb;
    }

    private double estimateMeanLuma(ImageProxy image) {
        if (image == null || image.getPlanes().length == 0) return Double.NaN;
        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        java.nio.ByteBuffer yBuffer = yPlane.getBuffer().duplicate();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();
        int startX = width / 4;
        int endX = width * 3 / 4;
        int startY = height / 4;
        int endY = height * 3 / 4;
        long sum = 0L;
        int count = 0;
        for (int y = startY; y < endY; y += 2) {
            int rowStart = y * rowStride;
            for (int x = startX; x < endX; x += 2) {
                int index = rowStart + x * pixelStride;
                if (index >= 0 && index < yBuffer.limit()) {
                    sum += (yBuffer.get(index) & 0xFF);
                    count++;
                }
            }
        }
        return count > 0 ? (sum * 1.0 / count) : Double.NaN;
    }

    private float[] estimateBoostedLumaMinMax(ImageProxy image, float gain) {
        if (image == null || image.getPlanes().length == 0) return null;
        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        java.nio.ByteBuffer yBuffer = yPlane.getBuffer().duplicate();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();
        float g = gain;
        if (!Float.isFinite(g) || g <= 0f) g = 1.0f;
        float minB = 1f;
        float maxB = 0f;
        int step = 4;
        for (int y = 0; y < height; y += step) {
            int rowStart = y * rowStride;
            for (int x = 0; x < width; x += step) {
                int index = rowStart + x * pixelStride;
                if (index >= 0 && index < yBuffer.limit()) {
                    float lumaN = (yBuffer.get(index) & 0xFF) / 255f;
                    float boosted = Math.min(1f, Math.max(0f, lumaN * g));
                    if (boosted < minB) minB = boosted;
                    if (boosted > maxB) maxB = boosted;
                }
            }
        }
        if (maxB < minB) return null;
        return new float[]{minB, maxB};
    }

    private void updatePreviewHueRangeFromAnalysis(ImageProxy image) {
        float[] minMax = estimateBoostedLumaMinMax(image, lastPreviewBrightnessGain);
        if (minMax == null) return;
        float minB = minMax[0];
        float maxB = minMax[1];
        if (maxB - minB < 1e-4f) {
            minB = 0f;
            maxB = 1f;
        }
        if (!previewHueRangeInitialized) {
            previewHueMinSmoothed = minB;
            previewHueMaxSmoothed = maxB;
            previewHueRangeInitialized = true;
        } else {
            float alpha = PREVIEW_HUE_RANGE_EMA;
            previewHueMinSmoothed += alpha * (minB - previewHueMinSmoothed);
            previewHueMaxSmoothed += alpha * (maxB - previewHueMaxSmoothed);
        }
        if (previewHueMaxSmoothed - previewHueMinSmoothed < 1e-4f) {
            previewHueMinSmoothed = 0f;
            previewHueMaxSmoothed = 1f;
        }
        view.setPreviewPseudoHueRange(previewHueMinSmoothed, previewHueMaxSmoothed);
    }

    private void updateStability(double meanY) {
        if (!Double.isFinite(meanY)) return;
        recentMeanY.addLast(meanY);
        while (recentMeanY.size() > CALIBRATION_STABILITY_WINDOW) {
            recentMeanY.removeFirst();
        }
    }

    private boolean isFrameStable() {
        if (recentMeanY.size() < 4) return false;
        double mean = 0.0;
        for (double value : recentMeanY) mean += value;
        mean /= recentMeanY.size();
        double variance = 0.0;
        for (double value : recentMeanY) {
            double diff = value - mean;
            variance += diff * diff;
        }
        variance /= recentMeanY.size();
        return Math.sqrt(variance) <= CALIBRATION_STABILITY_STD_MAX;
    }

    private CameraModel getModel() {
        return (CameraModel) model;
    }

    private String getCurveSourceLabel() {
        CurveParams params = getModel().getActiveCurveParams();
        return getModel().getCurveSourceLabel()
                + String.format(" (a=%.3f, b=%.3f)", params.a, params.b);
    }

    /** 供界面层保存分享记录等复用同一持久化实例。 */
    public CalibrationRepository getCalibrationRepository() {
        return getModel().getCalibrationRepository();
    }

    /** 云端标准曲线异步拉取完成后刷新内存与 UI。 */
    public void refreshCurveFromDisk() {
        getModel().reloadActiveCurveFromRepository();
        view.updateCurveSource(getCurveSourceLabel());
        view.updateCurveSourceBadge(getModel().getCurveBadgeShortLabel());
    }
}