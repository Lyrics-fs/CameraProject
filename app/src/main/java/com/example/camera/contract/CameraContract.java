package com.example.camera.contract;

import android.graphics.Bitmap;
import com.example.camera.model.CameraSettings;
import com.example.camera.model.AppState;

/**
 * MVP模式的契约接口
 * 定义View和Presenter之间的交互接口
 */
public interface CameraContract {
    
    /**
     * View接口 - 定义UI更新方法
     */
    interface View {
        // UI状态更新
        void showLoading(boolean show);
        void updateCameraStatus(String status);
        void updatePhotoCount(int count);
        void showError(String error);
        void showToast(String message);
        
        // 相机参数显示更新
        void updateIsoDisplay(int iso);
        void updateExposureDisplay(String exposureText);
        void updateBrightnessMode(String mode);
        void updateExposureValue(String exposureValue);
        
        // SeekBar控制
        void resetSeekBars();
        void setSeekBarProgress(int seekBarId, int progress);
        
        // 图像显示
        void displayCapturedImage(Bitmap bitmap);
        
        // 相机参数实际更新（直接操作相机硬件）
        void applyCameraIsoParameter(int iso);
        void applyCameraExposureParameter(long exposure);
        
        // 获取当前进度值
        int getIsoProgress();
        int getExposureProgress();
        int getBrightnessProgress();
    }
    
    /**
     * Presenter接口 - 定义业务逻辑方法
     */
    interface Presenter {
        // 生命周期方法
        void onViewCreated();
        void onResume();
        void onPause();
        void onDestroy();
        
        // 相机操作
        void initializeCamera();
        void startPreview();
        void takePicture();
        void closeCamera();
        
        // 参数控制
        void onIsoChanged(int progress);
        void onExposureChanged(int progress);
        void onBrightnessChanged(int progress);
        
        // 权限处理
        void onPermissionGranted();
        void onPermissionDenied();
        
        // 相机状态回调
        void onCameraOpened();
        void onCameraDisconnected();
        void onCameraError(int error);
        
        // 图像处理
        void onImageCaptured(byte[] imageData);
    }
    
    /**
     * Model接口 - 定义数据操作方法
     */
    interface Model {
        // 相机设置
        CameraSettings getCameraSettings();
        void updateCameraSettings(CameraSettings settings);
        
        // 应用状态
        AppState getAppState();
        void updateAppState(AppState state);
        
        // 数据计算
        double calculateExposureValue(int iso, long exposureTime, float aperture);
        String formatExposureTime(long exposureTimeNs);
        
        // 图像处理
        Bitmap createPseudoColorImage(Bitmap originalBitmap, String exifBrightness);
        
        // 文件操作
        boolean saveImage(byte[] imageData, String fileName);
        boolean saveImage(Bitmap bitmap, String fileName);
    }
}