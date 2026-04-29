package com.example.camera.model.calibration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CalibrationFitter {
    private CalibrationFitter() {}

    /** 相对误差 |L−L̂|/L 超过此值视为疑似异常点。 */
    private static final double OUTLIER_RELATIVE_ERROR_THRESHOLD = 0.30;
    /** 剔除该点后 R² 至少提升此绝对值才认为“显著提升”并采纳剔除。 */
    private static final double OUTLIER_R2_IMPROVEMENT_MIN = 0.03;
    private static final int OUTLIER_REFIT_MAX_ITERATIONS = 16;

    /**
     * 拟合结果：曲线参数 + 参与最终拟合的样本子集（与 {@link CalibrationSession} 中为同一对象引用，便于 retain）。
     */
    public static final class FitResult {
        private final CurveParams curveParams;
        private final List<CalibrationSample> samplesUsed;

        public FitResult(CurveParams curveParams, List<CalibrationSample> samplesUsed) {
            this.curveParams = curveParams;
            this.samplesUsed = Collections.unmodifiableList(new ArrayList<>(samplesUsed));
        }

        public CurveParams getCurveParams() {
            return curveParams;
        }

        /** 剔除异常点后用于拟合的样本列表（顺序可能与原列表不同）。 */
        public List<CalibrationSample> getSamplesUsed() {
            return samplesUsed;
        }
    }

    private static List<CalibrationSample> selectValidSamples(List<CalibrationSample> samples) {
        List<CalibrationSample> valid = new ArrayList<>();
        if (samples == null) {
            return valid;
        }
        for (CalibrationSample sample : samples) {
            if (sample.valid && sample.referenceL > 0.0 && Double.isFinite(sample.bv)) {
                valid.add(sample);
            }
        }
        return valid;
    }

    /**
     * 对 L_actual = referenceL 与 L_predicted = a·e^(b·BV) 计算决定系数 R²。
     * <p>
     * 与 {@link #fitExpCurve} 使用相同的有效样本准则。有效点 &lt; 2 或 SS_tot = 0 时返回 0。
     */
    public static double calculateRSquared(List<CalibrationSample> samples, double a, double b) {
        List<CalibrationSample> valid = selectValidSamples(samples);
        if (valid.size() < 2) {
            return 0.0;
        }
        double sumL = 0.0;
        for (CalibrationSample s : valid) {
            sumL += s.referenceL;
        }
        double meanL = sumL / valid.size();
        double ssTot = 0.0;
        for (CalibrationSample s : valid) {
            double d = s.referenceL - meanL;
            ssTot += d * d;
        }
        if (ssTot <= 0.0 || !Double.isFinite(ssTot)) {
            return 0.0;
        }
        double ssRes = 0.0;
        for (CalibrationSample s : valid) {
            double pred = a * Math.exp(b * s.bv);
            if (!Double.isFinite(pred)) {
                return 0.0;
            }
            double e = s.referenceL - pred;
            ssRes += e * e;
        }
        if (!Double.isFinite(ssRes)) {
            return 0.0;
        }
        double r2 = 1.0 - (ssRes / ssTot);
        return Double.isFinite(r2) ? r2 : 0.0;
    }

    /**
     * 单轮最小二乘指数拟合（无异常值剔除）。样本需已满足数量与 BV 跨度。
     */
    private static CurveParams fitLeastSquaresExpOrNull(
            List<CalibrationSample> working,
            int minSamples,
            double minBvSpread
    ) {
        if (working.size() < minSamples) {
            return null;
        }
        double minBv = Double.POSITIVE_INFINITY;
        double maxBv = Double.NEGATIVE_INFINITY;
        for (CalibrationSample s : working) {
            minBv = Math.min(minBv, s.bv);
            maxBv = Math.max(maxBv, s.bv);
        }
        if (maxBv - minBv < minBvSpread) {
            return null;
        }

        double sumX = 0.0;
        double sumY = 0.0;
        for (CalibrationSample s : working) {
            sumX += s.bv;
            sumY += Math.log(s.referenceL);
        }
        double meanX = sumX / working.size();
        double meanY = sumY / working.size();

        double numerator = 0.0;
        double denominator = 0.0;
        for (CalibrationSample s : working) {
            double x = s.bv - meanX;
            double y = Math.log(s.referenceL) - meanY;
            numerator += x * y;
            denominator += x * x;
        }
        if (denominator < 1e-8) {
            return null;
        }

        double b = numerator / denominator;
        double intercept = meanY - b * meanX;
        double a = Math.exp(intercept);
        if (!(a > 0.0) || !Double.isFinite(a) || !Double.isFinite(b) || Math.abs(b) > 5.0) {
            return null;
        }
        double r2 = calculateRSquared(working, a, b);
        return new CurveParams(a, b, CurveParams.Source.CALIBRATED, System.currentTimeMillis(), r2);
    }

    private static double relativeError(CalibrationSample s, double a, double b) {
        double pred = a * Math.exp(b * s.bv);
        if (!Double.isFinite(pred) || s.referenceL <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.abs(s.referenceL - pred) / s.referenceL;
    }

    /**
     * 指数拟合 + 相对误差异常值剔除；返回参数与最终参与拟合的样本子集。
     */
    public static FitResult fitExpCurveResult(
            List<CalibrationSample> samples,
            int minSamples,
            double minBvSpread
    ) {
        List<CalibrationSample> valid = selectValidSamples(samples);
        if (valid.size() < minSamples) {
            return null;
        }

        List<CalibrationSample> working = new ArrayList<>(valid);
        final int originalCount = working.size();

        for (int iter = 0; iter < OUTLIER_REFIT_MAX_ITERATIONS; iter++) {
            CurveParams params = fitLeastSquaresExpOrNull(working, minSamples, minBvSpread);
            if (params == null) {
                return null;
            }
            double r2 = params.getRSquared();

            List<Integer> suspects = new ArrayList<>();
            for (int i = 0; i < working.size(); i++) {
                if (relativeError(working.get(i), params.a, params.b) > OUTLIER_RELATIVE_ERROR_THRESHOLD) {
                    suspects.add(i);
                }
            }
            if (suspects.isEmpty()) {
                params.setCalibrationOutliersRemoved(originalCount - working.size());
                params.setFitSampleCount(working.size());
                return new FitResult(params, working);
            }

            int bestRemove = -1;
            double bestNewR2 = r2;
            for (int idx : suspects) {
                List<CalibrationSample> trial = new ArrayList<>(working);
                trial.remove(idx);
                CurveParams trialFit = fitLeastSquaresExpOrNull(trial, minSamples, minBvSpread);
                if (trialFit == null) {
                    continue;
                }
                double trialR2 = trialFit.getRSquared();
                if (trialR2 - r2 >= OUTLIER_R2_IMPROVEMENT_MIN && trialR2 > bestNewR2) {
                    bestNewR2 = trialR2;
                    bestRemove = idx;
                }
            }

            if (bestRemove < 0) {
                params.setCalibrationOutliersRemoved(originalCount - working.size());
                params.setFitSampleCount(working.size());
                return new FitResult(params, working);
            }

            working.remove(bestRemove);
        }

        CurveParams last = fitLeastSquaresExpOrNull(working, minSamples, minBvSpread);
        if (last == null) {
            return null;
        }
        last.setCalibrationOutliersRemoved(originalCount - working.size());
        last.setFitSampleCount(working.size());
        return new FitResult(last, working);
    }

    /**
     * 指数曲线拟合（含异常值剔除）；仅返回曲线参数，等价于 {@link #fitExpCurveResult} 的曲线部分。
     */
    public static CurveParams fitExpCurve(List<CalibrationSample> samples, int minSamples, double minBvSpread) {
        FitResult fr = fitExpCurveResult(samples, minSamples, minBvSpread);
        return fr != null ? fr.getCurveParams() : null;
    }
}
