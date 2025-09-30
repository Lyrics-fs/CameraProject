package com.example.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.exifinterface.media.ExifInterface;


import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;

public class MainActivity extends AppCompatActivity {
    private TextureView textureView;       // 显示相机预览的纹理视图
    private Button captureButton;          // 拍照按钮
    private ImageView imageView;           // 显示捕获的图片
    private TextView tvBrightnessValue;    // 显示亮度值
    private SeekBar seekBarBrightness, seekBarIso, seekBarExposure; // 调节参数的控制条


    // 相机相关变量
    private String cameraId = "0";         // 默认使用后置摄像头
    private CameraDevice cameraDevice;     // 相机设备实例
    private CameraCaptureSession cameraCaptureSession; // 相机捕获会话
    private CaptureRequest.Builder previewRequestBuilder; // 预览请求构建器
    private ImageReader imageReader;       // 用于捕获静态图像的ImageReader
    private Size previewSize;              // 预览尺寸
    private CameraManager cameraManager;   // 相机管理器
    private float aperture = 1.0f; // 默认为 f/1.0（需根据实际相机参数修正）

    // 相机参数范围
    private Range<Integer> isoRange;       // ISO感光度范围
    private Range<Long> exposureRange;     // 曝光时间范围
    private int sensorOrientation;         // 传感器方向

    // 屏幕旋转方向映射表（用于JPEG方向校正）
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    @Override
    // Activity创建时的初始化方法
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);  // 调用父类构造方法
        setContentView(R.layout.activity_main);  // 设置布局文件

        // 初始化界面组件
        textureView = findViewById(R.id.tv);         // 相机预览视图
        captureButton = findViewById(R.id.btn);      // 拍照按钮
        imageView = findViewById(R.id.iv);           // 显示拍摄结果的ImageView
        tvBrightnessValue = findViewById(R.id.tv_display_value); // 显示亮度值的TextView

        // 初始化相机参数调节滑动条
        seekBarBrightness = findViewById(R.id.seekBarBrightness); // 亮度调节滑动条
        seekBarIso = findViewById(R.id.seekBarIso);               // ISO感光度调节滑动条
        seekBarExposure = findViewById(R.id.seekBarExposure);     // 曝光时间调节滑动条

        // 设置ISO调节滑动条监听
        seekBarIso.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateIso(progress);  // 当进度改变时更新ISO值
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 设置曝光时间调节滑动条监听
        seekBarExposure.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateExposure(progress);  // 当进度改变时更新曝光时间
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 设置亮度调节滑动条监听
        seekBarBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateBrightness(progress);  // 当进度改变时更新亮度值
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 拍照按钮点击监听
        captureButton.setOnClickListener(v -> takePicture());  // 点击时执行拍照方法

        // 设置TextureView的表面纹理监听（用于相机预览）
        textureView.setSurfaceTextureListener(surfaceTextureListener);

        // 获取相机管理服务（Camera2 API入口）
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
    }

    // TextureView表面纹理状态监听器实现
    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        // 当SurfaceTexture准备就绪时触发（首次可用时调用）
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            openCamera();  // 初始化并打开相机
        }

        // 当SurfaceTexture尺寸发生变化时触发（此处未处理）
        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}

        // 当SurfaceTexture被销毁时触发
        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            return true;  // true表示由外部释放SurfaceTexture资源
        }

        // 当SurfaceTexture内容更新时触发（每帧预览时都会调用）
        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
    };

    // 打开相机的核心方法
    private void openCamera() {
        // 1. 检查相机权限
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // 如果没有权限则请求权限（请求码为1）
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 1);
            return; // 权限未授予时直接返回
        }

        try {
            // 2. 获取相机特性参数
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

            // 3. 获取流配置信息（用于获取支持的输出尺寸）
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            // 4. 获取传感器方向（用于后续处理图像方向）
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            // 5. 获取相机传感器支持的可调参数范围
            isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE); // ISO感光度范围
            exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE); // 曝光时间范围
            // 获取相机固定光圈值（手机摄像头通常只有一个固定光圈）
            float[] availableApertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
            if (availableApertures != null && availableApertures.length > 0) {
                aperture = availableApertures[0]; // 使用第一个（唯一）可用光圈值
            }

            // 6. 配置预览尺寸
            // 获取最大的JPEG输出尺寸（用于拍照）
            Size largestJpegSize = Collections.max(
                    Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                    new CompareSizesByArea() // 自定义的面积比较器
            );

            // 选择最优预览尺寸（需传入textureView的宽高和最大尺寸）
            previewSize = chooseOptimalSize(
                    map.getOutputSizes(SurfaceTexture.class), // 支持的预览尺寸
                    textureView.getWidth(),   // 预览视图宽度
                    textureView.getHeight(),  // 预览视图高度
                    largestJpegSize           // 最大输出尺寸（原注释提到应添加宽高比参数）
            );

            // 7. 配置图像读取器（用于捕获静态照片）
            Size largest = Collections.max(
                    Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                    new CompareSizesByArea()
            );
            // 创建JPEG格式的ImageReader（最多缓存2张图像）
            imageReader = ImageReader.newInstance(
                    largest.getWidth(),
                    largest.getHeight(),
                    ImageFormat.JPEG,
                    2
            );
            // 设置图像可用监听器（在主线程处理）
            imageReader.setOnImageAvailableListener(
                    imageAvailableListener,
                    new Handler(Looper.getMainLooper())
            );

            // 8. 正式打开相机（传入相机ID和状态回调）
            cameraManager.openCamera(cameraId, stateCallback, null);

        } catch (CameraAccessException e) {
            // 处理相机访问异常
            Log.e("Camera", "Camera access exception", e);
        }
    }

    // 相机设备状态回调实现（处理相机连接状态变化）
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        // 当相机成功打开时调用
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;  // 保存相机设备实例引用
            startPreview();         // 启动相机预览（需要后续实现预览配置）
        }

        // 当相机断开连接时调用（如被其他应用占用）
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            closeCamera();  // 执行相机资源释放操作
        }

        // 当发生错误时调用（错误码见CameraDevice.StateCallback文档）
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            closeCamera();  // 无论发生何种错误都关闭相机
        }
    };

    // 启动相机预览的核心方法
    private void startPreview() {
        try {
            // 1. 准备预览表面（Surface）
            SurfaceTexture texture = textureView.getSurfaceTexture();  // 获取TextureView的表面纹理
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight()); // 设置缓冲区尺寸为选择的预览尺寸
            Surface previewSurface = new Surface(texture);  // 创建用于预览的Surface对象

            // 2. 创建预览请求构建器
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); // 使用预览模板
            previewRequestBuilder.addTarget(previewSurface);  // 将预览表面添加为输出目标

            // 3. 配置手动控制参数
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF); // 关闭自动曝光
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO); // 自动模式

            // 4. 创建捕获会话（同时绑定预览和拍照输出）
            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, imageReader.getSurface()), // 包含预览和拍照两个输出目标
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            // 会话配置成功后的回调
                            cameraCaptureSession = session;  // 保存会话实例
                            updatePreview();  // 开始持续发送预览请求
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            // 会话配置失败处理（示例中为空实现，实际需添加错误处理）
                        }
                    },
                    null // 可选Handler（null表示使用当前线程）
            );
        } catch (CameraAccessException e) {
            Log.e("Camera", "Failed to start preview", e); // 捕获相机访问异常
        }
    }

    // 更新并持续发送预览请求的方法
    private void updatePreview() {
        // 安全校验：确保相机设备和请求构建器已初始化
        if (cameraDevice == null || previewRequestBuilder == null) return;

        try {
            // 构建并持续发送预览请求（每秒约30帧）
            cameraCaptureSession.setRepeatingRequest(
                    previewRequestBuilder.build(), // 使用配置好的请求参数
                    null,  // 捕获结果回调（未设置）
                    null   // 使用的Handler（null表示主线程）
            );
        } catch (CameraAccessException e) {
            // 处理相机访问异常（如相机被其他应用占用）
            Log.e("Camera", "Failed to update preview", e);
        }
    }

    private void updateIso(int progress) {
        if (previewRequestBuilder == null) return;
        int iso = isoRange.getLower() + (int)((isoRange.getUpper() - isoRange.getLower()) * (progress / 5000f));
        previewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
        seekBarBrightness.setProgress(0);
        updatePreview();
        updateBrightnessDisplay();
        Log.d("Camera", "ISO set to: " + iso);
    }

    private void updateExposure(int progress) {
        if (previewRequestBuilder == null) return;
        long exposure = exposureRange.getLower() + (long)((exposureRange.getUpper() - exposureRange.getLower()) * (progress / 5000f));
        previewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
        seekBarBrightness.setProgress(0);
        updatePreview();
        updateBrightnessDisplay();
        Log.d("Camera", "Exposure set to: " + exposure + " ns");
    }

    private void updateBrightness(int progress) {
        if (previewRequestBuilder == null) return;
        // Adjust both ISO and exposure for brightness effect
        int iso = isoRange.getLower() + (int)((isoRange.getUpper() - isoRange.getLower()) * (progress / 5000f));
        long exposure = exposureRange.getLower() + (long)((exposureRange.getUpper() - exposureRange.getLower()) * (progress / 5000f));

        // 当调整亮度时，将ISO和曝光SeekBar设为0
        runOnUiThread(() -> {
            seekBarIso.setProgress(0);
            seekBarExposure.setProgress(0);
        });

        previewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
        previewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
        updatePreview();
        updateBrightnessDisplay1();
        Log.d("Camera", "Brightness adjusted - ISO: " + iso + ", Exposure: " + exposure);
    }

    private void updateBrightnessDisplay() {
        int exposureProgress = seekBarExposure.getProgress();
        int isoProgress = seekBarIso.getProgress();

        // 获取当前ISO和曝光时间
        long exposure = exposureRange.getLower() + (long) ((exposureRange.getUpper() - exposureRange.getLower()) * (exposureProgress / 5000f));
        int iso = isoRange.getLower() + (int) ((isoRange.getUpper() - isoRange.getLower()) * (isoProgress / 5000f));

        // 转换为秒（1秒 = 1e9纳秒）
        double exposureSeconds = exposure / 1_000_000_000.0;

        // 计算曝光量 E = (ISO * exposureTime) / (aperture^2)
        double E = (iso * exposureSeconds) ;

        // 显示结果（保留两位小数）
        runOnUiThread(() -> tvBrightnessValue.setText(String.format("E: %.2f", E)));
        tvBrightnessValue.setTextColor(Color.GREEN);  // 新增这行设置字体颜色为绿色
    }

    private void updateBrightnessDisplay1() {
        if (seekBarBrightness == null || seekBarExposure == null || seekBarIso == null) return;

        int brightness = seekBarBrightness.getProgress(); // 亮度滑动条进度（0-5000）

        // 根据亮度滑动条计算ISO和曝光时间（同时影响两个参数）
        long exposure = exposureRange.getLower() + (long) ((exposureRange.getUpper() - exposureRange.getLower()) * (brightness / 5000f));
        int iso = isoRange.getLower() + (int) ((isoRange.getUpper() - isoRange.getLower()) * (brightness / 5000f));

        // 单位转换：曝光时间（纳秒 → 秒）
        double exposureSeconds = exposure / 1_000_000_000.0;

        // 计算曝光量 E = (ISO * exposureTime) / (aperture^2)
        double E = (iso * exposureSeconds);

        // 在UI线程更新显示
        runOnUiThread(() -> {
            tvBrightnessValue.setText(String.format("E: %.2f", E));
            tvBrightnessValue.setTextColor(Color.GREEN);  // 新增这行设置字体颜色为绿色
            Log.d("ExposureCalc",
                    String.format("ISO=%d, Exposure=%.4fs, F=%.1f → E=%.2f",
                            iso, exposureSeconds, aperture, E)
            );
        });
    }

    // 执行拍照操作的核心方法
    private void takePicture() {
        // 安全校验：确保相机设备已初始化
        if (cameraDevice == null) return;

        try {
            // 1. 创建拍照请求构建器（使用静态照片模板）
            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            // 2. 设置输出目标到ImageReader（用于获取JPEG数据）
            captureBuilder.addTarget(imageReader.getSurface());

            // 3. 设置照片方向（根据设备旋转角度自动适配）
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

            // 4. 停止持续预览请求（防止拍照时预览干扰）
            cameraCaptureSession.stopRepeating();

            // 5. 发送单次拍照请求
            cameraCaptureSession.capture(
                    captureBuilder.build(),  // 构建拍照请求
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                       @NonNull CaptureRequest request,
                                                       @NonNull TotalCaptureResult result) {
                            super.onCaptureCompleted(session, request, result);
                            // 拍照完成后恢复预览
                            updatePreview();
                        }
                    },
                    null // 可选Handler（null表示主线程）
            );
        } catch (CameraAccessException e) {
            Log.e("Camera", "Failed to take picture", e); // 处理相机访问异常
        }
    }

    private final ImageReader.OnImageAvailableListener imageAvailableListener = reader -> {
        Image image = reader.acquireLatestImage();
        if (image == null) return;

        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        image.close();

        // 解码原始JPEG数据为Bitmap
        Bitmap originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

        // 保存原始图像（一定要先保存，才能读取EXIF）
        File originalFile = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "Camera/" + System.currentTimeMillis() + "_original.jpg"
        );
        try (FileOutputStream output = new FileOutputStream(originalFile)) {
            output.write(bytes);
        } catch (IOException e) {
            Log.e("Camera", "保存原始图像失败", e);
        }

        // 读取EXIF亮度
        String exifBrightness = "";
        try {
            androidx.exifinterface.media.ExifInterface exif = new androidx.exifinterface.media.ExifInterface(originalFile.getAbsolutePath());
            exifBrightness = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_BRIGHTNESS_VALUE);
            if (exifBrightness == null) exifBrightness = "N/A";
        } catch (IOException e) {
            exifBrightness = "读取失败";
        }

        // 生成伪彩色图像，带EXIF亮度
        Bitmap pseudoColorBitmap = createPseudoColorImage(originalBitmap, exifBrightness);
        imageView.post(() -> imageView.setImageBitmap(pseudoColorBitmap));

        // 保存伪彩色图像到文件
        File pseudoFile = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "Camera/" + System.currentTimeMillis() + "_pseudo.jpg"
        );
        try (FileOutputStream output = new FileOutputStream(pseudoFile)) {
            pseudoColorBitmap.compress(Bitmap.CompressFormat.JPEG, 100, output);
            runOnUiThread(() -> Toast.makeText(this, "伪彩色图像已保存: " + pseudoFile, Toast.LENGTH_SHORT).show());
        } catch (IOException e) {
            Log.e("Camera", "保存伪彩色图像失败", e);
        }
    };


    /**
     * 选择最优的相机输出尺寸（用于预览/拍照配置）
     *
     * @param choices      相机支持的可用尺寸列表
     * @param width       目标宽度（通常为预览视图宽度）
     * @param height      目标高度（通常为预览视图高度）
     * @param aspectRatio 期望的宽高比（通常取最大JPEG尺寸的宽高比）
     * @return 满足条件的最优尺寸，若找不到则返回第一个可用尺寸
     */
    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // 存储所有符合宽高比且不小于目标尺寸的候选尺寸
        List<Size> bigEnough = new ArrayList<>();

        // 获取期望宽高比的分子分母（需注意参数顺序：aspectRatio的宽高应与设备方向匹配）
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();

        // 遍历所有可用尺寸进行筛选
        for (Size option : choices) {
            // 条件1：尺寸宽高比必须严格匹配期望比例（可能存在精度问题，建议改为近似判断）
            boolean isCorrectRatio = (option.getHeight() == option.getWidth() * h / w);

            // 条件2：尺寸不小于目标视图尺寸（注意：可能所有选项都比目标小，此时条件永远不成立）
            boolean isLargeEnough = option.getWidth() >= width && option.getHeight() >= height;

            if (isCorrectRatio && isLargeEnough) {
                bigEnough.add(option);
            }
        }

        // 存在候选尺寸时，返回面积最小的（节省资源）
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        }
        // 无候选时返回第一个可用尺寸（可能不符合比例或尺寸要求）
        else {
            return choices[0];
        }
    }

    /**
     * 尺寸面积比较器（用于按面积大小排序Size对象）
     *
     * 功能说明：
     * 1. 实现Comparator接口，用于比较两个Size对象的面积
     * 2. 防止整数溢出：使用long类型进行乘法运算
     * 3. 排序规则：按面积升序排列（从小到大）
     */
    static class CompareSizesByArea implements Comparator<Size> {
        /**
         * 比较两个尺寸的面积大小
         * @param lhs 左侧尺寸对象
         * @param rhs 右侧尺寸对象
         * @return 比较结果：
         *         - 正数：lhs面积 > rhs面积
         *         - 零：面积相等
         *         - 负数：lhs面积 < rhs面积
         */
        @Override
        public int compare(Size lhs, Size rhs) {
            // 转换为long防止大尺寸相乘时int溢出（如4032x3024=12,192,768 > Integer.MAX_VALUE=2,147,483,647）
            long lhsArea = (long) lhs.getWidth() * lhs.getHeight();
            long rhsArea = (long) rhs.getWidth() * rhs.getHeight();

            // 使用Long.signum确保返回符合Comparator规范的-1/0/1
            return Long.signum(lhsArea - rhsArea);
        }
    }

    /**
     * 安全关闭相机及相关资源的清理方法
     *
     * 功能说明：
     * 1. 按正确顺序释放相机相关资源
     * 2. 防止内存泄漏和相机设备占用
     * 3. 应在以下情况调用：
     *    - Activity/Fragment销毁时
     *    - 相机断开连接时
     *    - 发生不可恢复的错误时
     */
    private void closeCamera() {
        // 1. 关闭捕获会话（必须先于相机设备关闭）
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();  // 停止所有正在进行的捕获请求
            cameraCaptureSession = null;   // 防止野指针
        }

        // 2. 关闭相机设备（释放硬件资源）
        if (cameraDevice != null) {
            cameraDevice.close();         // 必须调用以释放相机给其他应用
            cameraDevice = null;          // 清除设备引用
        }

        // 3. 关闭图像读取器（释放图像缓冲区）
        if (imageReader != null) {
            imageReader.close();         // 停止接收新图像并释放资源
            imageReader = null;           // 防止内存泄漏
        }
    }

    // Activity生命周期 - 恢复时触发（从后台返回/初次创建时）
    @Override
    protected void onResume() {
        super.onResume(); // 必须首先调用父类方法

        // 检查TextureView是否已初始化完成
        if (textureView.isAvailable()) {
            openCamera(); // 直接打开相机（视图已准备好）
        } else {
            // 设置纹理监听器（当视图准备就绪后自动触发openCamera）
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    // Activity生命周期 - 暂停时触发（进入后台/被遮挡）
    @Override
    protected void onPause() {
        // 优先释放相机资源（防止占用导致其他应用无法使用相机）
        closeCamera();

        super.onPause(); // 最后调用父类方法
    }

    /**
     * 权限请求结果回调处理
     *
     * 当用户响应权限请求对话框后，系统会调用此方法
     *
     * @param requestCode  请求码（用于区分不同权限请求）
     * @param permissions  被请求的权限数组
     * @param grantResults 对应权限的授予结果数组
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        // 必须首先调用父类处理（确保Fragment等能正确处理）
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // 检查是否为相机权限请求（requestCode需与请求时一致）
        if (requestCode == 1) {
            // 验证结果数组有效性（防止用户取消授权导致的空数组）
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限已授予，启动相机
                openCamera();
            } else {
                // 权限被拒绝时的处理
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
            }
        } else {
        }
    }


    /**
     * 生成伪彩色图像并绘制基于亮度L的图例
     *
     * @param originalBitmap 原始灰度图
     * @param exifBrightness EXIF亮度字符串（可为空）
     * @return 带亮度L区间图例的伪彩色位图
     */
    private Bitmap createPseudoColorImage(Bitmap originalBitmap, String exifBrightness) {
        int width = originalBitmap.getWidth();
        int height = originalBitmap.getHeight();

        // 生成伪彩色图像
        int[] pixels = new int[width * height];
        originalBitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int i = 0; i < pixels.length; i++) {
            int gray = Color.red(pixels[i]);
            int color;
            if (gray < 64) {
                color = Color.rgb(0, 0, gray * 4);
            } else if (gray < 128) {
                int blue = 255 - (gray - 64) * 4;
                int green = (gray - 64) * 4;
                color = Color.rgb(0, green, blue);
            } else if (gray < 192) {
                int green = 255 - (gray - 128) * 4;
                int red = (gray - 128) * 4;
                color = Color.rgb(red, green, 0);
            } else {
                int green = (gray - 192) * 4;
                color = Color.rgb(255, green, 0);
            }
            pixels[i] = color;
        }

        Bitmap pseudoBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);

        // 旋转图像
        Matrix rotateMatrix = new Matrix();
        rotateMatrix.postRotate(90);
        Bitmap rotatedPseudoBitmap = Bitmap.createBitmap(pseudoBitmap, 0, 0, width, height, rotateMatrix, true);
        pseudoBitmap.recycle();
        int rotatedWidth = rotatedPseudoBitmap.getWidth();
        int rotatedHeight = rotatedPseudoBitmap.getHeight();

        // 计算平均亮度
        int[][] points = {
                {width / 4, height / 4},
                {width * 3 / 4, height / 4},
                {width / 4, height * 3 / 4},
                {width * 3 / 4, height * 3 / 4},
                {width / 2, height / 2}
        };
        int sumR = 0, sumG = 0, sumB = 0;
        for (int[] pt : points) {
            int pixel = originalBitmap.getPixel(pt[0], pt[1]);
            sumR += Color.red(pixel);
            sumG += Color.green(pixel);
            sumB += Color.blue(pixel);
        }
        int avgR = sumR / points.length;
        int avgG = sumG / points.length;
        int avgB = sumB / points.length;
        double yValue = 0.299 * avgR + 0.587 * avgG + 0.114 * avgB;

        // 亮度L中心值
        float bv = Float.NaN;
        if (exifBrightness != null && !exifBrightness.equals("N/A") && !exifBrightness.equals("读取失败")) {
            try {
                if (exifBrightness.contains("/")) {
                    String[] parts = exifBrightness.split("/");
                    bv = Float.parseFloat(parts[0]) / Float.parseFloat(parts[1]);
                } else {
                    bv = Float.parseFloat(exifBrightness);
                }
            } catch (Exception e) {
                bv = Float.NaN;
            }
        }
        double Lcenter;
        if (!Float.isNaN(bv)) {
            Lcenter = 2.9 * Math.exp(0.729 * bv);
        } else {
            Lcenter = yValue / 255.0 * 400 + 50;
        }

        // 图例区间设定
        final int legendLevels = 4;
        double delta = Lcenter * 0.25;
        double[] lThresholds = new double[legendLevels + 1];
        for (int i = 0; i <= legendLevels; i++) {
            lThresholds[i] = Lcenter - delta + (2 * delta) * i / legendLevels;
        }

        int[][] colorPairs = {
                {Color.rgb(0, 0, 0), Color.rgb(0, 0, 255)},
                {Color.rgb(0, 0, 255), Color.rgb(0, 255, 0)},
                {Color.rgb(0, 255, 0), Color.rgb(255, 255, 0)},
                {Color.rgb(255, 255, 0), Color.rgb(255, 0, 0)}
        };

        final int legendWidth = 190;
        Bitmap finalBitmap = Bitmap.createBitmap(rotatedWidth + legendWidth, rotatedHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(finalBitmap);
        canvas.drawBitmap(rotatedPseudoBitmap, 0, 0, null);
        rotatedPseudoBitmap.recycle();

        // 图例标题
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(Color.BLACK);
        paint.setTextSize(32);
        canvas.drawText("亮度L (cd/m²)", rotatedWidth + 18, 48, paint);

        int itemHeight = rotatedHeight / legendLevels;
        itemHeight = Math.max(itemHeight, 50);
        paint.setTextSize(40); // 字体更大

        for (int i = 0; i < legendLevels; i++) {
            int y = i * itemHeight;
            int startColor = colorPairs[i][0];
            int endColor = colorPairs[i][1];
            LinearGradient gradient = new LinearGradient(
                    rotatedWidth + 15, y + 10,
                    rotatedWidth + legendWidth - 15, y + itemHeight - 10,
                    new int[]{startColor, endColor}, null, Shader.TileMode.CLAMP
            );
            paint.setShader(gradient);
            canvas.drawRect(rotatedWidth + 10, y + 10, rotatedWidth + legendWidth - 10, y + itemHeight - 10, paint);
            paint.setShader(null);

            // 全部严格用上下端点，无∞
            String lLabel = String.format("L\n%.2f\n↓\n%.2f", lThresholds[i], lThresholds[i + 1]);
            paint.setColor(Color.BLACK);
            paint.setTextSize(40);

            // 居中竖排
            float lineHeight = paint.getTextSize() + 8;
            String[] lines = lLabel.split("\n");
            float totalHeight = lines.length * lineHeight;
            float textY = y + (itemHeight - totalHeight) / 2 + lineHeight;
            float textX = rotatedWidth + legendWidth / 2f - paint.measureText("00.00") / 2;

            for (int j = 0; j < lines.length; j++) {
                canvas.drawText(lines[j], textX, textY + j * lineHeight, paint);
            }
        }

        // 统计和EXIF
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36);
        textPaint.setAntiAlias(true);
        textPaint.setShadowLayer(2.0f, 2, 2, Color.BLACK);

        canvas.drawText(String.format("Avg RGB: R=%d, G=%d, B=%d", avgR, avgG, avgB), 30, 60, textPaint);
        canvas.drawText(String.format("Gray = %.2f", yValue), 30, 110, textPaint);
        canvas.drawText(String.format("EXIF BV = %s", exifBrightness), 30, 170, textPaint);

        String lResult;
        if (!Float.isNaN(bv)) {
            double L = 2.9 * Math.exp(0.729 * bv);
            lResult = String.format("L = 2.9 × exp(0.729×BV) = %.2f", L);
        } else {
            lResult = "L = N/A";
        }
        canvas.drawText(lResult, 30, 220, textPaint);

        return finalBitmap;
    }





    /**
     * 竖排绘制一行文字（单字一行）
     */
    private void drawVerticalText(Canvas canvas, String text, float x, float startY, Paint paint, float lineHeight) {
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            canvas.drawText(ch, x, startY + i * lineHeight, paint);
        }
    }




    private static class LegendItem {
        int startColor;
        int endColor;
        String description;

        LegendItem(int startColor, int endColor, String description) {
            this.startColor = startColor;
            this.endColor = endColor;
            this.description = description;
        }
    }
}