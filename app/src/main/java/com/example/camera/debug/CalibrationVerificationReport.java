package com.example.camera.debug;

import androidx.annotation.NonNull;

import com.example.camera.sensor.LightSensorManager;
import com.example.camera.sensor.SensorCalibrationStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Debug「校准效果验证」：按亮度计对比校准前后 App 亮度（cd/m²）的百分比误差统计。
 */
public final class CalibrationVerificationReport {

    public static final class SampleRow {
        public final double luxRaw;
        public final double meterCdM2;
        public final double lBefore;
        public final double lAfter;
        /** 绝对百分比误差 |L−meter|/meter×100 */
        public final double pctErrBefore;
        public final double pctErrAfter;

        SampleRow(double luxRaw, double meterCdM2, double lBefore, double lAfter,
                  double pctErrBefore, double pctErrAfter) {
            this.luxRaw = luxRaw;
            this.meterCdM2 = meterCdM2;
            this.lBefore = lBefore;
            this.lAfter = lAfter;
            this.pctErrBefore = pctErrBefore;
            this.pctErrAfter = pctErrAfter;
        }
    }

    public static final class Stats {
        public final double mape;
        public final double maxPct;
        public final double minPct;

        Stats(double mape, double maxPct, double minPct) {
            this.mape = mape;
            this.maxPct = maxPct;
            this.minPct = minPct;
        }
    }

    public static final class FullReport {
        @NonNull
        public final List<SampleRow> rows;
        @NonNull
        public final Stats before;
        @NonNull
        public final Stats after;
        /** 校准前 MAPE − 校准后 MAPE（百分点） */
        public final double mapeImprovementPoints;

        FullReport(@NonNull List<SampleRow> rows, @NonNull Stats before, @NonNull Stats after, double mapeImprovementPoints) {
            this.rows = rows;
            this.before = before;
            this.after = after;
            this.mapeImprovementPoints = mapeImprovementPoints;
        }
    }

    @NonNull
    public static List<SampleRow> buildRows(
            @NonNull List<VerificationSample> samples,
            @NonNull SensorCalibrationStore store
    ) {
        List<SampleRow> rows = new ArrayList<>(samples.size());
        for (VerificationSample s : samples) {
            double raw = s.luxRaw;
            double meter = s.meterCdM2;
            double lBefore = LightSensorManager.appDebugLuminanceFromLux(raw);
            float calLux = store.applyToLux((float) raw);
            double lAfter = LightSensorManager.appDebugLuminanceFromLux(calLux);
            double pb = absPctError(lBefore, meter);
            double pa = absPctError(lAfter, meter);
            rows.add(new SampleRow(raw, meter, lBefore, lAfter, pb, pa));
        }
        return rows;
    }

    public static double absPctError(double appL, double meter) {
        if (!Double.isFinite(appL) || !Double.isFinite(meter) || meter <= 1e-12) {
            return Double.NaN;
        }
        return Math.abs(appL - meter) / meter * 100.0;
    }

    @NonNull
    public static Stats statsFromPercentages(@NonNull List<Double> pcts) {
        int n = 0;
        double sum = 0.0;
        double max = Double.NEGATIVE_INFINITY;
        double min = Double.POSITIVE_INFINITY;
        for (double p : pcts) {
            if (!Double.isFinite(p)) {
                continue;
            }
            n++;
            sum += p;
            max = Math.max(max, p);
            min = Math.min(min, p);
        }
        if (n == 0) {
            return new Stats(Double.NaN, Double.NaN, Double.NaN);
        }
        return new Stats(sum / n, max, min);
    }

    @NonNull
    public static FullReport computeFull(@NonNull List<VerificationSample> samples, @NonNull SensorCalibrationStore store) {
        List<SampleRow> rows = buildRows(samples, store);
        List<Double> before = new ArrayList<>();
        List<Double> after = new ArrayList<>();
        for (SampleRow r : rows) {
            before.add(r.pctErrBefore);
            after.add(r.pctErrAfter);
        }
        Stats stBefore = statsFromPercentages(before);
        Stats stAfter = statsFromPercentages(after);
        double imp = (Double.isFinite(stBefore.mape) && Double.isFinite(stAfter.mape))
                ? (stBefore.mape - stAfter.mape)
                : Double.NaN;
        return new FullReport(rows, stBefore, stAfter, imp);
    }

    @NonNull
    public static String formatReportText(@NonNull FullReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("【校准前误差】\n");
        sb.append(String.format(Locale.US, "· 平均绝对百分比误差(MAPE) = %.2f%%\n", r.before.mape));
        sb.append(String.format(Locale.US, "· 最大误差 = %.2f%%\n", r.before.maxPct));
        sb.append(String.format(Locale.US, "· 最小误差 = %.2f%%\n\n", r.before.minPct));
        sb.append("【校准后误差】\n");
        sb.append(String.format(Locale.US, "· 平均绝对百分比误差(MAPE) = %.2f%%\n", r.after.mape));
        sb.append(String.format(Locale.US, "· 最大误差 = %.2f%%\n", r.after.maxPct));
        sb.append(String.format(Locale.US, "· 最小误差 = %.2f%%\n\n", r.after.minPct));
        sb.append("【改善情况】\n");
        sb.append(String.format(Locale.US, "· 误差降低 = 校准前MAPE − 校准后MAPE = %.2f%%\n", r.mapeImprovementPoints));
        sb.append("\n公式说明：\n");
        sb.append("· App亮度(校准前) = lux_raw × 0.18 / π\n");
        sb.append("· App亮度(校准后) = lux_校准后 × 0.18 / π（lux_校准后为当前已保存的校准策略输出）\n");
        sb.append("· 绝对百分比误差 = |App亮度 − 亮度计| / 亮度计 × 100%\n");
        return sb.toString();
    }

    /**
     * 简易文本对比条：刻度以本批「校准前/后误差」中的最大值为满宽。
     */
    @NonNull
    public static String formatAsciiComparisonChart(@NonNull FullReport r, int barWidth) {
        if (r.rows.isEmpty()) {
            return "";
        }
        double maxErr = 1.0;
        for (SampleRow row : r.rows) {
            maxErr = Math.max(maxErr, row.pctErrBefore);
            maxErr = Math.max(maxErr, row.pctErrAfter);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【误差对比（文本条）】\n");
        for (int i = 0; i < r.rows.size(); i++) {
            SampleRow row = r.rows.get(i);
            sb.append(String.format(Locale.US, "#%d  前%s  %.1f%%  后%s  %.1f%%\n",
                    i + 1,
                    barForPct(row.pctErrBefore, maxErr, barWidth),
                    row.pctErrBefore,
                    barForPct(row.pctErrAfter, maxErr, barWidth),
                    row.pctErrAfter));
        }
        return sb.toString();
    }

    @NonNull
    private static String barForPct(double pct, double maxScale, int width) {
        if (!Double.isFinite(pct) || maxScale <= 0 || width < 3) {
            return "[" + repeat('░', width) + "]";
        }
        int filled = (int) Math.round(Math.min(1.0, pct / maxScale) * width);
        filled = Math.max(0, Math.min(width, filled));
        return "[" + repeat('█', filled) + repeat('░', width - filled) + "]";
    }

    @NonNull
    private static String repeat(char c, int n) {
        if (n <= 0) {
            return "";
        }
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, c);
        return new String(arr);
    }

    @NonNull
    public static String buildCsv(@NonNull FullReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("index,lux_raw,meter_cd_m2,L_before,L_after,pct_err_before,pct_err_after\n");
        for (int i = 0; i < r.rows.size(); i++) {
            SampleRow row = r.rows.get(i);
            sb.append(String.format(Locale.US, "%d,%.6f,%.6f,%.6f,%.6f,%.4f,%.4f\n",
                    i + 1, row.luxRaw, row.meterCdM2, row.lBefore, row.lAfter,
                    row.pctErrBefore, row.pctErrAfter));
        }
        sb.append("\n");
        sb.append(String.format(Locale.US, "MAPE_before,%.4f\n", r.before.mape));
        sb.append(String.format(Locale.US, "MAPE_after,%.4f\n", r.after.mape));
        sb.append(String.format(Locale.US, "MAPE_improvement_pp,%.4f\n", r.mapeImprovementPoints));
        return sb.toString();
    }

    public static final class VerificationSample {
        public final double luxRaw;
        public final double meterCdM2;

        public VerificationSample(double luxRaw, double meterCdM2) {
            this.luxRaw = luxRaw;
            this.meterCdM2 = meterCdM2;
        }
    }
}
