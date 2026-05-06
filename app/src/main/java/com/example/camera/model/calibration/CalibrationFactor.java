package com.example.camera.model.calibration;

/**
 * 绝对亮度标定结果：将 Debevec 相对辐照度 {@code E_rel = exp(g(DN))} 映射到真实 {@code L}（cd/m²）的系数
 * {@code K = L_real / E_rel}（灰卡处标定）。
 */
public final class CalibrationFactor {

    /** 亮度计实测灰卡 cd/m²（高精度本地流程，可与云端「用户标定」对照）。 */
    public static final int ABS_LEVEL_BRIGHTNESS_METER = 2;
    /** 环境光传感器 lux 换算的估算 cd/m²，仅本地个人使用，不参与云端 uploads。 */
    public static final int ABS_LEVEL_SENSOR_LUX_ESTIMATE = 3;

    public final double k;
    public final double greyCardPixelValue;
    public final double greyCardLuminance;
    public final long calibrationTimestamp;
    /** {@link #ABS_LEVEL_BRIGHTNESS_METER} 或 {@link #ABS_LEVEL_SENSOR_LUX_ESTIMATE} */
    public final int absoluteCalibrationLevel;

    public CalibrationFactor(
            double k,
            double greyCardPixelValue,
            double greyCardLuminance,
            long calibrationTimestamp,
            int absoluteCalibrationLevel
    ) {
        this.k = k;
        this.greyCardPixelValue = greyCardPixelValue;
        this.greyCardLuminance = greyCardLuminance;
        this.calibrationTimestamp = calibrationTimestamp;
        this.absoluteCalibrationLevel = absoluteCalibrationLevel;
    }

    public boolean isSensorLuxEstimate() {
        return absoluteCalibrationLevel == ABS_LEVEL_SENSOR_LUX_ESTIMATE;
    }

    public boolean isValid() {
        boolean levelOk = absoluteCalibrationLevel == ABS_LEVEL_BRIGHTNESS_METER
                || absoluteCalibrationLevel == ABS_LEVEL_SENSOR_LUX_ESTIMATE;
        return levelOk
                && Double.isFinite(k) && k > 0.0
                && Double.isFinite(greyCardPixelValue)
                && Double.isFinite(greyCardLuminance) && greyCardLuminance > 0.0
                && calibrationTimestamp > 0L;
    }
}
