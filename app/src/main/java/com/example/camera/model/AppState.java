package com.example.camera.model;

/**
 * 应用状态数据模型
 * 记录应用运行时的状态信息
 */
public class AppState {
    private String cameraStatus;        // 相机状态
    private int photoCount;             // 拍照计数
    private boolean isLoading;          // 是否正在加载
    private String lastError;           // 最后一次错误信息
    private BrightnessMode brightnessMode; // 亮度模式
    
    public enum BrightnessMode {
        AUTO("自动"),
        MANUAL("手动");
        
        private final String displayName;
        
        BrightnessMode(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    public AppState() {
        this.cameraStatus = "初始化中...";
        this.photoCount = 0;
        this.isLoading = false;
        this.brightnessMode = BrightnessMode.AUTO;
    }
    
    // Getter和Setter方法
    public String getCameraStatus() {
        return cameraStatus;
    }
    
    public void setCameraStatus(String cameraStatus) {
        this.cameraStatus = cameraStatus;
    }
    
    public int getPhotoCount() {
        return photoCount;
    }
    
    public void setPhotoCount(int photoCount) {
        this.photoCount = photoCount;
    }
    
    public void incrementPhotoCount() {
        this.photoCount++;
    }
    
    public boolean isLoading() {
        return isLoading;
    }
    
    public void setLoading(boolean loading) {
        isLoading = loading;
    }
    
    public String getLastError() {
        return lastError;
    }
    
    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
    
    public BrightnessMode getBrightnessMode() {
        return brightnessMode;
    }
    
    public void setBrightnessMode(BrightnessMode brightnessMode) {
        this.brightnessMode = brightnessMode;
    }
    
    /**
     * 获取格式化的拍照计数字符串
     */
    public String getFormattedPhotoCount() {
        return String.format("已拍摄: %d 张", photoCount);
    }
    
    @Override
    public String toString() {
        return "AppState{" +
                "cameraStatus='" + cameraStatus + '\'' +
                ", photoCount=" + photoCount +
                ", isLoading=" + isLoading +
                ", brightnessMode=" + brightnessMode +
                '}';
    }
}