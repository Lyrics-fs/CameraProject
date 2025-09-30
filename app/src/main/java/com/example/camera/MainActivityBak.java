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
import android.util.Log;
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


public class MainActivityBak extends AppCompatActivity {
    private TextureView tv; // 用于显示相机预览的 TextureView
    private Button btn; // 拍照按钮
    private String mCameraId = "2"; // 摄像头 ID（通常 "0" 代表后置摄像头，"1" 代表前置摄像头）
    private final int RESULT_CODE_CAMERA = 1; // 请求相机权限的标识码
    private CameraDevice cameraDevice; // 当前打开的相机设备
    private CameraCaptureSession mPreviewSession; // 预览会话
    private CaptureRequest.Builder mCaptureRequestBuilder, captureRequestBuilder; // 捕获请求构建器
    private CaptureRequest mCaptureRequest; // 捕获请求
    private ImageReader imageReader; // 用于读取图像数据的 ImageReader
    private int height = 0, width = 0; // 预览尺寸
    private Size previewSize; // 最佳预览尺寸
    private ImageView iv; // 显示拍摄照片的 ImageView
    private TextView tvBrightnessValue; // 用于显示亮度计算值的 TextView
    private SeekBar seekBarBrightness, seekBarIso, seekBarExposure;

    /**
     * 定义屏幕旋转方向与 JPEG 方向的映射关系。
     */
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
        // 初始化数据
        seekBarBrightness = findViewById(R.id.seekBarBrightness);
        seekBarIso = findViewById(R.id.seekBarIso);
        seekBarExposure = findViewById(R.id.seekBarExposure);

        // 初始化视图组件
        tv = findViewById(R.id.tv);
        btn = findViewById(R.id.btn);
        iv = findViewById(R.id.iv);
        tvBrightnessValue = findViewById(R.id.tv_display_value); // 获取 TextView

//第三行亮度
        SeekBar seekBarBrightness = findViewById(R.id.seekBarBrightness);
        seekBarBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateBrightness(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 可选操作
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 可选操作
            }
        });

//第二行ISO
        SeekBar seekBarIso = findViewById(R.id.seekBarIso);
        seekBarIso.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateIso(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 可选操作
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 可选操作
            }
        });

//第一行曝光
        SeekBar seekBarExposure = findViewById(R.id.seekBarExposure);
        seekBarExposure.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateExposure(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 可选操作
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 可选操作
            }
        });

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takePicture();
            }
        });
        tv.setSurfaceTextureListener(surfaceTextureListener);
    }

    //用户离开界面时停止摄像机释放资源
    @Override
    protected void onPause() {
        super.onPause();
        if(cameraDevice!=null) {
            stopCamera();
        }
    }

    //回到界面时间启用摄像机
    @Override
    protected void onResume() {
        super.onResume();
        startCamera();
    }


    /**
    *四个update函数
    */

    private void updateBrightness(int progress) {
        if (cameraDevice != null && mCaptureRequestBuilder != null) {
            // 设置曝光补偿值
            int exposureCompensation = (progress - 50) * 2; // 假设进度条范围是0-100，对应曝光补偿值-100到100
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation);//用mCaptureRequestBuilder的方法去调相机的的曝光补偿值

            // 更新预览请求
            try {
                mPreviewSession.setRepeatingRequest(mCaptureRequestBuilder.build(), null, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
        //日志打印
        Log.d("Camera", "Setting brightness: " + progress);
        updateBrightnessDisplay();
    }

    private void updateIso(int progress) {
        if (cameraDevice != null && mCaptureRequestBuilder != null) {
            // 设置ISO值
            int isoValue = (int) (progress * 400); // 假设进度条范围是0-100，对应ISO值0-400
            mCaptureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue);//与调整曝光值的一样调用了mCaptureRequestBuilder

            // 更新预览请求
            try {
                mPreviewSession.setRepeatingRequest(mCaptureRequestBuilder.build(), null, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
        //日志打印
        Log.d("Camera", "Setting ISO: " + progress);
        updateBrightnessDisplay();
    }


    private void updateExposure(int progress) {
        if (cameraDevice != null && mCaptureRequestBuilder != null) {
            // 设置曝光时间
            long exposureTime = (long) (progress * 100000/10); // 假设进度条范围是0-100，对应曝光时间0-1000000微秒
            mCaptureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);

            // 更新预览请求
            try {
                mPreviewSession.setRepeatingRequest(mCaptureRequestBuilder.build(), null, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
        //日志打印
        Log.d("Camera", "Setting exposure time: " + progress + " microseconds");
        updateBrightnessDisplay();
    }

    private void updateBrightnessDisplay() {
        int brightness = seekBarBrightness.getProgress();//获取此时调整的亮度值
        int exposure = seekBarExposure.getProgress();//获取此时调整的曝光度
        int iso = seekBarIso.getProgress();//获取此时调整的ISO

        // 使用Math.max避免曝光和ISO为零
        double exposureValue = Math.max(exposure * 100000 / 10, 1); // 确保曝光时间不为零
        double isoValue = Math.max(iso * 400, 1); // 确保ISO值不为零

        // 计算亮度
        double brightnessValue = (brightness - 50) * 2 +
                Math.log(4 / (exposureValue / 1000000)) / Math.log(2) +
                Math.log(isoValue / 100) / Math.log(2);

        Log.d("Camera", "Brightness: " + brightness + ", Exposure: " + exposure + ", ISO: " + iso);
        Log.d("Camera", "Calculated brightness value: " + brightnessValue);

        // 取整并更新 TextView
        int finalBrightnessValue = (int) Math.round(brightnessValue);
        tvBrightnessValue.setText("亮度：" + finalBrightnessValue);
    }



    /**TextureView的监听*/
    private TextureView.SurfaceTextureListener surfaceTextureListener= new TextureView.SurfaceTextureListener() {

        // 当SurfaceTexture可用时调用，通常用于初始化需要SurfaceTexture的资源
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            // 更新MainActivity实例中的width和height字段，用于后续的图像处理或界面布局
            MainActivityBak.this.width=width;//摄像头画面的宽度
            MainActivityBak.this.height=height;//摄像头画面的高度
            // 打开摄像头，这通常意味着摄像头预览即将开始显示在界面上
            openCamera();
        }




        //SurfaceTexture尺寸大小变化时调用
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        //释放
        //SurfaceTexture被销毁时调用
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            stopCamera();
            return true;
        }

        //SurfaceTexture更新时调用
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    /**
     * 打开摄像头
     * 此方法负责初始化摄像头管理器，检查摄像头权限，并在权限允许的情况下打开摄像头
     */
    private void openCamera() {
        // getSystemService(Context.CAMERA_SERVICE) 获取系统的摄像头管理服务，manager是CameraManager的实例用于管理关于摄像头的操作
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        //调用 setCameraCharacteristics 方法来获取并设置与摄像头相关的特性
        setCameraCharacteristics(manager);
        try {
            // 检查应用是否具有摄像头权限
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                //提示用户进行危险权限授权
                String[] perms = {"android.permission.CAMERA"};
                //如果没有摄像头权限，就通过 requestPermissions 方法请求权限
                ActivityCompat.requestPermissions(MainActivityBak.this,perms, RESULT_CODE_CAMERA);
            }else {
                // 具有权限，打开摄像头
                manager.openCamera(mCameraId, stateCallback, null);
            }

        } catch (CameraAccessException e){
            // 捕获摄像头访问异常
            e.printStackTrace();
            //printStackTrace()会将异常的详细信息输出到控制台
        }
    }



    /**设置摄像头的参数*/
    private void setCameraCharacteristics(CameraManager manager)
    {
        try
        {
            // 通过manager.getCameraCharacteristics(mCameraId)获取指定摄像头的特性信息并存储在characteristics变量中
            CameraCharacteristics characteristics
                    = manager.getCameraCharacteristics(mCameraId);
            // 通过characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)获取摄像头支持的配置属性
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            // 获取摄像头支持的最大尺寸
            Size largest = Collections.max(
                    Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),new CompareSizesByArea());
            // 创建一个ImageReader对象，用于获取摄像头的图像数据
            imageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                    ImageFormat.JPEG, 2);
            //设置获取图片的监听，当有图像数据可用时触发imageAvailableListener 监听器的回调方法
            imageReader.setOnImageAvailableListener(imageAvailableListener,null);
            // 获取最佳的预览尺寸
            previewSize = chooseOptimalSize(map.getOutputSizes(
                    SurfaceTexture.class), width, height, largest);
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
        catch (NullPointerException e)
        {
        }
    }
    /**
     * 选择最优的分辨率大小
     * 该方法旨在从一系列可用的分辨率选项中，选择出最适合预览的分辨率
     * 它会优先选择那些分辨率大于预览Surface，并且保持所需纵横比的选项
     *
     * @param choices 摄像头支持的分辨率选项数组
     * @param width 预览Surface的宽度
     * @param height 预览Surface的高度
     * @param aspectRatio 期望的纵横比
     * @return 返回最优的分辨率大小，如果没有找到完全匹配的，返回第一个可用选项
     */
    private static Size chooseOptimalSize(Size[] choices
            , int width, int height, Size aspectRatio)
    {
        // 收集摄像头支持的大过预览Surface的分辨率并储存在bigEnough里
        List<Size> bigEnough = new ArrayList<>();
        // 获取期望的纵横比的宽度和高度
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        // 遍历所有可用的分辨率选项
        for (Size option : choices)
        {
            // 检查当前选项是否满足条件：保持纵横比并且大于预览Surface的尺寸
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height)
            {
                // 如果有满足条件，将option添加到bigEnough候选列表中
                bigEnough.add(option);
            }
        }
        // 如果找到多个预览尺寸，获取其中面积最小的
        if (bigEnough.size() > 0)
        {
            return Collections.min(bigEnough, new CompareSizesByArea());
        }
        else
        {
            //如果没有合适的预览尺寸，返回第一个可用选项
            return choices[0];
        }
    }



    // 为Size定义一个比较器Comparator
    static class CompareSizesByArea implements Comparator<Size>
    {
        @Override
        public int compare(Size lhs, Size rhs)
        {
            // 强转为long保证不会发生溢出
            //使用 Long.signum() 方法：
            //Long.signum() 方法用于确定一个 long 值的符号。如果面积差为正数，返回 1，表示 lhs 的面积大于 rhs 的面积。如果面积差为负数，返回 -1，表示 lhs 的面积小于 rhs 的面积。如果面积差为 0，返回 0，表示 lhs 和 rhs 的面积相等。
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }



    /**摄像头状态的监听*/
    private CameraDevice.StateCallback stateCallback = new CameraDevice. StateCallback()
    {
        // 摄像头被打开时触发该方法
        @Override
        public void onOpened(CameraDevice cameraDevice){
            MainActivityBak.this.cameraDevice = cameraDevice;
            // 开始预览
            takePreview();
        }

        // 摄像头断开连接时触发该方法
        @Override
        public void onDisconnected(CameraDevice cameraDevice)
        {
            MainActivityBak.this.cameraDevice.close();//释放资源
            MainActivityBak.this.cameraDevice = null;

        }
        // 打开摄像头出现错误时触发该方法
        @Override
        public void onError(CameraDevice cameraDevice, int error)
        {
            cameraDevice.close();
        }
    };

    /**
     * 开始预览
     */
    private void takePreview() {
        SurfaceTexture mSurfaceTexture = tv.getSurfaceTexture();
        //设置TextureView的缓冲区的大小设置为previewSize的宽度和高度
        mSurfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        //获取Surface显示预览数据
        Surface mSurface = new Surface(mSurfaceTexture);
        try {
            //创建预览请求
            mCaptureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            // 设置自动对焦模式
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            //设置Surface作为预览数据的显示界面
            mCaptureRequestBuilder.addTarget(mSurface);
            //创建相机捕获会话，第一个参数是捕获数据的输出Surface列表，第二个参数是CameraCaptureSession的状态回调接口，当它创建好后会回调onConfigured方法，第三个参数用来确定Callback在哪个线程执行，为null的话就在当前线程执行
            cameraDevice.createCaptureSession(Arrays.asList(mSurface,imageReader.getSurface()),new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    try {
                        //开始预览
                        mCaptureRequest = mCaptureRequestBuilder.build();// 构建最终的CaptureRequest对象
                        mPreviewSession = session;//存储创建好的 CameraCaptureSession对象，以便后续操作
                        //设置反复捕获数据的请求，这样预览界面就会一直有数据显示
                        mPreviewSession.setRepeatingRequest(mCaptureRequest, null, null);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }

                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {

                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }


    /**拍照*/
    private void takePicture()
    {
        try
        {
            if (cameraDevice == null)
            {
                return;
            }
            // 创建拍照请求
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            // 设置自动对焦模式
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            // 将imageReader的surface设为目标，这样拍摄的照片会被存储在imageReader中
            captureRequestBuilder.addTarget(imageReader.getSurface());
            // 获取设备方向
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            // 根据设备方向计算设置照片的方向
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION
                    , ORIENTATIONS.get(rotation));
            // 停止连续取景，避免在拍照瞬间还在更新预览画面
            mPreviewSession.stopRepeating();
            //拍照，captureRequestBuilder.build()构建最终的CaptureRequest对象
            CaptureRequest captureRequest = captureRequestBuilder.build();
            //设置拍照监听
            mPreviewSession.capture(captureRequest,captureCallback, null);
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }

    /**监听拍照结果*/
    private CameraCaptureSession.CaptureCallback captureCallback= new CameraCaptureSession.CaptureCallback()
    {
        // 拍照成功
        @Override
        public void onCaptureCompleted(CameraCaptureSession session,CaptureRequest request,TotalCaptureResult result)
        {
            // 重设自动对焦模式
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            // 设置自动曝光模式

            try {
                //重新进行预览
                mPreviewSession.setRepeatingRequest(mCaptureRequest, null, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);//调用父类的onCaptureFailed将失败的处理交给父类
        }
    };

    /**监听拍照的图片*/
    private ImageReader.OnImageAvailableListener imageAvailableListener= new ImageReader.OnImageAvailableListener()
    {
        // 当照片数据可用时激发该方法
        @Override
        public void onImageAvailable(ImageReader reader) {

            //使用Environment.getExternalStorageState()检查SD卡的状态
            String status = Environment.getExternalStorageState();
            //如果存储状态不等于Environment.MEDIA_MOUNTED表示SD卡不可用
            if (!status.equals(Environment.MEDIA_MOUNTED)) {
                Toast.makeText(getApplicationContext(), "你的sd卡不可用。", Toast.LENGTH_SHORT).show();
                return;
            }
            // 获取捕获的照片数据
            Image image = reader.acquireNextImage();//reader.acquireNextImage()从ImageReader中获取下一个可用的图像
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();//通过image.getPlanes()[0].getBuffer()获取图像的字节缓冲区
            byte[] data = new byte[buffer.remaining()];//创建一个字节数组，大小为缓冲区中剩余的字节数
            buffer.get(data);//将缓冲区中的数据复制到 data 数组中

            //手机拍照都是存到这个路径
            /**注意这个地方存储，有的手机相册不会识别，解决办法是手动找目录*/
            String filePath = Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera/";//确定存储图像的路径
            String picturePath = System.currentTimeMillis() + ".jpg";//生成一个以当前时间戳命名的文件名，确保文件名的唯一性
            File file = new File(filePath, picturePath);
            
            try {
                //存到本地相册
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                fileOutputStream.write(data);//使用FileOutputStream将图像数据字节数组写入文件
                fileOutputStream.close();

                //预览已拍的图片
                //创建 BitmapFactory.Options 并设置 inSampleSize = 2
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);//将字节数组转换为Bitmap对象
                iv.setImageBitmap(bitmap);//将生成的Bitmap显示在iv上，实现图像的预览。
                
                // 创建并保存伪色图
                Bitmap pseudoColorBitmap = createPseudoColorImage(bitmap);
                
                // 为伪色图生成文件名，添加"_pseudo"后缀
                String pseudoFileName = System.currentTimeMillis() + "_pseudo.jpg";
                File pseudoFile = new File(filePath, pseudoFileName);
                
                // 保存伪色图
                FileOutputStream pseudoOutputStream = new FileOutputStream(pseudoFile);
                pseudoColorBitmap.compress(Bitmap.CompressFormat.JPEG, 100, pseudoOutputStream);
                pseudoOutputStream.close();
                
                // 通知系统媒体库更新，使得新保存的图片能够在相册中显示
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, 
                        Uri.fromFile(file)));
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, 
                        Uri.fromFile(pseudoFile)));
                
                // 显示提示信息
                Toast.makeText(getApplicationContext(), "已保存原图和伪色图", Toast.LENGTH_SHORT).show();
                
                // 回收位图，释放内存
                if (pseudoColorBitmap != null && !pseudoColorBitmap.isRecycled()) {
                    pseudoColorBitmap.recycle();
                }
                
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                image.close();//关闭图像资源，确保资源的正确释放
            }
        }
    };

    /**
     * 处理请求权限结果的方法
     * 当用户在应用内请求权限并作出响应时，该方法会被调用
     * 此方法用于处理用户对相机权限请求的响应
     */
    @SuppressLint("MissingSuperCall")//继承父类方法
    @Override
    public void onRequestPermissionsResult(int permsRequestCode, String[] permissions, int[] grantResults){
        //根据请求码判断用户授权的权限类型
        switch(permsRequestCode){
            case RESULT_CODE_CAMERA:
                //判断相机权限是否被授权
                //检查用户是否授予了相机权限。grantResults 数组包含了用户对权限请求的响应，PackageManager.PERMISSION_GRANTED 表示权限已授予
                boolean cameraAccepted = grantResults[0]==PackageManager.PERMISSION_GRANTED;
                if(cameraAccepted){
                    //授权成功之后，调用系统相机进行拍照操作等
                    openCamera();
                }else{
                    //未授权提醒
                    Toast.makeText(MainActivityBak.this,"请开启相机权限",Toast.LENGTH_SHORT).show();
                }
                break;
        }
    }


    /**启动拍照*/
    private void startCamera(){
        //检查tv是否可用
        if (tv.isAvailable()) {
            if(cameraDevice==null) {
                openCamera();
            }
        } else {
            tv.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    /**
     * 停止拍照释放资源
     * 此方法用于在不再需要使用相机时停止相机预览并释放相关资源
     * 防止资源泄露，确保其他应用程序可以使用相机
     */
    private void stopCamera(){
        // 检查cameraDevice是否不为空，以避免重复释放或尝试释放未初始化的资源
        if(cameraDevice!=null){
            cameraDevice.close(); // 关闭相机设备，释放相机资源
            cameraDevice=null;   // 将cameraDevice设置为null，表示当前没有正在使用的相机设备
        }
    }

    /**
     * 创建伪色图
     * @param originalBitmap 原始图像
     * @return 处理后的伪色图
     */
    private Bitmap createPseudoColorImage(Bitmap originalBitmap) {
        int width = originalBitmap.getWidth();
        int height = originalBitmap.getHeight();
        
        // 创建一个新的位图，与原图大小相同
        Bitmap pseudoColorBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(pseudoColorBitmap);
        
        // 先转为灰度图
        ColorMatrix grayMatrix = new ColorMatrix();
        grayMatrix.setSaturation(0); // 设置饱和度为0，变成灰度图
        Paint grayPaint = new Paint();
        grayPaint.setColorFilter(new ColorMatrixColorFilter(grayMatrix));
        
        // 绘制灰度图
        canvas.drawBitmap(originalBitmap, 0, 0, grayPaint);
        
        // 转为伪色图
        int[] pixels = new int[width * height];
        pseudoColorBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int gray = Color.red(pixel); // 灰度值
            
            // 根据亮度分级着色
            int color;
            if (gray < 64) { // 最暗部分
                color = Color.rgb(0, 0, gray * 4); // 蓝色
            } else if (gray < 128) { // 稍暗部分
                int blue = 255 - (gray - 64) * 4;
                int green = (gray - 64) * 4;
                color = Color.rgb(0, green, blue); // 青色
            } else if (gray < 192) { // 稍亮部分
                int green = 255 - (gray - 128) * 4;
                int red = (gray - 128) * 4;
                color = Color.rgb(red, green, 0); // 黄色
            } else { // 最亮部分
                int red = 255;
                int green = (gray - 192) * 4;
                color = Color.rgb(red, green, 0); // 红色到黄色
            }
            
            pixels[i] = color;
        }
        
        pseudoColorBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return pseudoColorBitmap;
    }

}
