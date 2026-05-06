package com.example.camera.presenter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.util.Size;
import android.util.Range;

import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.example.camera.R;
import com.example.camera.contract.CameraContract;
import com.example.camera.data.CalibrationRepository;
import com.example.camera.model.AppState;
import com.example.camera.model.CameraModel;
import com.example.camera.model.CameraSettings;
import com.example.camera.model.calibration.AbsoluteLuminanceCalibration;
import com.example.camera.model.calibration.CalibrationFactor;
import com.example.camera.model.calibration.DebevecSolveResult;
import com.example.camera.model.calibration.DebevecSolver;
import com.example.camera.presenter.state.AutoTuneState;

import java.util.ArrayList;
import java.util.Comparator;
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
    /** 预览中心 L 显示节流：按中心 ROI 平均 DN（0–255）变化。 */
    private static final double CENTER_L_DISPLAY_DN_DEADBAND = 0.6;
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
    private volatile double latestMeanY = Double.NaN;
    /** 最近一次成功保存 Debevec g 时的简短诊断（供界面结果区展示）。 */
    private volatile String lastDebevecDiagnosticsSummary = "";
    /** 与 {@link ImageAnalysis} 同分辨率的 Y 平面紧凑拷贝（行优先，长度 w×h），供灰卡标定取样。 */
    private final Object analysisYSnapshotLock = new Object();
    private byte[] lastAnalysisYCompact;
    private int lastAnalysisW;
    private int lastAnalysisH;
    private volatile double latestSceneBv = Double.NaN;
    /** 与 {@link #latestMeanY} / {@link #latestSceneBv} 同一帧分析时刻的墙上时钟（ms），用于与传感器时间对齐。 */
    private volatile long latestFrameStatsWallTimeMs = 0L;
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
    /** 上一档已显示的中心 L 对应的平均 DN（{@link #CENTER_L_DISPLAY_DN_DEADBAND} 节流）。 */
    private double lastEmittedCenterMeanDn = Double.NaN;
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
        view.updateCalibrationStatus(appContext != null
                ? appContext.getString(R.string.panel_calibration_status_hint)
                : "响应曲线与绝对亮度：请展开预览左下角 Debevec 标定块");
    }

    @Override public void onResume() {}

    @Override
    public void onPause() {
        closeCamera();
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
                snapshotAnalysisYPlane(image);
                CameraModel.ExposureRecommendation recommendation = analyzeExposureRecommendation(image);
                double meanY = estimateMeanLuma(image);
                latestMeanY = meanY;
                long frameWallMs = System.currentTimeMillis();
                latestFrameStatsWallTimeMs = frameWallMs;
                updatePreviewHueRangeFromAnalysis(image);
                if (recommendation != null) {
                    latestSceneBv = recommendation.currentSceneBV;
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

    /** 与灰卡归一化选区映射的 {@link ImageAnalysis} Y 平面宽度；尚无帧时为 0。 */
    public int getLastImageAnalysisWidth() {
        return lastAnalysisW;
    }

    /** 与灰卡归一化选区映射的 {@link ImageAnalysis} Y 平面高度；尚无帧时为 0。 */
    public int getLastImageAnalysisHeight() {
        return lastAnalysisH;
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

    /** 中心 L 与曝光推荐解耦，按 DN 死区节流，避免 UI 每帧刷新。 */
    private void maybeEmitCenterLDisplayThrottled(double meanY) {
        if (!(model instanceof CameraModel) || !Double.isFinite(meanY)) {
            return;
        }
        if (!Double.isFinite(lastEmittedCenterMeanDn)
                || Math.abs(meanY - lastEmittedCenterMeanDn) >= CENTER_L_DISPLAY_DN_DEADBAND) {
            emitCenterLFromMeanY(meanY);
        }
    }

    private void emitCenterLFromMeanY(double meanY) {
        lastEmittedCenterMeanDn = meanY;
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

    private void snapshotAnalysisYPlane(ImageProxy image) {
        if (image == null || image.getPlanes().length == 0) {
            return;
        }
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            return;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        if (w < 1 || h < 1) {
            return;
        }
        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        java.nio.ByteBuffer buf = yPlane.getBuffer().duplicate();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();
        byte[] compact = new byte[w * h];
        int idx = 0;
        for (int y = 0; y < h; y++) {
            int rowStart = y * rowStride;
            for (int x = 0; x < w; x++) {
                compact[idx++] = buf.get(rowStart + x * pixelStride);
            }
        }
        synchronized (analysisYSnapshotLock) {
            lastAnalysisYCompact = compact;
            lastAnalysisW = w;
            lastAnalysisH = h;
        }
    }

    /**
     * 当前分析帧上，归一化矩形（相对帧宽高 0～1）内 Y 通道平均值（0～255）。
     */
    public double computeMeanYInNormalizedRect(float nl, float nt, float nr, float nb) {
        synchronized (analysisYSnapshotLock) {
            if (lastAnalysisYCompact == null || lastAnalysisW < 1) {
                return Double.NaN;
            }
            int w = lastAnalysisW;
            int h = lastAnalysisH;
            int x0 = clampInt((int) Math.floor(nl * w), 0, w - 1);
            int x1 = clampInt((int) Math.ceil(nr * w) - 1, 0, w - 1);
            int y0 = clampInt((int) Math.floor(nt * h), 0, h - 1);
            int y1 = clampInt((int) Math.ceil(nb * h) - 1, 0, h - 1);
            if (x1 < x0) {
                int t = x0;
                x0 = x1;
                x1 = t;
            }
            if (y1 < y0) {
                int t = y0;
                y0 = y1;
                y1 = t;
            }
            long sum = 0L;
            int n = 0;
            byte[] b = lastAnalysisYCompact;
            for (int y = y0; y <= y1; y++) {
                int row = y * w;
                for (int x = x0; x <= x1; x++) {
                    sum += (b[row + x] & 0xFF);
                    n++;
                }
            }
            return n > 0 ? (sum * 1.0 / n) : Double.NaN;
        }
    }

    /**
     * 用当前分析帧 Y 平面与归一化灰卡选区、已知亮度写入 {@link CalibrationRepository}。
     */
    public CalibrationFactor calibrateAbsoluteLuminanceFromNormRect(
            float nl, float nt, float nr, float nb,
            double knownLuminanceCdM2
    ) {
        CalibrationRepository repo = getCalibrationRepository();
        if (repo == null) {
            throw new IllegalStateException("no repository");
        }
        double[] g = repo.loadDebevecG();
        if (g == null || g.length < 256) {
            throw new IllegalStateException("请先完成 Debevec 标定并保存 g(DN)");
        }
        byte[] image;
        int w;
        int h;
        synchronized (analysisYSnapshotLock) {
            if (lastAnalysisYCompact == null) {
                throw new IllegalStateException("尚无分析帧，请保持预览开启后重试");
            }
            image = java.util.Arrays.copyOf(lastAnalysisYCompact, lastAnalysisYCompact.length);
            w = lastAnalysisW;
            h = lastAnalysisH;
        }
        int x0 = clampInt((int) Math.floor(nl * w), 0, w - 1);
        int x1 = clampInt((int) Math.ceil(nr * w) - 1, 0, w - 1);
        int y0 = clampInt((int) Math.floor(nt * h), 0, h - 1);
        int y1 = clampInt((int) Math.ceil(nb * h) - 1, 0, h - 1);
        if (x1 < x0) {
            int t = x0;
            x0 = x1;
            x1 = t;
        }
        if (y1 < y0) {
            int t = y0;
            y0 = y1;
            y1 = t;
        }
        Rect r = new Rect(x0, y0, x1, y1);
        CalibrationFactor f = AbsoluteLuminanceCalibration.calibrateAbsoluteLuminance(
                image, w, r, knownLuminanceCdM2, g);
        repo.saveCalibrationFactor(f);
        return f;
    }

    public void saveDebevecG(double[] g) {
        if (getCalibrationRepository() != null) {
            getCalibrationRepository().saveDebevecG(g);
        }
    }

    /**
     * 在后台线程对曝光序列灰度栈做 Debevec–Malik 解算，成功后 {@link #saveDebevecG(double[])} 并回调
     * {@link CameraContract.View#onDebevecGSaved()}。
     */
    public void solveAndSaveDebevecGFromFrames(List<ExposureSequenceCapture.CapturedFrame> frames) {
        analysisExecutor.execute(() -> {
            try {
                if (frames == null || frames.size() < 4) {
                    view.showToast("有效帧过少（至少 4 张），无法解算 g(DN)");
                    return;
                }
                ArrayList<ExposureSequenceCapture.CapturedFrame> sorted =
                        new ArrayList<>(frames);
                sorted.sort(Comparator.comparingInt(ExposureSequenceCapture.CapturedFrame::getIndex));
                int w0 = sorted.get(0).getWidth();
                int h0 = sorted.get(0).getHeight();
                ArrayList<byte[]> images = new ArrayList<>(sorted.size());
                ArrayList<Double> times = new ArrayList<>(sorted.size());
                for (ExposureSequenceCapture.CapturedFrame f : sorted) {
                    if (f.getWidth() != w0 || f.getHeight() != h0) {
                        view.showToast("各帧分辨率不一致，已放弃解算");
                        return;
                    }
                    images.add(f.getPixels());
                    times.add(f.getExposureTime());
                }
                DebevecSolver solver = new DebevecSolver();
                DebevecSolveResult result = solver.solveWithDiagnostics(
                        images,
                        times,
                        40.0,
                        200
                );
                saveDebevecG(result.getG());
                lastDebevecDiagnosticsSummary = String.format(Locale.US,
                        "g(DN) 已保存，R²_lnΔt = %.3f",
                        result.getRSquaredLogDeltaT());
                view.showToast(String.format(Locale.US,
                        "Debevec g(DN) 已保存（校验 R²_lnΔt = %.3f）",
                        result.getRSquaredLogDeltaT()));
                view.updateCalibrationStatus(String.format(Locale.US,
                        "Debevec g(DN) 已保存，R²_lnΔt=%.3f；可输入灰卡亮度做绝对标定",
                        result.getRSquaredLogDeltaT()));
                view.onDebevecGSaved();
            } catch (Exception e) {
                String msg = e.getMessage();
                view.showToast(msg != null ? msg : "Debevec 解算失败");
            }
        });
    }

    public String getLastDebevecDiagnosticsSummary() {
        String s = lastDebevecDiagnosticsSummary;
        return s != null ? s : "";
    }

    /** 已保存 Debevec {@code g} 但尚未完成灰卡绝对标定（预览中心 L 应显示「未校准」）。 */
    public boolean isDebevecAwaitingAbsoluteCalibration() {
        CalibrationRepository r = getCalibrationRepository();
        return r != null && r.hasDebevecG() && !r.isAbsoluteLuminanceCalibrated();
    }

    /** 绝对标定后刷新左上角中心 L 文案。 */
    public void refreshCenterLuminanceDisplay() {
        if (Double.isFinite(latestMeanY)) {
            emitCenterLFromMeanY(latestMeanY);
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
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

    private CameraModel getModel() {
        return (CameraModel) model;
    }

    private String getCurveSourceLabel() {
        return getModel().getCurveSourceLabel();
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