package com.example.camera.model.calibration;

import java.util.ArrayList;
import java.util.List;

public final class CalibrationFitter {
    private CalibrationFitter() {}

    public static CurveParams fitExpCurve(List<CalibrationSample> samples, int minSamples, double minBvSpread) {
        List<CalibrationSample> valid = new ArrayList<>();
        for (CalibrationSample sample : samples) {
            if (sample.valid && sample.referenceL > 0.0 && Double.isFinite(sample.bv)) {
                valid.add(sample);
            }
        }
        if (valid.size() < minSamples) return null;

        double minBv = Double.POSITIVE_INFINITY;
        double maxBv = Double.NEGATIVE_INFINITY;
        for (CalibrationSample s : valid) {
            minBv = Math.min(minBv, s.bv);
            maxBv = Math.max(maxBv, s.bv);
        }
        if (maxBv - minBv < minBvSpread) return null;

        double sumX = 0.0;
        double sumY = 0.0;
        for (CalibrationSample s : valid) {
            sumX += s.bv;
            sumY += Math.log(s.referenceL);
        }
        double meanX = sumX / valid.size();
        double meanY = sumY / valid.size();

        double numerator = 0.0;
        double denominator = 0.0;
        for (CalibrationSample s : valid) {
            double x = s.bv - meanX;
            double y = Math.log(s.referenceL) - meanY;
            numerator += x * y;
            denominator += x * x;
        }
        if (denominator < 1e-8) return null;

        double b = numerator / denominator;
        double intercept = meanY - b * meanX;
        double a = Math.exp(intercept);
        if (!(a > 0.0) || !Double.isFinite(a) || !Double.isFinite(b) || Math.abs(b) > 5.0) {
            return null;
        }
        return new CurveParams(a, b, CurveParams.Source.CALIBRATED, System.currentTimeMillis());
    }
}
