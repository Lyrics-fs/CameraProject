package com.example.camera.model.calibration;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CalibrationSession {

    /** 图像统计与环境光读数允许的最大时间差（ms），与用户点击采样时刻共同校验。 */
    public static final long MAX_SENSOR_FRAME_SYNC_MS = 500L;

    public enum Status {
        IDLE,
        INSTRUCTION,
        SAMPLING,
        FITTING,
        DONE,
        FAILED
    }

    private final List<CalibrationSample> samples = new ArrayList<>();
    private Status status = Status.IDLE;
    private String failReason = "";
    private CurveParams fittedParams;

    /** 当前环境光传感器换算的视亮度 L（cd/m²），由 {@link #updateReferenceL(double)} 更新。 */
    private double currentReferenceL = Double.NaN;
    /** {@link #currentReferenceL} 最近一次更新的墙上时钟（ms）。 */
    private long referenceLUpdatedAtMs = 0L;

    public void clear() {
        samples.clear();
        status = Status.IDLE;
        failReason = "";
        fittedParams = null;
        currentReferenceL = Double.NaN;
        referenceLUpdatedAtMs = 0L;
    }

    /**
     * 由光线传感器回调更新当前参考亮度（真实物理亮度 cd/m²）。
     * 与 {@link #captureSample(double, double, long, boolean)} 在同一会话锁上同步，保证采样时一致。
     */
    public synchronized void updateReferenceL(double luminanceCdM2) {
        currentReferenceL = luminanceCdM2;
        referenceLUpdatedAtMs = System.currentTimeMillis();
    }

    public synchronized double getCurrentReferenceL() {
        return currentReferenceL;
    }

    public synchronized long getReferenceLUpdatedAtMs() {
        return referenceLUpdatedAtMs;
    }

    /**
     * 记录一次标定采样：{@code referenceL} 使用当前传感器亮度 {@link #currentReferenceL}，不再由 BV 反推。
     *
     * @param meanY                  图像 ROI 平均亮度（DN）
     * @param bv                     场景 BV（来自图像分析）
     * @param frameStatsWallTimeMs   产生上述 meanY/BV 的帧统计时间（墙上时钟 ms）
     * @param valid                  曝光/稳定性等业务层有效性
     * @return 失败时返回简短原因供 UI 展示；成功则返回 null 并已加入样本列表
     */
    @Nullable
    public synchronized String captureSample(
            double meanY,
            double bv,
            long frameStatsWallTimeMs,
            boolean valid
    ) {
        long nowMs = System.currentTimeMillis();
        if (!Double.isFinite(currentReferenceL) || currentReferenceL <= 0.0) {
            return "无有效的环境光亮度，请确认传感器未被遮挡或稍候再试";
        }
        if (!Double.isFinite(meanY) || !Double.isFinite(bv)) {
            return "当前帧数据无效，请稍后重试";
        }
        if (frameStatsWallTimeMs <= 0L) {
            return "图像统计未就绪，请稍后重试";
        }
        if (referenceLUpdatedAtMs <= 0L) {
            return "尚无环境光读数，请稍候再采样";
        }
        if (nowMs - frameStatsWallTimeMs > MAX_SENSOR_FRAME_SYNC_MS) {
            return "图像统计已过期，请保持画面稳定后重试";
        }
        if (nowMs - referenceLUpdatedAtMs > MAX_SENSOR_FRAME_SYNC_MS) {
            return "环境光读数已过期，请重试";
        }
        if (Math.abs(frameStatsWallTimeMs - referenceLUpdatedAtMs) > MAX_SENSOR_FRAME_SYNC_MS) {
            return "图像亮度与环境光测量不同步，请重试";
        }
        samples.add(new CalibrationSample(bv, currentReferenceL, meanY, nowMs, valid));
        return null;
    }

    public void addSample(CalibrationSample sample) {
        samples.add(sample);
    }

    public List<CalibrationSample> getSamples() {
        return Collections.unmodifiableList(samples);
    }

    /**
     * 标定拟合完成后，仅保留参与最终拟合的样本（与 {@link com.example.camera.model.calibration.CalibrationFitter.FitResult#getSamplesUsed()} 为同一引用）。
     * 未参与拟合的点（无效点、被剔除的异常点）将从会话中移除。
     */
    public synchronized void retainOnlySamplesUsed(List<CalibrationSample> usedInFit) {
        if (usedInFit == null) {
            return;
        }
        samples.retainAll(usedInFit);
    }

    public int getValidSampleCount() {
        int count = 0;
        for (CalibrationSample sample : samples) {
            if (sample.valid) count++;
        }
        return count;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public CurveParams getFittedParams() {
        return fittedParams;
    }

    public void setFittedParams(CurveParams fittedParams) {
        this.fittedParams = fittedParams;
    }
}
