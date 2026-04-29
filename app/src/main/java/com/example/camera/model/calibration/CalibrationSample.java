package com.example.camera.model.calibration;

/**
 * 单次标定采样：场景曝光指数 {@code bv} 与灰卡区域平均亮度 {@code meanY} 来自图像分析；
 * {@code referenceL} 为与采样时刻对齐的<strong>真实物理视亮度</strong>（cd/m²），由环境光传感器测得
 * （lux→L 的换算在 {@link com.example.camera.sensor.LightSensorManager} 中完成），不再由 BV 先验反推。
 */
public class CalibrationSample {
    /** 场景 BV（图像统计）。 */
    public final double bv;
    /** 真实物理亮度 L（cd/m²），传感器测得，用于与 BV 建立标定曲线。 */
    public final double referenceL;
    /** 灰卡 ROI 平均亮度（数字量 DN，约 0–255）。 */
    public final double meanY;
    public final long timestamp;
    public final boolean valid;

    public CalibrationSample(double bv, double referenceL, double meanY, long timestamp, boolean valid) {
        this.bv = bv;
        this.referenceL = referenceL;
        this.meanY = meanY;
        this.timestamp = timestamp;
        this.valid = valid;
    }
}
