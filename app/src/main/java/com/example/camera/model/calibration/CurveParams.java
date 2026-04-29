package com.example.camera.model.calibration;

public class CurveParams {
    public static final double PRIOR_A = 2.9;
    public static final double PRIOR_B = 0.729;

    public enum Source {
        PRIOR,
        /** 云端机型标准曲线（device_profiles）。 */
        CLOUD,
        CALIBRATED
    }

    public final double a;
    public final double b;
    public final Source source;
    public final long updatedAt;

    /** 决定系数 R²（对 referenceL 与 a·e^(b·BV) 的拟合优度）；先验或未计算时为 0。 */
    private double rSquared;
    /** 本次标定拟合中因相对误差过大且剔除后 R² 明显提升而丢弃的有效点数量。 */
    private int calibrationOutliersRemoved;
    /** 参与最终拟合的样本数；先验或未持久化时为 0。 */
    private int fitSampleCount;

    /** 云端 profile 的版本号（仅 source=CLOUD 有效）。 */
    private int cloudProfileVersion;
    /** 云端聚合时参与统计的条数，用于展示「基于 X 台设备」。 */
    private int cloudAggregatedDeviceCount;
    /** 云端给出的置信度 [0,1]（仅 source=CLOUD 有效）。 */
    private double cloudConfidence;

    public CurveParams(double a, double b, Source source, long updatedAt) {
        this(a, b, source, updatedAt, 0.0);
    }

    public CurveParams(double a, double b, Source source, long updatedAt, double rSquared) {
        this.a = a;
        this.b = b;
        this.source = source;
        this.updatedAt = updatedAt;
        setRSquared(rSquared);
    }

    public static CurveParams prior() {
        return new CurveParams(PRIOR_A, PRIOR_B, Source.PRIOR, 0L, 0.0);
    }

    /**
     * 由云端 {@link com.example.camera.calibration.model.DeviceProfile} 构造标准曲线（不经由标定拟合的 R²）。
     */
    public static CurveParams fromCloudProfile(
            double defaultA,
            double defaultB,
            long updatedAtMillis,
            int profileVersion,
            int aggregatedSampleCount,
            double confidence
    ) {
        CurveParams c = new CurveParams(defaultA, defaultB, Source.CLOUD, updatedAtMillis, 0.0);
        c.setCloudProfileVersion(profileVersion);
        c.setCloudAggregatedDeviceCount(Math.max(0, aggregatedSampleCount));
        c.setCloudConfidence(Double.isFinite(confidence) ? confidence : 0.0);
        c.setFitSampleCount(Math.max(0, aggregatedSampleCount));
        return c;
    }

    public boolean isValid() {
        return a > 0.0 && Double.isFinite(a) && Double.isFinite(b);
    }

    public double getRSquared() {
        return rSquared;
    }

    public void setRSquared(double rSquared) {
        this.rSquared = Double.isFinite(rSquared) ? rSquared : 0.0;
    }

    /** 标定曲线是否达到常用经验阈值（R² ≥ 0.95）。 */
    public boolean isHighQuality() {
        return rSquared >= 0.95;
    }

    public int getCalibrationOutliersRemoved() {
        return calibrationOutliersRemoved;
    }

    public void setCalibrationOutliersRemoved(int calibrationOutliersRemoved) {
        this.calibrationOutliersRemoved = Math.max(0, calibrationOutliersRemoved);
    }

    public int getFitSampleCount() {
        return fitSampleCount;
    }

    public void setFitSampleCount(int fitSampleCount) {
        this.fitSampleCount = Math.max(0, fitSampleCount);
    }

    public int getCloudProfileVersion() {
        return cloudProfileVersion;
    }

    public void setCloudProfileVersion(int cloudProfileVersion) {
        this.cloudProfileVersion = Math.max(0, cloudProfileVersion);
    }

    public int getCloudAggregatedDeviceCount() {
        return cloudAggregatedDeviceCount;
    }

    public void setCloudAggregatedDeviceCount(int cloudAggregatedDeviceCount) {
        this.cloudAggregatedDeviceCount = Math.max(0, cloudAggregatedDeviceCount);
    }

    public double getCloudConfidence() {
        return cloudConfidence;
    }

    public void setCloudConfidence(double cloudConfidence) {
        this.cloudConfidence = Double.isFinite(cloudConfidence) ? cloudConfidence : 0.0;
    }

    /**
     * 与 {@link com.example.camera.model.CameraModel} 分析链路一致：由中心 ROI 平均灰度 DN（0～255）得到 BV。
     * {@code BV = log₂(max(DN/255, ε))}。
     */
    public static double bvFromDn(double dn) {
        if (!Double.isFinite(dn)) {
            return Double.NaN;
        }
        double clamped = Math.max(0.0, Math.min(255.0, dn));
        double normalized = Math.max(clamped / 255.0, 1e-4);
        return Math.log(normalized) / Math.log(2.0);
    }

    /**
     * 由标定指数曲线计算亮度（cd/m²）：{@code L = a × exp(b × BV)}，其中 {@code BV = bvFromDn(DN)}。
     * <p>
     * 注意：拟合系数 {@code a、b} 是以 BV 为自变量得到的，并非将 DN 直接代入指数项。
     */
    public double computeLFromBv(double bv) {
        if (!isValid() || !Double.isFinite(bv)) {
            return Double.NaN;
        }
        double l = a * Math.exp(b * bv);
        return Double.isFinite(l) ? l : Double.NaN;
    }

    /**
     * 由灰度 DN（0～255）计算 L（cd/m²）：内部先 {@link #bvFromDn(double)} 再 {@link #computeLFromBv(double)}。
     */
    public double computeL(double dn) {
        return computeLFromBv(bvFromDn(dn));
    }

    /** @see #computeL(double) */
    public double computeL(float dn) {
        return computeL((double) dn);
    }
}
