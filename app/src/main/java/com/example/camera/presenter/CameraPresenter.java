package com.example.camera.presenter;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Size;
import android.util.Range;

import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.example.camera.R;
import com.example.camera.contract.CameraContract;
import com.example.camera.model.AppState;
import com.example.camera.model.CameraModel;
import com.example.camera.model.CameraSettings;
import com.example.camera.presenter.state.AutoTuneState;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraPresenter implements CameraContract.Presenter {
    private static final int SEEK_MAX = 5000;
    private static final int AUTO_EXPOSURE_ANALYSIS_WIDTH = 640;
    private static final int AUTO_EXPOSURE_ANALYSIS_HEIGHT = 480;
    private static final int ISO_DEADBAND = 30;
    private static final long EXPOSURE_DEADBAND_NS = 500_000L;
    private static final double BV_DEADBAND = 0.15;
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
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private float aperture = 1.8f;
    private ImageAnalysis imageAnalysis;
    private CameraModel.ExposureRecommendation lastRecommendation;
    private CameraModel.ExposureRecommendation lastEmittedRecommendation;
    private long manualModeUntilMs = 0L;
    private boolean aeLocked = false;
    private boolean deferHardwareApply = false;
    private final AutoTuneState autoTuneState = new AutoTuneState(
            AUTO_APPLY_STABLE_FRAME_COUNT_DEFAULT,
            AUTO_APPLY_STEP_RATIO_DEFAULT,
            AUTO_APPLY_BV_DELTA_THRESHOLD_DEFAULT
    );
    
    public CameraPresenter(CameraContract.View view, Context context) {
        this.view = view;
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
    }

    @Override public void onResume() {}

    @Override
    public void onPause() {
        closeCamera();
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
            return ((CameraModel) model).createPseudoColorImage(originalBitmap, exifBrightness, rotationDegrees);
        }
        return model.createPseudoColorImage(originalBitmap, exifBrightness);
    }

    public void startAutoExposureAnalysis() {
        imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(new Size(AUTO_EXPOSURE_ANALYSIS_WIDTH, AUTO_EXPOSURE_ANALYSIS_HEIGHT))
                .build();

        imageAnalysis.setAnalyzer(analysisExecutor, image -> {
            try {
                CameraModel.ExposureRecommendation recommendation = analyzeExposureRecommendation(image);
                if (recommendation != null) {
                    handleExposureRecommendation(recommendation);
                }
            } finally {
                image.close();
            }
        });
    }

    public ImageAnalysis getImageAnalysis() {
        return imageAnalysis;
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

    private void handleExposureRecommendation(CameraModel.ExposureRecommendation recommendation) {
        lastRecommendation = recommendation;
        if (shouldEmitRecommendation(recommendation)) {
            lastEmittedRecommendation = recommendation;
            if (!isManualModeActive() && !aeLocked) {
                view.onExposureRecommendationChanged(recommendation);
            }
        }
        maybeAutoApplyFromAnalyzer(recommendation);
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
}