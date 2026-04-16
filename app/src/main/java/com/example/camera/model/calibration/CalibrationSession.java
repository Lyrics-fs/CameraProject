package com.example.camera.model.calibration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CalibrationSession {
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

    public void clear() {
        samples.clear();
        status = Status.IDLE;
        failReason = "";
        fittedParams = null;
    }

    public void addSample(CalibrationSample sample) {
        samples.add(sample);
    }

    public List<CalibrationSample> getSamples() {
        return Collections.unmodifiableList(samples);
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
