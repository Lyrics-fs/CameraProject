package com.example.camera.model.calibration;

/**
 * 绝对亮度标定结果：将 Debevec 相对辐照度 {@code E_rel = exp(g(DN))} 映射到真实 {@code L}（cd/m²）的系数
 * {@code K = L_real / E_rel}（灰卡处标定）。
 */
public final class CalibrationFactor {

    public final double k;
    public final double greyCardPixelValue;
    public final double greyCardLuminance;
    public final long calibrationTimestamp;

    public CalibrationFactor(
            double k,
            double greyCardPixelValue,
            double greyCardLuminance,
            long calibrationTimestamp
    ) {
        this.k = k;
        this.greyCardPixelValue = greyCardPixelValue;
        this.greyCardLuminance = greyCardLuminance;
        this.calibrationTimestamp = calibrationTimestamp;
    }

    public boolean isValid() {
        return Double.isFinite(k) && k > 0.0
                && Double.isFinite(greyCardPixelValue)
                && Double.isFinite(greyCardLuminance) && greyCardLuminance > 0.0
                && calibrationTimestamp > 0L;
    }
}
