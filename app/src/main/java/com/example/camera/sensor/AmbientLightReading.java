package com.example.camera.sensor;

import androidx.annotation.NonNull;

/**
 * 一次环境光读数快照，供 {@link LightSensorManager#getAmbientLightLiveData()} 推送。
 */
public final class AmbientLightReading {

    /** 传感器原始 lux（未做 lux 校准）。 */
    public final float rawLux;
    /**
     * 经 {@link SensorCalibrationStore#applyToLux(float)} 后的 lux；
     * 关闭校准时与 {@link #rawLux} 相同。
     */
    public final float lux;
    public final double luminanceCdM2;
    /** {@link android.os.SystemClock#elapsedRealtime()}，便于调试与节流对齐。 */
    public final long elapsedRealtimeMs;

    public AmbientLightReading(float rawLux, float lux, double luminanceCdM2, long elapsedRealtimeMs) {
        this.rawLux = rawLux;
        this.lux = lux;
        this.luminanceCdM2 = luminanceCdM2;
        this.elapsedRealtimeMs = elapsedRealtimeMs;
    }

    @NonNull
    @Override
    public String toString() {
        return "AmbientLightReading{rawLux=" + rawLux + ", lux=" + lux + ", cd/m2=" + luminanceCdM2 + "}";
    }
}
