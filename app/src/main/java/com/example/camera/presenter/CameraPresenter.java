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
import com.example.camera.model.calibration.CalibrationSample;
import com.example.camera.model.calibration.CalibrationSession;
import com.example.camera.model.calibration.CalibrationFitter;
import com.example.camera.model.calibration.CurveParams;
import com.example.camera.presenter.state.AutoTuneState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
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
    private static final int CALIBRATION_MIN_SAMPLES = 8;
    private static final long CALIBRATION_SAMPLE_INTERVAL_MS = 700L;
    private static final double CALIBRATION_MIN_BV_SPREAD = 0.5;
    private static final int CALIBRATION_STABILITY_WINDOW = 8;
    private static final double CALIBRATION_STABILITY_STD_MAX = 2.0;
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
    private boolean calibrationModeActive = false;
    private final CalibrationSession calibrationSession = new CalibrationSession();
    private final Deque<Double> recentMeanY = new ArrayDeque<>();
    private double latestMeanY = Double.NaN;
    private double latestSceneBv = Double.NaN;
    private long lastCalibrationSampleTs = 0L;
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
        view.updateCurveSource(getCurveSourceLabel());
        String summary = getModel().getCalibrationSummary();
        view.updateCalibrationStatus(summary.isEmpty() ? "标定模式未开始" : summary);
    }

    @Override public void onResume() {}

    @Override
    public void onPause() {
        closeCamera();
        calibrationModeActive = false;
        calibrationSession.setStatus(CalibrationSession.Status.IDLE);
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
                latestMeanY = estimateMeanLuma(image);
                if (recommendation != null) {
                    latestSceneBv = recommendation.currentSceneBV;
                    updateStability(latestMeanY);
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

    @Override
    public void startCalibration() {
        calibrationModeActive = true;
        calibrationSession.clear();
        calibrationSession.setStatus(CalibrationSession.Status.SAMPLING);
        lastCalibrationSampleTs = 0L;
        recentMeanY.clear();
        view.updateCalibrationStatus("标定中：请对准灰卡并点击“采样”至少8次，建议缓慢调整曝光后再采样");
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
        lastCalibrationSampleTs = now;
        if (!Double.isFinite(latestSceneBv) || !Double.isFinite(latestMeanY)) {
            view.updateCalibrationStatus("当前帧数据无效，请稍后重试");
            return;
        }
        boolean stable = isFrameStable();
        boolean validExposure = latestMeanY > 12 && latestMeanY < 245;
        boolean valid = stable && validExposure;
        double referenceL = Math.max(1.0, latestMeanY / 255.0 * 100.0);
        CalibrationSample sample = new CalibrationSample(
                latestSceneBv,
                referenceL,
                latestMeanY,
                now,
                valid
        );
        calibrationSession.addSample(sample);
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
        String summary = String.format(
                "标定完成: a=%.4f, b=%.4f, 样本=%d",
                fitted.a, fitted.b, calibrationSession.getValidSampleCount());
        getModel().saveCalibrationSummary(summary);
        view.updateCurveSource(getCurveSourceLabel());
        view.updateCalibrationStatus(summary);
        calibrationModeActive = false;
    }

    @Override
    public boolean isCalibrationModeActive() {
        return calibrationModeActive;
    }

    private CurveParams fitCurveParams(List<CalibrationSample> samples) {
        return CalibrationFitter.fitExpCurve(samples, CALIBRATION_MIN_SAMPLES, CALIBRATION_MIN_BV_SPREAD);
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
}