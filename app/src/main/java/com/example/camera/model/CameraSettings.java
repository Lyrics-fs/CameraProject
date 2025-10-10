package com.example.camera.model;

import android.util.Range;

/**
 * 相机设置数据模型
 * 包含相机的各种参数设置
 */
public class CameraSettings {
    private int iso;                        // ISO感光度
    private long exposureTime;              // 曝光时间（纳秒）
    private float aperture;                 // 光圈值
    private Range<Integer> isoRange;        // ISO范围
    private Range<Long> exposureRange;      // 曝光时间范围
    private int sensorOrientation;          // 传感器方向
    
    public CameraSettings() {
        this.aperture = 1.0f; // 默认光圈值
    }
    
    public CameraSettings(int iso, long exposureTime, float aperture) {
        this.iso = iso;
        this.exposureTime = exposureTime;
        this.aperture = aperture;
    }
    
    // Getter和Setter方法
    public int getIso() {
        return iso;
    }
    
    public void setIso(int iso) {
        this.iso = iso;
    }
    
    public long getExposureTime() {
        return exposureTime;
    }
    
    public void setExposureTime(long exposureTime) {
        this.exposureTime = exposureTime;
    }
    
    public float getAperture() {
        return aperture;
    }
    
    public void setAperture(float aperture) {
        this.aperture = aperture;
    }
    
    public Range<Integer> getIsoRange() {
        return isoRange;
    }
    
    public void setIsoRange(Range<Integer> isoRange) {
        this.isoRange = isoRange;
    }
    
    public Range<Long> getExposureRange() {
        return exposureRange;
    }
    
    public void setExposureRange(Range<Long> exposureRange) {
        this.exposureRange = exposureRange;
    }
    
    public int getSensorOrientation() {
        return sensorOrientation;
    }
    
    public void setSensorOrientation(int sensorOrientation) {
        this.sensorOrientation = sensorOrientation;
    }
    
    /**
     * 计算曝光量
     * E = (ISO * exposureTime) / (aperture^2)
     */
    public double calculateExposureValue() {
        double exposureSeconds = exposureTime / 1_000_000_000.0;
        return (iso * exposureSeconds) / (aperture * aperture);
    }
    
    /**
     * 格式化曝光时间显示
     */
    public String getFormattedExposureTime() {
        double exposureMs = exposureTime / 1_000_000.0;
        if (exposureMs < 1) {
            return String.format("%.2fμs", exposureMs * 1000);
        } else {
            return String.format("%.2fms", exposureMs);
        }
    }
    
    @Override
    public String toString() {
        return "CameraSettings{" +
                "iso=" + iso +
                ", exposureTime=" + exposureTime +
                ", aperture=" + aperture +
                ", exposureValue=" + calculateExposureValue() +
                '}';
    }
}