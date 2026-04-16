package com.example.camera.model.calibration;

public class CalibrationSample {
    public final double bv;
    public final double referenceL;
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
