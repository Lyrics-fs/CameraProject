package com.example.camera;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.opengl.Matrix;

public class CameraRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "CameraRenderer";

    // 顶点着色器：使用 SurfaceTexture 变换矩阵修正方向
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
            "}\n";

    // 片段着色器：与 CameraModel.createPseudoColorImage 对齐（luma*gain → 全局拉伸 Hue → 分段伪彩；当前离线 s=1 全饱和）
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform float uBrightness;\n" +
            "uniform float uHueMin;\n" +
            "uniform float uHueMax;\n" +
            "void main() {\n" +
            "    vec4 color = texture2D(uTexture, vTexCoord);\n" +
            "    float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));\n" +
            "    float boosted = clamp(luma * uBrightness, 0.0, 1.0);\n" +
            "    float hr = clamp((boosted - uHueMin) / max(uHueMax - uHueMin, 1e-4), 0.0, 1.0);\n" +
            "    float g = hr * 255.0;\n" +
            "    vec3 pseudo;\n" +
            "    if (g < 64.0) {\n" +
            "        pseudo = vec3(0.0, 0.0, g * 4.0 / 255.0);\n" +
            "    } else if (g < 128.0) {\n" +
            "        float t = (g - 64.0) * 4.0;\n" +
            "        pseudo = vec3(0.0, t / 255.0, (255.0 - t) / 255.0);\n" +
            "    } else if (g < 192.0) {\n" +
            "        float t = (g - 128.0) * 4.0;\n" +
            "        pseudo = vec3(t / 255.0, (255.0 - t) / 255.0, 0.0);\n" +
            "    } else {\n" +
            "        float t = (g - 192.0) * 4.0;\n" +
            "        pseudo = vec3(1.0, t / 255.0, 0.0);\n" +
            "    }\n" +
            "    gl_FragColor = vec4(pseudo, 1.0);\n" +
            "}\n";

    // 全屏四边形顶点（NDC坐标，运行时根据宽高比动态更新）
    private float[] vertices = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f,
    };

    // 纹理坐标（标准 [0,1] 范围，方向由 uTexMatrix 处理）
    private static final float[] TEX_COORDS = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
    };

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    private int program;
    private int aPositionHandle;
    private int aTexCoordHandle;
    private int uTextureHandle;
    private int uBrightnessHandle;
    private int uTexMatrixHandle;
    private int uHueMinHandle;
    private int uHueMaxHandle;

    // SurfaceTexture 变换矩阵（修正方向）
    private final float[] texMatrix = new float[16];

    // view 和相机的宽高比（用于 centerCrop）
    private volatile float viewAspect = 1.0f;
    private volatile float cameraAspect = 4f / 3f; // 默认 4:3，bindCameraPreview 后更新
    private volatile boolean aspectDirty = false;
    private volatile float hueMin = 0f;
    private volatile float hueMax = 1f;

    private int[] cameraTextureId = new int[1];
    private SurfaceTexture surfaceTexture;

    private OnSurfaceTextureAvailableListener listener;
    private OnFrameAvailableListener frameAvailableListener;
    private volatile float brightness = 1.0f; // 亮度增益，由主线程设置

    public interface OnSurfaceTextureAvailableListener {
        void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture);
    }

    public interface OnFrameAvailableListener {
        void onFrameAvailable();
    }

    public void setOnSurfaceTextureAvailableListener(OnSurfaceTextureAvailableListener l) {
        this.listener = l;
    }

    public void setOnFrameAvailableListener(OnFrameAvailableListener listener) {
        this.frameAvailableListener = listener;
    }

    public void setBrightness(float brightness) {
        this.brightness = brightness;
    }

    /** 由 MainActivity 在 bindCameraPreview 后调用，传入相机分辨率 */
    public void setCameraAspect(int camWidth, int camHeight) {
        // 后置相机传感器通常是横向的，竖屏时实际输出宽高需交换
        cameraAspect = (float) camWidth / camHeight;
        aspectDirty = true;
    }

    /** 与离线伪彩一致：对 boosted luma 做全局 min/max 拉伸后再映射 Hue（由分析线程传入） */
    public void setPseudoHueRange(float minBoostedN, float maxBoostedN) {
        if (!Float.isFinite(minBoostedN) || !Float.isFinite(maxBoostedN)) return;
        if (maxBoostedN <= minBoostedN + 1e-4f) {
            hueMin = 0f;
            hueMax = 1f;
            return;
        }
        hueMin = Math.max(0f, Math.min(1f, minBoostedN));
        hueMax = Math.max(0f, Math.min(1f, maxBoostedN));
        if (hueMax <= hueMin + 1e-4f) {
            hueMin = 0f;
            hueMax = 1f;
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);

        // 创建 OES 外部纹理（用于接收相机帧）
        GLES20.glGenTextures(1, cameraTextureId, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // 创建 SurfaceTexture 并通知主线程
        surfaceTexture = new SurfaceTexture(cameraTextureId[0]);
        surfaceTexture.setOnFrameAvailableListener(st -> {
            if (frameAvailableListener != null) {
                frameAvailableListener.onFrameAvailable();
            }
        });
        if (listener != null) {
            listener.onSurfaceTextureAvailable(surfaceTexture);
        }

        // 编译着色器程序
        program = ShaderUtils.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPositionHandle   = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoordHandle   = GLES20.glGetAttribLocation(program, "aTexCoord");
        uTextureHandle    = GLES20.glGetUniformLocation(program, "uTexture");
        uBrightnessHandle = GLES20.glGetUniformLocation(program, "uBrightness");
        uTexMatrixHandle  = GLES20.glGetUniformLocation(program, "uTexMatrix");
        uHueMinHandle = GLES20.glGetUniformLocation(program, "uHueMin");
        uHueMaxHandle = GLES20.glGetUniformLocation(program, "uHueMax");

        // 初始化单位矩阵
        Matrix.setIdentityM(texMatrix, 0);

        // 准备顶点缓冲区
        vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(vertices).position(0);

        texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer.put(TEX_COORDS).position(0);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        viewAspect = (float) width / height;
        aspectDirty = true;
    }

    /** 根据 view 和相机宽高比计算 centerCrop 顶点坐标 */
    private void updateVerticesIfNeeded() {
        if (!aspectDirty) return;
        aspectDirty = false;

        float scaleX = 1.0f, scaleY = 1.0f;
        if (viewAspect > cameraAspect) {
            // view 比相机更宽：上下裁剪 → 缩小 Y 方向顶点
            scaleY = cameraAspect / viewAspect;
        } else {
            // view 比相机更窄：左右裁剪 → 缩小 X 方向顶点
            scaleX = viewAspect / cameraAspect;
        }

        vertices[0] = -scaleX; vertices[1] = -scaleY;
        vertices[2] =  scaleX; vertices[3] = -scaleY;
        vertices[4] = -scaleX; vertices[5] =  scaleY;
        vertices[6] =  scaleX; vertices[7] =  scaleY;

        vertexBuffer.put(vertices).position(0);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (program == 0) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }

        // 更新相机帧到 OES 纹理，并获取方向变换矩阵
        if (surfaceTexture != null) {
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(texMatrix);
        }

        // 按需更新顶点（宽高比校正）
        updateVerticesIfNeeded();

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        // 绑定 OES 纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId[0]);
        GLES20.glUniform1i(uTextureHandle, 0);

        // 传递亮度增益
        GLES20.glUniform1f(uBrightnessHandle, brightness);
        GLES20.glUniform1f(uHueMinHandle, hueMin);
        GLES20.glUniform1f(uHueMaxHandle, hueMax);

        // 传递 SurfaceTexture 变换矩阵（修正相机方向）
        GLES20.glUniformMatrix4fv(uTexMatrixHandle, 1, false, texMatrix, 0);

        // 传递顶点坐标
        GLES20.glEnableVertexAttribArray(aPositionHandle);
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        // 传递纹理坐标
        GLES20.glEnableVertexAttribArray(aTexCoordHandle);
        GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        // 绘制全屏四边形
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPositionHandle);
        GLES20.glDisableVertexAttribArray(aTexCoordHandle);
    }

    public SurfaceTexture getSurfaceTexture() {
        return surfaceTexture;
    }

    /**
     * Must be called on GL thread to release GPU resources safely.
     */
    public void release() {
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }

        if (cameraTextureId[0] != 0) {
            GLES20.glDeleteTextures(1, cameraTextureId, 0);
            cameraTextureId[0] = 0;
        }

        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }

        listener = null;
        frameAvailableListener = null;
    }
}
