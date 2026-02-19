package com.example.camera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import android.hardware.camera2.CaptureRequest;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CameraXGL";
    private static final int REQUEST_CAMERA_PERMISSION = 1;

    // UI 组件
    private GLSurfaceView glSurfaceView;
    private Button captureButton;
    private ImageView imageView;
    private TextView tvBrightnessValue;
    private TextView tvExposureLabel;
    private TextView tvIsoLabel;
    private TextView tvBrightnessLabel;
    private SeekBar seekBarBrightness, seekBarIso, seekBarExposure;

    // OpenGL ES 渲染器
    private CameraRenderer cameraRenderer;

    // CameraX 组件
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageCapture imageCapture;
    private Preview preview;

    // 相机参数（通过 Camera2 Interop 手动控制）
    private android.util.Range<Integer> isoRange;
    private android.util.Range<Long> exposureRange;
    private float aperture = 1.8f;

    // 当前手动参数值
    private int currentIso = -1;
    private long currentExposure = -1;

    private final Executor captureExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        glSurfaceView     = findViewById(R.id.gl_surface_view);
        captureButton     = findViewById(R.id.btn);
        imageView         = findViewById(R.id.iv);
        tvBrightnessValue = findViewById(R.id.tv_display_value);
        tvExposureLabel   = findViewById(R.id.tv_exposure);
        tvIsoLabel        = findViewById(R.id.tv_iso);
        tvBrightnessLabel = findViewById(R.id.tv_brightness_label);
        seekBarBrightness = findViewById(R.id.seekBarBrightness);
        seekBarIso        = findViewById(R.id.seekBarIso);
        seekBarExposure   = findViewById(R.id.seekBarExposure);

        setupGLSurfaceView();
        setupSeekBars();
        captureButton.setOnClickListener(v -> takePicture());

        // 在布局完成后根据宽度设置预览高度 = 宽度 × 4/3（竖屏相机比例）
        glSurfaceView.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                glSurfaceView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int w = glSurfaceView.getWidth();
                if (w > 0) {
                    android.view.ViewGroup.LayoutParams lp = glSurfaceView.getLayoutParams();
                    lp.height = w * 4 / 3;
                    glSurfaceView.setLayoutParams(lp);
                }
            }
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    // -------------------------------------------------------------------------
    // OpenGL ES 初始化
    // -------------------------------------------------------------------------

    private void setupGLSurfaceView() {
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glSurfaceView.getHolder().setFormat(android.graphics.PixelFormat.RGBA_8888);

        cameraRenderer = new CameraRenderer();
        cameraRenderer.setOnSurfaceTextureAvailableListener(surfaceTexture -> {
            // GL 线程回调：SurfaceTexture 就绪后绑定到 CameraX Preview
            runOnUiThread(() -> bindCameraPreview(surfaceTexture));
        });

        glSurfaceView.setRenderer(cameraRenderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    // -------------------------------------------------------------------------
    // CameraX 启动与绑定
    // -------------------------------------------------------------------------

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                // 如果 GL SurfaceTexture 已就绪则立即绑定，否则等待渲染器回调
                SurfaceTexture st = cameraRenderer.getSurfaceTexture();
                if (st != null) {
                    bindCameraPreview(st);
                }
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "CameraProvider init failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraPreview(SurfaceTexture surfaceTexture) {
        if (cameraProvider == null) return;

        cameraProvider.unbindAll();

        // 将 GL SurfaceTexture 包装为 CameraX Preview.SurfaceProvider
        surfaceTexture.setDefaultBufferSize(1280, 720);
        android.view.Surface glSurface = new android.view.Surface(surfaceTexture);

        Preview.SurfaceProvider surfaceProvider = request -> {
            android.util.Size resolution = request.getResolution();
            surfaceTexture.setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
            request.provideSurface(glSurface,
                    ContextCompat.getMainExecutor(this), result -> {});
            // 通知渲染器相机实际分辨率，用于宽高比校正
            // 后置相机传感器为横向，竖屏时宽高需交换
            glSurfaceView.queueEvent(() ->
                    cameraRenderer.setCameraAspect(resolution.getHeight(), resolution.getWidth()));
        };

        preview = new Preview.Builder().build();
        preview.setSurfaceProvider(surfaceProvider);

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build();

        CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;

        camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);

        // 读取相机参数范围（通过 Camera2 Interop）
        readCameraRanges();
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private void readCameraRanges() {
        if (camera == null) return;
        try {
            androidx.camera.camera2.interop.Camera2CameraInfo info =
                    androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.getCameraInfo());
            isoRange = info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            exposureRange = info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            float[] apertures = info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
            if (apertures != null && apertures.length > 0) aperture = apertures[0];

            // 初始化默认值
            if (isoRange != null) currentIso = isoRange.getLower();
            if (exposureRange != null) currentExposure = exposureRange.getLower();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read camera ranges", e);
        }
    }

    // -------------------------------------------------------------------------
    // SeekBar 监听
    // -------------------------------------------------------------------------

    private void setupSeekBars() {
        seekBarIso.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) { updateIso(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        seekBarExposure.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) { updateExposure(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        seekBarBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) { updateBrightness(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private void applyCamera2Options() {
        if (camera == null) return;
        try {
            CaptureRequestOptions.Builder builder = new CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_OFF);
            if (currentIso > 0)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, currentIso);
            if (currentExposure > 0)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposure);

            Camera2CameraControl.from(camera.getCameraControl())
                    .setCaptureRequestOptions(builder.build());
        } catch (Exception e) {
            Log.e(TAG, "applyCamera2Options failed", e);
        }
    }

    private void updateIso(int progress) {
        if (isoRange == null) return;
        currentIso = isoRange.getLower() +
                (int) ((isoRange.getUpper() - isoRange.getLower()) * (progress / 5000f));
        seekBarBrightness.setProgress(0);
        applyCamera2Options();
        updateBrightnessDisplay();
        runOnUiThread(() -> tvIsoLabel.setText(String.valueOf(currentIso)));
        Log.d(TAG, "ISO=" + currentIso);
    }

    private void updateExposure(int progress) {
        if (exposureRange == null) return;
        currentExposure = exposureRange.getLower() +
                (long) ((exposureRange.getUpper() - exposureRange.getLower()) * (progress / 5000f));
        seekBarBrightness.setProgress(0);
        applyCamera2Options();
        updateBrightnessDisplay();
        double expSec = currentExposure / 1_000_000_000.0;
        String expStr = expSec >= 1.0
                ? String.format("%.2f s", expSec)
                : String.format("1/%.0f s", 1.0 / expSec);
        runOnUiThread(() -> tvExposureLabel.setText(expStr));
        Log.d(TAG, "Exposure=" + currentExposure + " ns");
    }

    private void updateBrightness(int progress) {
        // 亮度滑条：同时调整 ISO + 曝光，并更新 GL 亮度增益
        if (isoRange != null && exposureRange != null) {
            currentIso = isoRange.getLower() +
                    (int) ((isoRange.getUpper() - isoRange.getLower()) * (progress / 5000f));
            currentExposure = exposureRange.getLower() +
                    (long) ((exposureRange.getUpper() - exposureRange.getLower()) * (progress / 5000f));
            runOnUiThread(() -> {
                seekBarIso.setProgress(0);
                seekBarExposure.setProgress(0);
                tvIsoLabel.setText("—");
                tvExposureLabel.setText("—");
            });
            applyCamera2Options();
        }
        // GL 亮度增益：0→0.5x，5000→2.0x
        float glBrightness = 0.5f + (progress / 5000f) * 1.5f;
        cameraRenderer.setBrightness(glBrightness);
        String gainStr = String.format("×%.1f", glBrightness);
        runOnUiThread(() -> tvBrightnessLabel.setText(gainStr));
        updateBrightnessDisplay1(progress);
    }

    private void updateBrightnessDisplay() {
        if (isoRange == null || exposureRange == null) return;
        double E = currentIso * (currentExposure / 1_000_000_000.0);
        runOnUiThread(() -> tvBrightnessValue.setText(String.format("E = %.2f", E)));
    }

    private void updateBrightnessDisplay1(int progress) {
        if (isoRange == null || exposureRange == null) return;
        long exp = exposureRange.getLower() +
                (long) ((exposureRange.getUpper() - exposureRange.getLower()) * (progress / 5000f));
        int iso = isoRange.getLower() +
                (int) ((isoRange.getUpper() - isoRange.getLower()) * (progress / 5000f));
        double E = iso * (exp / 1_000_000_000.0);
        runOnUiThread(() -> tvBrightnessValue.setText(String.format("E = %.2f", E)));
    }

    // -------------------------------------------------------------------------
    // 拍照
    // -------------------------------------------------------------------------

    private void takePicture() {
        if (imageCapture == null) return;
        imageCapture.takePicture(captureExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                processCapture(imageProxy);
                imageProxy.close();
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Capture failed", exception);
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "拍照失败", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void processCapture(ImageProxy imageProxy) {
        // 从 ImageProxy 获取 JPEG 字节
        ImageProxy.PlaneProxy plane = imageProxy.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        Bitmap originalBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (originalBitmap == null) return;

        // 保存原始 JPEG
        File dcimDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), "Camera");
        if (!dcimDir.exists()) dcimDir.mkdirs();

        File originalFile = new File(dcimDir, System.currentTimeMillis() + "_original.jpg");
        try (FileOutputStream fos = new FileOutputStream(originalFile)) {
            fos.write(bytes);
        } catch (IOException e) {
            Log.e(TAG, "保存原始图像失败", e);
        }

        // 读取 EXIF 亮度
        String exifBrightness = "N/A";
        try {
            ExifInterface exif = new ExifInterface(originalFile.getAbsolutePath());
            String val = exif.getAttribute(ExifInterface.TAG_BRIGHTNESS_VALUE);
            if (val != null) exifBrightness = val;
        } catch (IOException e) {
            exifBrightness = "读取失败";
        }

        // 生成伪彩色图像并显示
        Bitmap pseudoBitmap = createPseudoColorImage(originalBitmap, exifBrightness);
        runOnUiThread(() -> imageView.setImageBitmap(pseudoBitmap));

        // 保存伪彩色图像
        File pseudoFile = new File(dcimDir, System.currentTimeMillis() + "_pseudo.jpg");
        try (FileOutputStream fos = new FileOutputStream(pseudoFile)) {
            pseudoBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            runOnUiThread(() -> Toast.makeText(this,
                    "已保存: " + pseudoFile.getName(), Toast.LENGTH_SHORT).show());
        } catch (IOException e) {
            Log.e(TAG, "保存伪彩色图像失败", e);
        }
    }

    // -------------------------------------------------------------------------
    // 伪彩色图像生成（CPU 端，用于拍照结果展示）
    // -------------------------------------------------------------------------

    private Bitmap createPseudoColorImage(Bitmap originalBitmap, String exifBrightness) {
        int width = originalBitmap.getWidth();
        int height = originalBitmap.getHeight();

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

        // 旋转 90°
        Matrix rotateMatrix = new Matrix();
        rotateMatrix.postRotate(90);
        Bitmap rotated = Bitmap.createBitmap(pseudoBitmap, 0, 0, width, height, rotateMatrix, true);
        pseudoBitmap.recycle();
        int rw = rotated.getWidth();
        int rh = rotated.getHeight();

        // 计算平均亮度
        int[][] samplePoints = {
                {width / 4, height / 4}, {width * 3 / 4, height / 4},
                {width / 4, height * 3 / 4}, {width * 3 / 4, height * 3 / 4},
                {width / 2, height / 2}
        };
        int sumR = 0, sumG = 0, sumB = 0;
        for (int[] pt : samplePoints) {
            int px = originalBitmap.getPixel(pt[0], pt[1]);
            sumR += Color.red(px);
            sumG += Color.green(px);
            sumB += Color.blue(px);
        }
        int n = samplePoints.length;
        int avgR = sumR / n, avgG = sumG / n, avgB = sumB / n;
        double yValue = 0.299 * avgR + 0.587 * avgG + 0.114 * avgB;

        // 解析 EXIF BV
        float bv = Float.NaN;
        if (exifBrightness != null && !exifBrightness.equals("N/A") && !exifBrightness.equals("读取失败")) {
            try {
                if (exifBrightness.contains("/")) {
                    String[] parts = exifBrightness.split("/");
                    bv = Float.parseFloat(parts[0]) / Float.parseFloat(parts[1]);
                } else {
                    bv = Float.parseFloat(exifBrightness);
                }
            } catch (Exception ignored) {}
        }

        double Lcenter = Float.isNaN(bv)
                ? yValue / 255.0 * 400 + 50
                : 2.9 * Math.exp(0.729 * bv);

        // 图例
        final int legendLevels = 4;
        double delta = Lcenter * 0.25;
        double[] lThresholds = new double[legendLevels + 1];
        for (int i = 0; i <= legendLevels; i++)
            lThresholds[i] = Lcenter - delta + (2 * delta) * i / legendLevels;

        int[][] colorPairs = {
                {Color.rgb(0, 0, 0),   Color.rgb(0, 0, 255)},
                {Color.rgb(0, 0, 255), Color.rgb(0, 255, 0)},
                {Color.rgb(0, 255, 0), Color.rgb(255, 255, 0)},
                {Color.rgb(255, 255, 0), Color.rgb(255, 0, 0)}
        };

        final int legendWidth = 190;
        Bitmap finalBitmap = Bitmap.createBitmap(rw + legendWidth, rh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(finalBitmap);
        canvas.drawBitmap(rotated, 0, 0, null);
        rotated.recycle();

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(Color.BLACK);
        paint.setTextSize(32);
        canvas.drawText("亮度L (cd/m²)", rw + 18, 48, paint);

        int itemHeight = Math.max(rh / legendLevels, 50);
        paint.setTextSize(40);
        for (int i = 0; i < legendLevels; i++) {
            int y = i * itemHeight;
            LinearGradient gradient = new LinearGradient(
                    rw + 15, y + 10, rw + legendWidth - 15, y + itemHeight - 10,
                    new int[]{colorPairs[i][0], colorPairs[i][1]}, null, Shader.TileMode.CLAMP);
            paint.setShader(gradient);
            canvas.drawRect(rw + 10, y + 10, rw + legendWidth - 10, y + itemHeight - 10, paint);
            paint.setShader(null);

            String lLabel = String.format("L\n%.2f\n↓\n%.2f", lThresholds[i], lThresholds[i + 1]);
            paint.setColor(Color.BLACK);
            paint.setTextSize(40);
            float lineH = paint.getTextSize() + 8;
            String[] lines = lLabel.split("\n");
            float totalH = lines.length * lineH;
            float textY = y + (itemHeight - totalH) / 2f + lineH;
            float textX = rw + legendWidth / 2f - paint.measureText("00.00") / 2f;
            for (int j = 0; j < lines.length; j++)
                canvas.drawText(lines[j], textX, textY + j * lineH, paint);
        }

        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36);
        textPaint.setAntiAlias(true);
        textPaint.setShadowLayer(2.0f, 2, 2, Color.BLACK);
        canvas.drawText(String.format("Avg RGB: R=%d, G=%d, B=%d", avgR, avgG, avgB), 30, 60, textPaint);
        canvas.drawText(String.format("Gray = %.2f", yValue), 30, 110, textPaint);
        canvas.drawText(String.format("EXIF BV = %s", exifBrightness), 30, 170, textPaint);
        String lResult = Float.isNaN(bv) ? "L = N/A"
                : String.format("L = 2.9 × exp(0.729×BV) = %.2f", 2.9 * Math.exp(0.729 * bv));
        canvas.drawText(lResult, 30, 220, textPaint);

        return finalBitmap;
    }

    // -------------------------------------------------------------------------
    // 生命周期
    // -------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        glSurfaceView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        glSurfaceView.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
