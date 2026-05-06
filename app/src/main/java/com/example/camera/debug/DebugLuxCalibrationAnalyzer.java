package com.example.camera.debug;

import androidx.annotation.NonNull;

import com.example.camera.sensor.SensorCalibrationStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 根据调试 CSV 行计算 lux→L 线性/分段 scale（含 2σ 异常点检测）。
 * <p>
 * 线性 vs 分段：由比值变异系数 CV = std/avg 判定；分段按传感器 lux
 * {@value SensorCalibrationStore#LUX_BAND_LOW_MAX} / {@value SensorCalibrationStore#LUX_BAND_MID_MAX} 分为低/中/高三段。
 */
public final class DebugLuxCalibrationAnalyzer {

    /** App 侧公式系数：0.18/π */
    public static final double LUX_TO_APP_L_FACTOR = 0.18 / Math.PI;

    /** CV &lt; 此值视为线性误差，推荐全局 scale = 比值均值。 */
    public static final double CV_LINEAR_THRESHOLD = 0.1;

    public static final class Row {
        public final double lux;
        public final double meterCdM2;
        public final double appComputedCdM2;
        public final double bv;

        public Row(double lux, double meterCdM2, double appComputedCdM2, double bv) {
            this.lux = lux;
            this.meterCdM2 = meterCdM2;
            this.appComputedCdM2 = appComputedCdM2;
            this.bv = bv;
        }
    }

    /**
     * 对有效样本做 2σ 异常扫描（比值 = 亮度计 / App 亮度）。
     */
    public static final class OutlierScan {
        public final List<Row> valid;
        public final List<Double> ratios;
        public final boolean[] isOutlier;
        public final double ratioMean;
        public final double ratioStd;
        public final double ratioCv;

        OutlierScan(List<Row> valid, List<Double> ratios, boolean[] isOutlier,
                    double ratioMean, double ratioStd, double ratioCv) {
            this.valid = valid;
            this.ratios = ratios;
            this.isOutlier = isOutlier;
            this.ratioMean = ratioMean;
            this.ratioStd = ratioStd;
            this.ratioCv = ratioCv;
        }

        public int getOutlierCount() {
            int c = 0;
            for (boolean b : isOutlier) {
                if (b) {
                    c++;
                }
            }
            return c;
        }

        @NonNull
        public List<Row> rowsWithoutOutliers() {
            List<Row> out = new ArrayList<>();
            for (int i = 0; i < valid.size(); i++) {
                if (!isOutlier[i]) {
                    out.add(valid.get(i));
                }
            }
            return out;
        }
    }

    public static final class Result {
        public final boolean linear;
        /** 比值变异系数 std/|avg|（拟合样本） */
        public final double ratioCv;
        public final double ratioMean;
        /** 比值样本标准差 */
        public final double ratioStd;
        /** 参与拟合的有效样本数（剔除后） */
        public final int effectiveSampleCount;
        /** CSV 解析后、剔除前的有效组数 */
        public final int totalSampleCount;
        /** 2σ 标记的异常点数量 */
        public final int flaggedOutlierCount;
        /** 用户是否选择剔除异常点后再算 */
        public final boolean outliersRemoved;
        public final double linearScale;
        public final double scaleLow;
        public final double scaleMid;
        public final double scaleHigh;
        /** 分段为 lux 分界时记 100 / 1000，仅展示兼容；持久化用 {@link SensorCalibrationStore#luxSegmentsByIlluminanceBands}。 */
        public final double thr1;
        public final double thr2;

        Result(boolean linear, double ratioCv, double ratioMean, double ratioStd,
               int effectiveSampleCount, int totalSampleCount, int flaggedOutlierCount, boolean outliersRemoved,
               double linearScale, double scaleLow, double scaleMid, double scaleHigh,
               double thr1, double thr2) {
            this.linear = linear;
            this.ratioCv = ratioCv;
            this.ratioMean = ratioMean;
            this.ratioStd = ratioStd;
            this.effectiveSampleCount = effectiveSampleCount;
            this.totalSampleCount = totalSampleCount;
            this.flaggedOutlierCount = flaggedOutlierCount;
            this.outliersRemoved = outliersRemoved;
            this.linearScale = linearScale;
            this.scaleLow = scaleLow;
            this.scaleMid = scaleMid;
            this.scaleHigh = scaleHigh;
            this.thr1 = thr1;
            this.thr2 = thr2;
        }
    }

    @NonNull
    public static List<Row> filterValidRows(List<Row> rows) {
        List<Row> valid = new ArrayList<>();
        for (Row r : rows) {
            if (Double.isFinite(r.meterCdM2) && Double.isFinite(r.appComputedCdM2)
                    && r.appComputedCdM2 > 1e-9 && r.meterCdM2 > 0.0) {
                valid.add(r);
            }
        }
        return valid;
    }

    /**
     * 在 valid 上计算比值均值、样本标准差，并标记 |ratio−mean| &gt; 2σ 的点。
     */
    @NonNull
    public static OutlierScan scanOutliers(List<Row> valid) {
        List<Double> ratios = new ArrayList<>();
        for (Row r : valid) {
            ratios.add(r.meterCdM2 / r.appComputedCdM2);
        }
        int n = ratios.size();
        boolean[] isOutlier = new boolean[n];
        if (n == 0) {
            return new OutlierScan(valid, ratios, isOutlier, Double.NaN, 0.0, Double.NaN);
        }
        double mean = 0.0;
        for (double x : ratios) {
            mean += x;
        }
        mean /= n;
        double std = 0.0;
        if (n >= 2) {
            double var = 0.0;
            for (double x : ratios) {
                double d = x - mean;
                var += d * d;
            }
            std = Math.sqrt(var / (n - 1));
        }
        double absMean = Math.abs(mean) < 1e-12 ? 1e-12 : Math.abs(mean);
        double cv = std / absMean;

        if (std > 1e-12) {
            double thr = 2.0 * std;
            for (int i = 0; i < n; i++) {
                if (Math.abs(ratios.get(i) - mean) > thr) {
                    isOutlier[i] = true;
                }
            }
        }

        return new OutlierScan(valid, ratios, isOutlier, mean, std, cv);
    }

    /**
     * 在已选定的样本集上拟合 scale（totalSampleCount / flagged / removed 仅用于展示）。
     * <p>
     * 判定：CV = std/avg；若 CV &lt; 0.1 则线性 scale=avg；否则按 lux &lt;100、[100,1000)、≥1000 三分段取各段比值均值。
     */
    @NonNull
    public static Result analyze(
            List<Row> rowsForFit,
            int totalSampleCount,
            int flaggedOutlierCount,
            boolean outliersRemoved
    ) {
        List<Row> valid = filterValidRows(rowsForFit);
        List<Double> ratios = new ArrayList<>();
        for (Row r : valid) {
            ratios.add(r.meterCdM2 / r.appComputedCdM2);
        }
        int n = ratios.size();
        if (n < 1) {
            return new Result(true, Double.NaN, 1.0, 0.0, 0, totalSampleCount, flaggedOutlierCount, outliersRemoved,
                    1.0, 1.0, 1.0, 1.0,
                    SensorCalibrationStore.LUX_BAND_LOW_MAX, SensorCalibrationStore.LUX_BAND_MID_MAX);
        }
        if (n == 1) {
            double single = ratios.get(0);
            return new Result(true, 0.0, single, 0.0, n, totalSampleCount, flaggedOutlierCount, outliersRemoved,
                    single, single, single, single,
                    SensorCalibrationStore.LUX_BAND_LOW_MAX, SensorCalibrationStore.LUX_BAND_MID_MAX);
        }

        double avgR = 0.0;
        for (double x : ratios) {
            avgR += x;
        }
        avgR /= n;

        double var = 0.0;
        for (double x : ratios) {
            double d = x - avgR;
            var += d * d;
        }
        double stdR = Math.sqrt(var / (n - 1));
        double absAvg = Math.abs(avgR) < 1e-12 ? 1e-12 : Math.abs(avgR);
        double cv = stdR / absAvg;

        boolean linear = cv < CV_LINEAR_THRESHOLD;
        double b1 = SensorCalibrationStore.LUX_BAND_LOW_MAX;
        double b2 = SensorCalibrationStore.LUX_BAND_MID_MAX;

        if (linear) {
            return new Result(true, cv, avgR, stdR, n, totalSampleCount, flaggedOutlierCount, outliersRemoved,
                    avgR, avgR, avgR, avgR, b1, b2);
        }

        List<Row> bucketLow = new ArrayList<>();
        List<Row> bucketMid = new ArrayList<>();
        List<Row> bucketHigh = new ArrayList<>();
        for (Row r : valid) {
            if (!Double.isFinite(r.lux)) {
                continue;
            }
            if (r.lux < b1) {
                bucketLow.add(r);
            } else if (r.lux < b2) {
                bucketMid.add(r);
            } else {
                bucketHigh.add(r);
            }
        }

        double sL = meanRatioRows(bucketLow, avgR);
        double sM = meanRatioRows(bucketMid, avgR);
        double sH = meanRatioRows(bucketHigh, avgR);

        return new Result(false, cv, avgR, stdR, n, totalSampleCount, flaggedOutlierCount, outliersRemoved,
                avgR, sL, sM, sH, b1, b2);
    }

    private static double meanRatioRows(@NonNull List<Row> rows, double fallback) {
        double sum = 0.0;
        int c = 0;
        for (Row r : rows) {
            if (!Double.isFinite(r.meterCdM2) || !Double.isFinite(r.appComputedCdM2)
                    || r.appComputedCdM2 <= 1e-9 || r.meterCdM2 <= 0.0) {
                continue;
            }
            sum += r.meterCdM2 / r.appComputedCdM2;
            c++;
        }
        return c > 0 ? sum / c : fallback;
    }

    @NonNull
    public static String formatResultForDisplay(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("公式: App计算亮度(cd/m²) = 传感器lux × 0.18 / π\n");
        sb.append(String.format(Locale.US, "（π≈3.14159，0.18/π≈%.5f）\n\n", LUX_TO_APP_L_FACTOR));
        sb.append(String.format(Locale.getDefault(), "使用的样本数: %d\n", r.totalSampleCount));
        sb.append(String.format(Locale.getDefault(), "2σ 异常点: %d 个\n", r.flaggedOutlierCount));
        sb.append(String.format(Locale.getDefault(), "异常点处理: %s\n",
                r.outliersRemoved ? "已剔除后再计算" : "未剔除（使用全部）"));
        sb.append(String.format(Locale.getDefault(), "有效样本数（拟合）: %d\n", r.effectiveSampleCount));
        sb.append(String.format(Locale.US, "比值均值 avg_r: %.4f\n比值标准差 std_r: %.4f\n变异系数 CV=std_r/|avg_r|: %.4f（阈值 %.2f）\n\n",
                r.ratioMean, r.ratioStd, r.ratioCv, CV_LINEAR_THRESHOLD));

        if (r.linear) {
            sb.append(String.format(Locale.US, "检测到线性误差，推荐 scale = %.2f\n\n", r.linearScale));
            sb.append(String.format(Locale.US, "推荐校准模式: linear\n修正: L = (lux×0.18/π) × %.4f",
                    r.linearScale));
        } else {
            sb.append(String.format(Locale.US,
                    "检测到非线性误差，推荐分段校准：低光 scale=%.2f，中光 scale=%.2f，高光 scale=%.2f\n\n",
                    r.scaleLow, r.scaleMid, r.scaleHigh));
            sb.append(String.format(Locale.US,
                    "推荐校准模式: 分段线性（传感器 lux：低＜%.0f，%.0f≤中＜%.0f，高≥%.0f）\n"
                            + "修正: 各段 lux_校准后 = lux_原始 × 对应 scale；再 L = lux_校准后×0.18/π",
                    r.thr1, r.thr1, r.thr2, r.thr2));
        }
        return sb.toString();
    }
}
