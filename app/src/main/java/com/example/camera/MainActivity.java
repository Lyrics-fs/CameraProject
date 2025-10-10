package com.example.camera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.camera.contract.CameraContract;
import com.example.camera.presenter.CameraPresenter;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * 主Activity - 实现View接口
 * 负责UI展示和用户交互，业务逻辑委托给Presenter处理
 * 经过MVP架构重构，分离了业务逻辑和UI逻辑
 */
public class MainActivity extends AppCompatActivity implements CameraContract.View {
    private static final String TAG = "MainActivity";
    
    // UI组件
    private TextureView textureView;
    private Button captureButton;
    private ImageView imageView;
    private TextView tvBrightnessValue, tvExposureValue, tvIsoValue;
    private TextView tvBrightnessLabel, tvCameraStatus, tvPhotoCount;
    private SeekBar seekBarBrightness, seekBarIso, seekBarExposure;
    private ProgressBar progressBar;
    
    // MVP架构
    private CameraContract.Presenter presenter;
    
    // 相机相关变量（保留必要的Camera2 API操作）
    private String cameraId = "0";
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private ImageReader imageReader;
    private Size previewSize;
    private CameraManager cameraManager;
    private Range<Integer> isoRange;
    private Range<Long> exposureRange;
    private int sensorOrientation;
    private float aperture = 1.0f;
    
    // 屏幕旋转方向映射表
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        
        // 初始化MVP架构
        presenter = new CameraPresenter(this, this);
        presenter.onViewCreated();
        
        setupListeners();
    }
    
    private void initViews() {
        textureView = findViewById(R.id.tv);
        captureButton = findViewById(R.id.btn);
        imageView = findViewById(R.id.iv);
        tvBrightnessValue = findViewById(R.id.tv_display_value);
        tvExposureValue = findViewById(R.id.tv_exposure_value);
        tvIsoValue = findViewById(R.id.tv_iso_value);
        tvBrightnessLabel = findViewById(R.id.tv_brightness_value);
        tvCameraStatus = findViewById(R.id.tv_camera_status);
        tvPhotoCount = findViewById(R.id.tv_photo_count);
        progressBar = findViewById(R.id.progressBar);
        
        seekBarBrightness = findViewById(R.id.seekBarBrightness);
        seekBarIso = findViewById(R.id.seekBarIso);
        seekBarExposure = findViewById(R.id.seekBarExposure);
    }
    
    private void setupListeners() {
        seekBarIso.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && presenter != null) {
                    presenter.onIsoChanged(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        seekBarExposure.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && presenter != null) {
                    presenter.onExposureChanged(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        seekBarBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && presenter != null) {
                    presenter.onBrightnessChanged(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        captureButton.setOnClickListener(v -> takePicture());
        textureView.setSurfaceTextureListener(surfaceTextureListener);
    }

    // =============================================================================
    // View接口实现
    // =============================================================================
    
    @Override
    public void showLoading(boolean show) {
        runOnUiThread(() -> {
            if (progressBar != null) {
                progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });
    }
    
    @Override
    public void updateCameraStatus(String status) {
        runOnUiThread(() -> {
            if (tvCameraStatus != null) {
                tvCameraStatus.setText(status);
            }
        });
    }
    
    @Override
    public void updatePhotoCount(int count) {
        runOnUiThread(() -> {
            if (tvPhotoCount != null) {
                tvPhotoCount.setText(String.format("已拍摄: %d 张", count));
            }
        });
    }
    
    @Override
    public void showError(String error) {
        runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                .setTitle("错误")
                .setMessage(error)
                .setPositiveButton("确定", null)
                .show();
        });
    }
    
    @Override
    public void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
    
    @Override
    public void updateIsoDisplay(int iso) {
        runOnUiThread(() -> {
            if (tvIsoValue != null) {
                tvIsoValue.setText(String.valueOf(iso));
            }
        });
    }
    
    @Override
    public void updateExposureDisplay(String exposureText) {
        runOnUiThread(() -> {
            if (tvExposureValue != null) {
                tvExposureValue.setText(exposureText);
            }
        });
    }
    
    @Override
    public void updateBrightnessMode(String mode) {
        runOnUiThread(() -> {
            if (tvBrightnessLabel != null) {
                tvBrightnessLabel.setText(mode);
            }
        });
    }
    
    @Override
    public void updateExposureValue(String exposureValue) {
        runOnUiThread(() -> {
            if (tvBrightnessValue != null) {
                tvBrightnessValue.setText(exposureValue);
                tvBrightnessValue.setTextColor(getResources().getColor(R.color.status_text));
            }
        });
    }
    
    @Override
    public void resetSeekBars() {
        runOnUiThread(() -> {
            seekBarIso.setProgress(0);
            seekBarExposure.setProgress(0);
            seekBarBrightness.setProgress(0);
        });
    }
    
    @Override
    public void setSeekBarProgress(int seekBarId, int progress) {
        runOnUiThread(() -> {
            SeekBar seekBar = findViewById(seekBarId);
            if (seekBar != null) {
                seekBar.setProgress(progress);
            }
        });
    }
    
    @Override
    public void displayCapturedImage(Bitmap bitmap) {
        runOnUiThread(() -> {
            if (imageView != null && bitmap != null) {
                imageView.setImageBitmap(bitmap);
            }
        });
    }
    
    @Override
    public int getIsoProgress() {
        return seekBarIso != null ? seekBarIso.getProgress() : 0;
    }
    
    @Override
    public int getExposureProgress() {
        return seekBarExposure != null ? seekBarExposure.getProgress() : 0;
    }
    
    @Override
    public int getBrightnessProgress() {
        return seekBarBrightness != null ? seekBarBrightness.getProgress() : 0;
    }
    
    @Override
    public void applyCameraIsoParameter(int iso) {
        updateIsoParameter(iso);
    }
    
    @Override
    public void applyCameraExposureParameter(long exposure) {
        updateExposureParameter(exposure);
    }

    // =============================================================================
    // Activity生命周期和权限处理
    // =============================================================================
    
    @Override
    protected void onResume() {
        super.onResume();
        if (presenter != null) {
            presenter.onResume();
        }
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (textureView.isAvailable()) {
                openCamera();
            } else {
                textureView.setSurfaceTextureListener(surfaceTextureListener);
            }
        } else {
            requestPermissions();
        }
    }
    
    @Override
    protected void onPause() {
        if (presenter != null) {
            presenter.onPause();
        }
        closeCamera();
        super.onPause();
    }
    
    @Override
    protected void onDestroy() {
        if (presenter != null) {
            presenter.onDestroy();
        }
        super.onDestroy();
    }
    
    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        
        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), 1001);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == 1001) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted && presenter != null) {
                presenter.onPermissionGranted();
            } else if (presenter != null) {
                presenter.onPermissionDenied();
            }
        }
    }

    // =============================================================================
    // 相机操作方法（简化版，与Presenter协作）
    // =============================================================================
    
    // TextureView表面纹理状态监听器实现
    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            openCamera();
        }
        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            return true;
        }
        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
    };
    
    /**
     * 打开相机（委托给Presenter处理业务逻辑）
     */
    private void openCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions();
            return;
        }
        
        if (presenter != null) {
            presenter.initializeCamera();
        }
        
        // 这里保留原有的Camera2 API操作
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            
            float[] availableApertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
            if (availableApertures != null && availableApertures.length > 0) {
                aperture = availableApertures[0];
            }
            
            Size largestJpegSize = Collections.max(
                    Arrays.asList(map.getOutputSizes(android.graphics.ImageFormat.JPEG)),
                    new CompareSizesByArea()
            );
            
            // 修复预览尺寸选择问题
            Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            
            // 如果TextureView尺寸为0，使用屏幕尺寸
            if (viewWidth == 0 || viewHeight == 0) {
                android.util.DisplayMetrics displayMetrics = new android.util.DisplayMetrics();
                getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                viewWidth = displayMetrics.widthPixels;
                viewHeight = displayMetrics.heightPixels;
            }
            
            previewSize = chooseOptimalSize(
                    previewSizes,
                    viewWidth,
                    viewHeight,
                    largestJpegSize
            );
            
            Log.d(TAG, "Selected preview size: " + previewSize.getWidth() + "x" + previewSize.getHeight());
            Log.d(TAG, "TextureView size: " + viewWidth + "x" + viewHeight);
            
            // 调整TextureView以匹配相机预览的宽高比，避免拉伸
            adjustTextureViewAspectRatio(previewSize, viewWidth, viewHeight);
            
            imageReader = ImageReader.newInstance(
                    largestJpegSize.getWidth(),
                    largestJpegSize.getHeight(),
                    android.graphics.ImageFormat.JPEG,
                    2
            );
            imageReader.setOnImageAvailableListener(
                    imageAvailableListener,
                    new Handler(Looper.getMainLooper())
            );
            
            cameraManager.openCamera(cameraId, stateCallback, null);
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access exception", e);
            if (presenter != null) {
                presenter.onCameraError(CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
            }
        }
    }
    
    // 相机设备状态回调
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            startPreview();
            if (presenter != null) {
                presenter.onCameraOpened();
            }
        }
        
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            closeCamera();
            if (presenter != null) {
                presenter.onCameraDisconnected();
            }
        }
        
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            closeCamera();
            if (presenter != null) {
                presenter.onCameraError(error);
            }
        }
    };
    
    /**
     * 启动预览
     */
    private void startPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) {
                Log.e(TAG, "SurfaceTexture is null");
                return;
            }
            
            // 设置缓冲区尺寸
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);
            
            Log.d(TAG, "Creating preview surface with size: " + previewSize.getWidth() + "x" + previewSize.getHeight());
            
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(previewSurface);
            
            // 修复相机参数设置 - 先使用自动模式确保预览正常
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            // 先使用自动曝光，等预览正常后再切换到手动模式
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            
            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "Camera capture session configured successfully");
                            cameraCaptureSession = session;
                            updatePreview();
                        }
                        
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Camera capture session configuration failed");
                            showError("相机预览配置失败");
                        }
                    },
                    null
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to start preview", e);
            showError("启动预览失败: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error starting preview", e);
            showError("预览启动发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 更新预览
     */
    private void updatePreview() {
        if (cameraDevice == null) {
            Log.w(TAG, "Camera device is null, cannot update preview");
            return;
        }
        
        if (previewRequestBuilder == null) {
            Log.w(TAG, "Preview request builder is null, cannot update preview");
            return;
        }
        
        if (cameraCaptureSession == null) {
            Log.w(TAG, "Camera capture session is null, cannot update preview");
            return;
        }
        
        try {
            Log.d(TAG, "Starting repeating preview request");
            cameraCaptureSession.setRepeatingRequest(
                    previewRequestBuilder.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureStarted(@NonNull CameraCaptureSession session, 
                                                    @NonNull CaptureRequest request, 
                                                    long timestamp, 
                                                    long frameNumber) {
                            Log.d(TAG, "Preview capture started, frame: " + frameNumber);
                        }
                        
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                      @NonNull CaptureRequest request,
                                                      @NonNull TotalCaptureResult result) {
                            // 预览正常运行后，可以切换到手动模式
                            // Log.d(TAG, "Preview capture completed");
                        }
                        
                        @Override
                        public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                                   @NonNull CaptureRequest request,
                                                   @NonNull CaptureFailure failure) {
                            Log.e(TAG, "Preview capture failed: " + failure.getReason());
                        }
                    },
                    null
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to update preview", e);
            showError("更新预览失败: " + e.getMessage());
        } catch (IllegalStateException e) {
            Log.e(TAG, "Camera session is closed", e);
            showError("相机会话已关闭");
        }
    }
    
    /**
     * 拍照
     */
    private void takePicture() {
        if (cameraDevice == null) return;
        
        try {
            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
            
            cameraCaptureSession.stopRepeating();
            cameraCaptureSession.capture(
                    captureBuilder.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                       @NonNull CaptureRequest request,
                                                       @NonNull TotalCaptureResult result) {
                            super.onCaptureCompleted(session, request, result);
                            updatePreview();
                        }
                    },
                    null
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to take picture", e);
        }
    }
    
    /**
     * 图像捕获监听器
     */
    private final ImageReader.OnImageAvailableListener imageAvailableListener = reader -> {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        image.close();
        
        // 委托给Presenter处理图像数据
        if (presenter != null) {
            presenter.onImageCaptured(bytes);
        }
    };
    
    /**
     * 关闭相机
     */
    private void closeCamera() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }
    
    /**
     * 更新ISO参数（委托给Presenter处理业务逻辑）
     */
    public void updateIsoParameter(int iso) {
        if (previewRequestBuilder != null) {
            // 切换到手动模式
            switchToManualMode();
            previewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            updatePreview();
        }
    }
    
    /**
     * 更新曝光时间参数（委托给Presenter处理业务逻辑）
     */
    public void updateExposureParameter(long exposure) {
        if (previewRequestBuilder != null) {
            // 切换到手动模式
            switchToManualMode();
            previewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
            updatePreview();
        }
    }
    
    /**
     * 切换到手动模式（关闭自动曝光）
     */
    private void switchToManualMode() {
        if (previewRequestBuilder != null) {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            
            // 设置默认的手动参数，防止参数为空导致黑屏
            if (isoRange != null && exposureRange != null) {
                // 如果没有设置过ISO，使用中间值
                Integer currentIso = previewRequestBuilder.get(CaptureRequest.SENSOR_SENSITIVITY);
                if (currentIso == null) {
                    int defaultIso = (isoRange.getLower() + isoRange.getUpper()) / 4; // 使用较低的ISO
                    previewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, defaultIso);
                    Log.d(TAG, "Set default ISO: " + defaultIso);
                }
                
                // 如果没有设置过曝光时间，使用中间值
                Long currentExposure = previewRequestBuilder.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
                if (currentExposure == null) {
                    long defaultExposure = (exposureRange.getLower() + exposureRange.getUpper()) / 4; // 使用较短的曝光时间
                    previewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, defaultExposure);
                    Log.d(TAG, "Set default exposure: " + defaultExposure + " ns");
                }
            }
            
            Log.d(TAG, "Switched to manual exposure mode");
        }
    }
    
    // =============================================================================
    // 工具方法
    // =============================================================================
    
    /**
     * 调整TextureView的宽高比以匹配相机预览，避免拉伸（简化版）
     */
    private void adjustTextureViewAspectRatio(Size previewSize, int viewWidth, int viewHeight) {
        if (previewSize == null || textureView == null) {
            return;
        }
        
        int previewWidth = previewSize.getWidth();
        int previewHeight = previewSize.getHeight();
        
        // 考虑设备方向，对于手机通常需要交换宽高
        if (sensorOrientation == 90 || sensorOrientation == 270) {
            previewWidth = previewSize.getHeight();
            previewHeight = previewSize.getWidth();
        }
        
        // 计算相机预览的宽高比
        float previewAspectRatio = (float) previewWidth / previewHeight;
        
        // 计算TextureView容器的宽高比
        float viewAspectRatio = (float) viewWidth / viewHeight;
        
        Log.d(TAG, "Preview aspect ratio: " + previewAspectRatio + " (" + previewWidth + "x" + previewHeight + ")");
        Log.d(TAG, "View aspect ratio: " + viewAspectRatio + " (" + viewWidth + "x" + viewHeight + ")");
        
        // 检查是否需要调整
        if (Math.abs(previewAspectRatio - viewAspectRatio) < 0.1f) {
            Log.d(TAG, "Aspect ratios are similar, no adjustment needed");
            return;
        }
        
        // 在UI线程中调整TextureView尺寸（只使用布局调整，不使用Matrix变换）
        runOnUiThread(() -> {
            int newWidth, newHeight;
            
            if (previewAspectRatio > viewAspectRatio) {
                // 相机预览更宽，以宽度为准，调整高度
                newWidth = viewWidth;
                newHeight = Math.round(viewWidth / previewAspectRatio);
            } else {
                // 相机预览更高，以高度为准，调整宽度
                newHeight = viewHeight;
                newWidth = Math.round(viewHeight * previewAspectRatio);
            }
            
            // 设置TextureView的布局参数
            android.view.ViewGroup.LayoutParams layoutParams = textureView.getLayoutParams();
            if (layoutParams != null) {
                layoutParams.width = newWidth;
                layoutParams.height = newHeight;
                textureView.setLayoutParams(layoutParams);
                
                Log.d(TAG, "Adjusted TextureView size to: " + newWidth + "x" + newHeight);
            }
            
            // 清除任何可能存在的Matrix变换
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            textureView.setTransform(matrix);
            
            Log.d(TAG, "Reset TextureView transform to identity matrix");
        });
    }
    
    /**
     * 选择最优的相机输出尺寸（简化版，优先保证稳定性）
     */
    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        if (choices == null || choices.length == 0) {
            Log.e("CameraUtils", "No size choices available");
            return new Size(640, 480); // 默认尺寸
        }
        
        // 打印所有可用尺寸
        Log.d("CameraUtils", "Available preview sizes:");
        for (Size size : choices) {
            Log.d("CameraUtils", "  " + size.getWidth() + "x" + size.getHeight());
        }
        
        // 简化策略：选择面积适中的尺寸，避免过大或过小
        List<Size> suitableSizes = new ArrayList<>();
        
        // 计算目标面积
        int targetArea = width * height;
        
        for (Size option : choices) {
            int optionArea = option.getWidth() * option.getHeight();
            
            // 选择面积在目标的 0.5x 到 4x 之间的尺寸
            if (optionArea >= targetArea / 2 && optionArea <= targetArea * 4) {
                suitableSizes.add(option);
                Log.d("CameraUtils", "Suitable size: " + option.getWidth() + "x" + option.getHeight());
            }
        }
        
        Size selectedSize;
        if (!suitableSizes.isEmpty()) {
            // 从合适的尺寸中选择中等面积的
            selectedSize = suitableSizes.get(suitableSizes.size() / 2);
        } else {
            // 如果没有合适的，选择中等大小的
            Arrays.sort(choices, new CompareSizesByArea());
            selectedSize = choices[choices.length / 2];
        }
        
        Log.d("CameraUtils", "Selected size: " + selectedSize.getWidth() + "x" + selectedSize.getHeight() + 
              " for target: " + width + "x" + height);
        
        return selectedSize;
    }
    
    /**
     * 尺寸面积比较器
     */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}