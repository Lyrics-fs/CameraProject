package com.example.camera.presenter.state;

public class AutoTuneState {
    public boolean enabled;
    public long lastAutoApplyTs;
    public int lastDirection;
    public int stableFrameCount;
    public int requiredStableFrameCount;
    public double stepRatio;
    public double bvDeltaThreshold;

    public AutoTuneState(int requiredStableFrameCount, double stepRatio, double bvDeltaThreshold) {
        this.enabled = false;
        this.lastAutoApplyTs = 0L;
        this.lastDirection = 0;
        this.stableFrameCount = 0;
        this.requiredStableFrameCount = requiredStableFrameCount;
        this.stepRatio = stepRatio;
        this.bvDeltaThreshold = bvDeltaThreshold;
    }
}
