package com.example.camera.model.calibration;

public class CurveParams {
    public static final double PRIOR_A = 2.9;
    public static final double PRIOR_B = 0.729;

    public enum Source {
        PRIOR,
        CALIBRATED
    }

    public final double a;
    public final double b;
    public final Source source;
    public final long updatedAt;

    public CurveParams(double a, double b, Source source, long updatedAt) {
        this.a = a;
        this.b = b;
        this.source = source;
        this.updatedAt = updatedAt;
    }

    public static CurveParams prior() {
        return new CurveParams(PRIOR_A, PRIOR_B, Source.PRIOR, 0L);
    }

    public boolean isValid() {
        return a > 0.0 && Double.isFinite(a) && Double.isFinite(b);
    }
}
