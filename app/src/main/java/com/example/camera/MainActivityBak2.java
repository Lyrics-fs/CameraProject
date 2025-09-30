package com.example.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
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
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivityBak2 extends AppCompatActivity {
    private TextureView tv;
    private Button btn;
    private String mCameraId = "2";
    private final int RESULT_CODE_CAMERA = 1;
    private CameraDevice cameraDevice;
    private CameraCaptureSession mPreviewSession;
    private CaptureRequest.Builder mCaptureRequestBuilder, captureRequestBuilder;
    private CaptureRequest mCaptureRequest;
    private ImageReader imageReader;
    private int height = 0, width = 0;
    private Size previewSize;
    private ImageView iv;
    private TextView tvBrightnessValue;
    private SeekBar seekBarBrightness, seekBarIso, seekBarExposure;


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
        setupListeners();
    }

    private void initViews() {
        seekBarBrightness = findViewById(R.id.seekBarBrightness);
        seekBarIso = findViewById(R.id.seekBarIso);
        seekBarExposure = findViewById(R.id.seekBarExposure);
        tv = findViewById(R.id.tv);
        btn = findViewById(R.id.btn);
        iv = findViewById(R.id.iv);
        tvBrightnessValue = findViewById(R.id.tv_display_value);
    }

    private void setupListeners() {
        seekBarBrightness.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                updateBrightness(progress);
            }
        });

        seekBarIso.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                updateIso(progress);
            }
        });

        seekBarExposure.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                updateExposure(progress);
            }
        });

        btn.setOnClickListener(v -> takePicture());
        tv.setSurfaceTextureListener(surfaceTextureListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if(cameraDevice!=null) {
            stopCamera();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startCamera();
    }

    //调整亮度
    private void updateBrightness(int progress) {
        if (cameraDevice != null && mCaptureRequestBuilder != null) {
            int exposureCompensation = (progress - 50) * 2;
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation);
            updatePreview();
        }
        updateBrightnessDisplay();
    }

    //调整ISO
//    private void updateIso(int progress) {
//        if (cameraDevice != null && mCaptureRequestBuilder != null) {
//            int isoValue = progress * 400;
//            mCaptureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue);
//            updatePreview();
//        }
//        updateBrightnessDisplay();
//    }

    //调整曝光度
//    private void updateExposure(int progress) {
//        if (cameraDevice != null && mCaptureRequestBuilder != null) {
//            long exposureTime = progress * 100000L / 10;
//            mCaptureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);
//            updatePreview();
//        }
//        updateBrightnessDisplay();
//    }
    // 调整ISO
    private void updateIso(int progress) {
        try {
            if (cameraDevice != null && mCaptureRequestBuilder != null) {
                CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraId);
                Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                int isoValue = isoRange.getLower() + progress;
                mCaptureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue);
                updatePreview();
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    // 调整曝光时间
    private void updateExposure(int progress) {
        try {
            if (cameraDevice != null && mCaptureRequestBuilder != null) {
                CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraId);
                Range<Long> exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                long exposureTime = exposureRange.getLower() + progress * 1000000L; // 假设进度单位为毫秒
                mCaptureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);
                updatePreview();
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void updatePreview() {
        try {
            mPreviewSession.setRepeatingRequest(mCaptureRequestBuilder.build(), null, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void updateBrightnessDisplay() {
        int brightness = seekBarBrightness.getProgress();
        int exposure = seekBarExposure.getProgress();
        int iso = seekBarIso.getProgress();

        double exposureValue = Math.max(exposure * 100000 / 10, 1);
        double isoValue = Math.max(iso * 400, 1);

        double brightnessValue = (brightness - 50) * 2 +
                Math.log(4 / (exposureValue / 1000000)) / Math.log(2) +
                Math.log(isoValue / 100) / Math.log(2);

        tvBrightnessValue.setText("亮度：" + (int) Math.round(brightnessValue));
    }

    private TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            MainActivityBak2.this.width = width;
            MainActivityBak2.this.height = height;
            openCamera();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            stopCamera();
            return true;
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        setCameraCharacteristics(manager);
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivityBak2.this, new String[]{"android.permission.CAMERA"}, RESULT_CODE_CAMERA);
            } else {
                manager.openCamera(mCameraId, stateCallback, null);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setCameraCharacteristics(CameraManager manager) {
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraId);
//            // 检查是否支持手动ISO
//            boolean isManualIsoSupported = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES).contains(CaptureRequest.CONTROL_AE_MODE_OFF);
//
//            // 检查是否支持手动曝光时间
//            boolean isManualExposureSupported = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES).contains(CaptureRequest.CONTROL_AE_MODE_OFF);
//
//            // 根据支持情况启用/禁用SeekBar
//            seekBarIso.setEnabled(isManualIsoSupported);
//            seekBarExposure.setEnabled(isManualExposureSupported);

            // 获取ISO范围
            Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            seekBarIso.setMax(isoRange.getUpper() - isoRange.getLower());

            // 获取曝光时间范围（单位：纳秒）
            Range<Long> exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            long minExposure = exposureRange.getLower();
            long maxExposure = exposureRange.getUpper();
            seekBarExposure.setMax((int) ((maxExposure - minExposure) / 1000000)); // 转换为毫秒级进度

            //CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size largest = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
            imageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(imageAvailableListener, null);
            previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height, largest);
        } catch (CameraAccessException | NullPointerException e) {
            e.printStackTrace();
        }
    }

    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();

        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        return bigEnough.size() > 0 ? Collections.min(bigEnough, new CompareSizesByArea()) : choices[0];
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice cameraDevice) {
            MainActivityBak2.this.cameraDevice = cameraDevice;
            takePreview();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            cameraDevice.close();
            MainActivityBak2.this.cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            cameraDevice.close();
        }
    };

    private void takePreview() {
        SurfaceTexture mSurfaceTexture = tv.getSurfaceTexture();
        mSurfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface mSurface = new Surface(mSurfaceTexture);

        try {
            mCaptureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            // 关闭自动曝光
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            // 启用手动控制
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            // 设置对焦模式（原有代码）
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            mCaptureRequestBuilder.addTarget(mSurface);

            cameraDevice.createCaptureSession(Arrays.asList(mSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            try {
                                mCaptureRequest = mCaptureRequestBuilder.build();
                                mPreviewSession = session;
                                mPreviewSession.setRepeatingRequest(mCaptureRequest, null, null);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {}
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void takePicture() {
        try {
            if (cameraDevice == null) return;

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureRequestBuilder.addTarget(imageReader.getSurface());

            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

            mPreviewSession.stopRepeating();
            mPreviewSession.capture(captureRequestBuilder.build(), captureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            try {
                mPreviewSession.setRepeatingRequest(mCaptureRequest, null, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
        }
    };

    private ImageReader.OnImageAvailableListener imageAvailableListener = reader -> {
        String status = Environment.getExternalStorageState();
        if (!status.equals(Environment.MEDIA_MOUNTED)) {
            Toast.makeText(getApplicationContext(), "你的sd卡不可用。", Toast.LENGTH_SHORT).show();
            return;
        }

        Image image = reader.acquireNextImage();
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);

        String filePath = Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera/";
        String picturePath = System.currentTimeMillis() + ".jpg";
        File file = new File(filePath, picturePath);

        try {
            saveImage(file, data);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            iv.setImageBitmap(bitmap);

            Bitmap pseudoColorBitmap = createPseudoColorImage(bitmap);
            String pseudoFileName = System.currentTimeMillis() + "_pseudo.jpg";
            File pseudoFile = new File(filePath, pseudoFileName);
            savePseudoColorImage(pseudoFile, pseudoColorBitmap);

            notifyMediaScanner(file, pseudoFile);
            Toast.makeText(getApplicationContext(), "已保存原图和伪色图", Toast.LENGTH_SHORT).show();

            if (pseudoColorBitmap != null && !pseudoColorBitmap.isRecycled()) {
                pseudoColorBitmap.recycle();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            image.close();
        }
    };

    private void saveImage(File file, byte[] data) throws IOException {
        FileOutputStream fileOutputStream = new FileOutputStream(file);
        fileOutputStream.write(data);
        fileOutputStream.close();
    }

    private void savePseudoColorImage(File file, Bitmap bitmap) throws IOException {
        FileOutputStream outputStream = new FileOutputStream(file);
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
        outputStream.close();
    }

    private void notifyMediaScanner(File... files) {
        for (File file : files) {
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)));
        }
    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void onRequestPermissionsResult(int permsRequestCode, String[] permissions, int[] grantResults) {
        if (permsRequestCode == RESULT_CODE_CAMERA) {
            boolean cameraAccepted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (cameraAccepted) {
                openCamera();
            } else {
                Toast.makeText(this, "请开启相机权限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startCamera() {
        if (tv.isAvailable()) {
            if (cameraDevice == null) {
                openCamera();
            }
        } else {
            tv.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    private void stopCamera() {
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private Bitmap createPseudoColorImage(Bitmap originalBitmap) {
        int width = originalBitmap.getWidth();
        int height = originalBitmap.getHeight();
        Bitmap pseudoColorBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(pseudoColorBitmap);

        ColorMatrix grayMatrix = new ColorMatrix();
        grayMatrix.setSaturation(0);
        Paint grayPaint = new Paint();
        grayPaint.setColorFilter(new ColorMatrixColorFilter(grayMatrix));
        canvas.drawBitmap(originalBitmap, 0, 0, grayPaint);

        int[] pixels = new int[width * height];
        pseudoColorBitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int gray = Color.red(pixel);
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
                int red = 255;
                int green = (gray - 192) * 4;
                color = Color.rgb(red, green, 0);
            }

            pixels[i] = color;
        }

        pseudoColorBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return pseudoColorBitmap;
    }

    private abstract class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}