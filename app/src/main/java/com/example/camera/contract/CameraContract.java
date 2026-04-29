package com.example.camera.contract;

import android.graphics.Bitmap;
import com.example.camera.model.CameraSettings;
import com.example.camera.model.AppState;
import com.example.camera.model.CameraModel;
import com.example.camera.model.calibration.CurveParams;

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
        /**
         * 预览中心 ROI 对应的亮度 L（cd/m²）；无效时为 NaN。
         * {@code centerMeanDn} 用于可选的色调映射（与测光 DN 一致）。
         */
        void updateCenterLuminance(double lCdPerM2, double centerMeanDn);
        void onExposureRecommendationChanged(CameraModel.ExposureRecommendation recommendation);
        void requestAutoApplyRecommendation();
        void updateCalibrationStatus(String text);
        void updateCurveSource(String source);
        /** 预览角标：曲线来源与置信度 / R²。 */
        void updateCurveSourceBadge(String badgeText);

        /**
         * 步骤 5.5：统计质量不足（R² 或采样数未达标），仅 Toast + 本地留存 LOW，不进入分享流程。
         */
        void onCalibrationLowQualityComplete(CurveParams fitted);
        /**
         * 步骤 5.5：拟合参数异常（如 a≤0 或 b 超出合理区间），仅 Toast + 本地留存 ABNORMAL，不进入分享流程。
         */
        void onCalibrationAbnormalComplete(CurveParams fitted);
        /**
         * 标定质量为 HIGH/MEDIUM：由界面层结合 DataStore 决定是否弹出分享授权或静默保存。
         */
        void onCalibrationHighMediumComplete(CurveParams fitted, String qualityTier);
        
        // SeekBar控制
        void resetSeekBars();
        void setSeekBarProgress(int seekBarId, int progress);
        
        // 图像显示
        void displayCapturedImage(Bitmap bitmap);
        
        // 相机参数实际更新（直接操作相机硬件）
        void applyCameraIsoParameter(int iso);
        void applyCameraExposureParameter(long exposure);
        void setPreviewBrightness(float gain);
        /** 与离线伪彩一致：boosted luma 归一化后的全局 min/max，用于预览 Hue 拉伸 */
        void setPreviewPseudoHueRange(float minBoostedN, float maxBoostedN);
        
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

        // 标定流程
        void startCalibration();
        void captureCalibrationSample();
        void finishCalibration();
        boolean isCalibrationModeActive();
        /** 标定模式下 lux 窗口是否稳定（方差在阈值内）；非标定模式下视为 true。 */
        boolean isAmbientLuxStable();
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