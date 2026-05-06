package com.example.camera.model.calibration;

/**
 * 亮度域辅助量（与 APEX 指数曲线解耦后仍可用于调试或 DN 节流等）。
 */
public final class LumaMetrics {
    private LumaMetrics() {}

    /** 将 0–255 灰度 DN 映射为 BV 标度：{@code BV = log₂(max(DN/255, ε))}。 */
    public static double bvFromDn(double dn) {
        if (!Double.isFinite(dn)) {
            return Double.NaN;
        }
        double clamped = Math.max(0.0, Math.min(255.0, dn));
        double normalized = Math.max(clamped / 255.0, 1e-4);
        return Math.log(normalized) / Math.log(2.0);
    }
}
