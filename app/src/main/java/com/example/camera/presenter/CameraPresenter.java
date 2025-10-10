package com.example.camera.presenter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Range;

import com.example.camera.R;
import com.example.camera.contract.CameraContract;
import com.example.camera.model.AppState;
import com.example.camera.model.CameraModel;
import com.example.camera.model.CameraSettings;

/**
 * 业务逻辑层Presenter实现
 * 负责处理UI交互和相机控制逻辑
 */
public class CameraPresenter implements CameraContract.Presenter {
    private static final String TAG = "CameraPresenter";
    
    private CameraContract.View view;
    private CameraContract.Model model;
    private Context context;
    private CameraManager cameraManager;
    
    // 相机相关
    private String cameraId = "0";
    private CameraDevice cameraDevice;
    
    public CameraPresenter(CameraContract.View view, Context context) {
        this.view = view;
        this.context = context;
        this.model = new CameraModel(context);
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }
    
    @Override
    public void onViewCreated() {
        Log.d(TAG, "View created, initializing UI state");
        AppState appState = model.getAppState();
        view.updateCameraStatus(appState.getCameraStatus());
        view.updatePhotoCount(appState.getPhotoCount());
        view.updateBrightnessMode(appState.getBrightnessMode().getDisplayName());
        view.updateExposureValue("E: --");
    }
    
    @Override
    public void onResume() {
        Log.d(TAG, "Presenter resumed");
        // 在Activity onResume时由View调用初始化相机
    }
    
    @Override
    public void onPause() {
        Log.d(TAG, "Presenter paused");
        closeCamera();
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "Presenter destroyed");
        closeCamera();
    }
    
    @Override
    public void initializeCamera() {
        Log.d(TAG, "Initializing camera");
        view.showLoading(true);
        view.updateCameraStatus("正在启动相机...");
        
        try {
            // 获取相机特性
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            
            // 设置相机设置
            CameraSettings settings = model.getCameraSettings();
            
            // 获取ISO和曝光时间范围
            Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            
            if (isoRange != null) {
                settings.setIsoRange(isoRange);
                settings.setIso(isoRange.getLower());
            }
            
            if (exposureRange != null) {
                settings.setExposureRange(exposureRange);
                settings.setExposureTime(exposureRange.getLower());
            }
            
            // 获取光圈值
            float[] availableApertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
            if (availableApertures != null && availableApertures.length > 0) {
                settings.setAperture(availableApertures[0]);
            }
            
            // 获取传感器方向
            Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensorOrientation != null) {
                settings.setSensorOrientation(sensorOrientation);
            }
            
            model.updateCameraSettings(settings);
            
            Log.d(TAG, "Camera characteristics loaded: " + settings.toString());
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to get camera characteristics", e);
            view.showError("获取相机参数失败");
            view.showLoading(false);
        }
    }
    
    @Override
    public void startPreview() {
        Log.d(TAG, "Starting camera preview");
        // 预览启动逻辑将在View层的相机回调中处理
        // 这里主要更新UI状态
        AppState appState = model.getAppState();
        appState.setLoading(false);
        appState.setCameraStatus("相机就绪");
        model.updateAppState(appState);
        
        view.showLoading(false);
        view.updateCameraStatus(appState.getCameraStatus());
        view.showToast("相机已就绪");
        
        // 初始化参数显示
        updateParameterDisplays();
    }
    
    @Override
    public void takePicture() {
        Log.d(TAG, "Taking picture");
        // 拍照逻辑主要在View层处理，这里更新状态
        AppState appState = model.getAppState();
        appState.incrementPhotoCount();
        model.updateAppState(appState);
        
        view.updatePhotoCount(appState.getPhotoCount());
    }
    
    @Override
    public void closeCamera() {
        Log.d(TAG, "Closing camera");
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        
        AppState appState = model.getAppState();
        appState.setCameraStatus("相机已关闭");
        appState.setLoading(false);
        model.updateAppState(appState);
        
        view.updateCameraStatus(appState.getCameraStatus());
        view.showLoading(false);
    }
    
    @Override
    public void onIsoChanged(int progress) {
        CameraSettings settings = model.getCameraSettings();
        Range<Integer> isoRange = settings.getIsoRange();
        
        if (isoRange != null) {
            int iso = isoRange.getLower() + (int)((isoRange.getUpper() - isoRange.getLower()) * (progress / 100f));
            settings.setIso(iso);
            model.updateCameraSettings(settings);
            
            // 更新UI显示
            view.updateIsoDisplay(iso);
            view.setSeekBarProgress(R.id.seekBarBrightness, 0);
            
            // 实际应用到相机硬件
            view.applyCameraIsoParameter(iso);
            
            // 更新亮度模式
            AppState appState = model.getAppState();
            appState.setBrightnessMode(AppState.BrightnessMode.MANUAL);
            model.updateAppState(appState);
            view.updateBrightnessMode(appState.getBrightnessMode().getDisplayName());
            
            // 更新曝光量显示
            updateExposureValueDisplay();
            
            Log.d(TAG, "ISO changed to: " + iso + ", applied to camera");
        }
    }
    
    @Override
    public void onExposureChanged(int progress) {
        CameraSettings settings = model.getCameraSettings();
        Range<Long> exposureRange = settings.getExposureRange();
        
        if (exposureRange != null) {
            long exposure = exposureRange.getLower() + (long)((exposureRange.getUpper() - exposureRange.getLower()) * (progress / 100f));
            settings.setExposureTime(exposure);
            model.updateCameraSettings(settings);
            
            // 更新UI显示
            view.updateExposureDisplay(settings.getFormattedExposureTime());
            view.setSeekBarProgress(R.id.seekBarBrightness, 0);
            
            // 实际应用到相机硬件
            view.applyCameraExposureParameter(exposure);
            
            // 更新亮度模式
            AppState appState = model.getAppState();
            appState.setBrightnessMode(AppState.BrightnessMode.MANUAL);
            model.updateAppState(appState);
            view.updateBrightnessMode(appState.getBrightnessMode().getDisplayName());
            
            // 更新曝光量显示
            updateExposureValueDisplay();
            
            Log.d(TAG, "Exposure changed to: " + exposure + " ns, applied to camera");
        }
    }
    
    @Override
    public void onBrightnessChanged(int progress) {
        CameraSettings settings = model.getCameraSettings();
        Range<Integer> isoRange = settings.getIsoRange();
        Range<Long> exposureRange = settings.getExposureRange();
        
        if (isoRange != null && exposureRange != null) {
            // 同时调整ISO和曝光时间
            int iso = isoRange.getLower() + (int)((isoRange.getUpper() - isoRange.getLower()) * (progress / 100f));
            long exposure = exposureRange.getLower() + (long)((exposureRange.getUpper() - exposureRange.getLower()) * (progress / 100f));
            
            settings.setIso(iso);
            settings.setExposureTime(exposure);
            model.updateCameraSettings(settings);
            
            // 重置其他SeekBar
            view.setSeekBarProgress(R.id.seekBarIso, 0);
            view.setSeekBarProgress(R.id.seekBarExposure, 0);
            
            // 更新UI显示
            view.updateIsoDisplay(iso);
            view.updateExposureDisplay(settings.getFormattedExposureTime());
            
            // 实际应用到相机硬件
            view.applyCameraIsoParameter(iso);
            view.applyCameraExposureParameter(exposure);
            
            // 更新亮度模式
            AppState appState = model.getAppState();
            appState.setBrightnessMode(progress == 0 ? AppState.BrightnessMode.AUTO : AppState.BrightnessMode.MANUAL);
            model.updateAppState(appState);
            view.updateBrightnessMode(appState.getBrightnessMode().getDisplayName());
            
            // 更新曝光量显示
            updateExposureValueDisplay();
            
            Log.d(TAG, "Brightness adjusted - ISO: " + iso + ", Exposure: " + exposure + ", applied to camera");
        }
    }
    
    @Override
    public void onPermissionGranted() {
        Log.d(TAG, "Camera permission granted");
        initializeCamera();
    }
    
    @Override
    public void onPermissionDenied() {
        Log.d(TAG, "Camera permission denied");
        view.showError("相机权限被拒绝，无法使用相机功能");
        
        AppState appState = model.getAppState();
        appState.setCameraStatus("权限被拒绝");
        model.updateAppState(appState);
        view.updateCameraStatus(appState.getCameraStatus());
    }
    
    @Override
    public void onCameraOpened() {
        Log.d(TAG, "Camera opened successfully");
        startPreview();
    }
    
    @Override
    public void onCameraDisconnected() {
        Log.d(TAG, "Camera disconnected");
        view.showError("相机连接已断开");
        
        AppState appState = model.getAppState();
        appState.setCameraStatus("相机已断开");
        model.updateAppState(appState);
        view.updateCameraStatus(appState.getCameraStatus());
    }
    
    @Override
    public void onCameraError(int error) {
        Log.e(TAG, "Camera error: " + error);
        String errorMsg = getCameraErrorMessage(error);
        view.showError(errorMsg);
        
        AppState appState = model.getAppState();
        appState.setCameraStatus("相机错误");
        appState.setLastError(errorMsg);
        model.updateAppState(appState);
        view.updateCameraStatus(appState.getCameraStatus());
    }
    
    @Override
    public void onImageCaptured(byte[] imageData) {
        Log.d(TAG, "Image captured, processing...");
        
        // 保存原始图像
        String originalFileName = System.currentTimeMillis() + "_original.jpg";
        boolean saved = model.saveImage(imageData, originalFileName);
        
        if (saved) {
            // 生成伪彩色图像
            Bitmap originalBitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
            if (originalBitmap != null) {
                // 读取EXIF数据（这里简化处理）
                String exifBrightness = "N/A";
                
                Bitmap pseudoColorBitmap = model.createPseudoColorImage(originalBitmap, exifBrightness);
                if (pseudoColorBitmap != null) {
                    view.displayCapturedImage(pseudoColorBitmap);
                    
                    // 保存伪彩色图像
                    String pseudoFileName = System.currentTimeMillis() + "_pseudo.jpg";
                    model.saveImage(pseudoColorBitmap, pseudoFileName);
                    
                    view.showToast("图像处理完成并已保存");
                }
            }
            
            // 更新拍照计数
            takePicture();
        } else {
            view.showError("图像保存失败");
        }
    }
    
    /**
     * 更新参数显示
     */
    private void updateParameterDisplays() {
        CameraSettings settings = model.getCameraSettings();
        AppState appState = model.getAppState();
        
        view.updateIsoDisplay(settings.getIso());
        view.updateExposureDisplay(settings.getFormattedExposureTime());
        view.updateBrightnessMode(appState.getBrightnessMode().getDisplayName());
        updateExposureValueDisplay();
    }
    
    /**
     * 更新曝光量显示
     */
    private void updateExposureValueDisplay() {
        CameraSettings settings = model.getCameraSettings();
        double exposureValue = settings.calculateExposureValue();
        view.updateExposureValue(String.format("E: %.2f", exposureValue));
    }
    
    /**
     * 获取相机错误信息
     */
    private String getCameraErrorMessage(int error) {
        switch (error) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                return "相机正被其他应用使用";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return "已达到最大相机使用数量";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                return "相机已被禁用";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                return "相机设备发生错误";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                return "相机服务发生错误";
            default:
                return "未知相机错误";
        }
    }
}