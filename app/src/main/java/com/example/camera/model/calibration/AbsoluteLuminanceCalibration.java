package com.example.camera.model.calibration;

import android.graphics.Rect;

/**
 * 灰卡 + 已知亮度（亮度计）对 Debevec 相对对数辐照度 {@code g(DN)} 做绝对标定。
 */
public final class AbsoluteLuminanceCalibration {

    private AbsoluteLuminanceCalibration() {
    }

    /**
     * 对灰度图矩形区域求平均 DN（0～255）；{@code image} 为行优先单通道，长度 {@code imageWidth * height}。
     */
    public static double computeRegionAverage(byte[] image, int imageWidth, Rect greyCardRegion) {
        if (image == null || imageWidth < 1 || greyCardRegion == null) {
            return Double.NaN;
        }
        int height = image.length / imageWidth;
        if (height < 1) {
            return Double.NaN;
        }
        int left = clamp(greyCardRegion.left, 0, imageWidth - 1);
        int right = clamp(greyCardRegion.right, 0, imageWidth - 1);
        int top = clamp(greyCardRegion.top, 0, height - 1);
        int bottom = clamp(greyCardRegion.bottom, 0, height - 1);
        if (right < left) {
            int t = left;
            left = right;
            right = t;
        }
        if (bottom < top) {
            int t = top;
            top = bottom;
            bottom = t;
        }
        double sum = 0.0;
        int n = 0;
        for (int y = top; y <= bottom; y++) {
            int row = y * imageWidth;
            for (int x = left; x <= right; x++) {
                sum += image[row + x] & 0xFF;
                n++;
            }
        }
        return n > 0 ? sum / n : Double.NaN;
    }

    /**
     * 由灰卡区域平均 DN、已知亮度与 {@code g[0..255]} 求标定系数 {@code K = L_real / exp(g[z])}，
     * 其中 {@code z} 为平均 DN 四舍五入到 0～255。
     */
    public static CalibrationFactor calibrateAbsoluteLuminance(
            byte[] image,
            int imageWidth,
            Rect greyCardRegion,
            double knownLuminance,
            double[] g
    ) {
        if (g == null || g.length < 256) {
            throw new IllegalArgumentException("g must have length at least 256");
        }
        if (!Double.isFinite(knownLuminance) || knownLuminance <= 0.0) {
            throw new IllegalArgumentException("knownLuminance must be finite and > 0");
        }
        double avgPixel = computeRegionAverage(image, imageWidth, greyCardRegion);
        if (!Double.isFinite(avgPixel)) {
            throw new IllegalArgumentException("invalid grey card region or image");
        }
        int z = (int) Math.round(Math.max(0.0, Math.min(255.0, avgPixel)));
        double lnE = g[z];
        double eRel = Math.exp(lnE);
        if (!Double.isFinite(eRel) || Math.abs(eRel) < 1e-18) {
            throw new IllegalStateException("invalid relative irradiance exp(g(DN))");
        }
        double k = knownLuminance / eRel;
        return new CalibrationFactor(k, avgPixel, knownLuminance, System.currentTimeMillis());
    }

    /** {@code L = K × E_rel}。 */
    public static double convertToRealLuminance(double eRel, CalibrationFactor factor) {
        if (factor == null || !factor.isValid()) {
            return Double.NaN;
        }
        if (!Double.isFinite(eRel)) {
            return Double.NaN;
        }
        double L = factor.k * eRel;
        return Double.isFinite(L) ? L : Double.NaN;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
