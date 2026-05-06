package com.example.camera.contract;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import com.example.camera.model.CameraSettings;
import com.example.camera.model.AppState;
import com.example.camera.model.CameraModel;
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

        /** 较长提示（如上传失败原因）；默认与 [showToast] 相同，实现类可改为较长显示时间。 */
        default void showToastLong(String message) {
            showToast(message);
        }
        
        // 相机参数显示更新
        void updateIsoDisplay(int iso);
        void updateExposureDisplay(String exposureText);
        void updateBrightnessMode(String mode);
        void updateExposureValue(String exposureValue);
        /**
         * 预览中心 ROI 对应的亮度 L（cd/m²）；无效时为 NaN。
         * {@code centerMeanDn} 用于可选的色调映射（与测光 DN 一致）。
         * {@code luminanceSourceTag}：与 {@link com.example.camera.presenter.CameraPresenter#computeLuminance(double)}
         * 分支一致时为「查表」「标定」「先验」之一；无效 L 时为 {@code null}。
         */
        void updateCenterLuminance(double lCdPerM2, double centerMeanDn, @Nullable String luminanceSourceTag);
        void onExposureRecommendationChanged(CameraModel.ExposureRecommendation recommendation);
        void requestAutoApplyRecommendation();
        void updateCalibrationStatus(String text);
        void updateCurveSource(String source);
        /** 预览角标：曲线来源与置信度 / R²。 */
        void updateCurveSourceBadge(String badgeText);

        /** Debevec {@code g(DN)} 已从曝光序列写入本地后调用，用于刷新绝对亮度校准等 UI。 */
        void onDebevecGSaved();

        /** Level1 查表云端上传成功/失败后，刷新「已上传」与重传按钮等 UI。 */
        void onLevel1LookupUploadStateChanged();

        /** 已废弃：原 BV 指数曲线标定质量分支（应用已改为 Debevec + 灰卡标定）。 */
        void onCalibrationLowQualityComplete();
        void onCalibrationAbnormalComplete();
        void onCalibrationHighMediumComplete(String qualityTier);
        
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