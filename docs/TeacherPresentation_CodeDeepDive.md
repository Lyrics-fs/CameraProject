# 教师汇报：核心代码深度解读

本文档与仓库当前实现一致。模板中部分文件名/方法名与工程不符处，已在各节**如实标注**（例如：指数拟合在 `CalibrationFitter`；自动微调核心为 `maybeAutoApplyFromAnalyzer`；片元着色器**无** `texelSize`、**无** 3×3 邻域采样；标定**无** R² 判定等）。

---

### 【1. 响应曲线指数拟合（对数线性最小二乘）】

**文件位置**：`app/src/main/java/com/example/camera/model/calibration/CalibrationFitter.java`

**核心代码**：

```java
public static CurveParams fitExpCurve(List<CalibrationSample> samples, int minSamples, double minBvSpread) {
    List<CalibrationSample> valid = new ArrayList<>();
    for (CalibrationSample sample : samples) {
        if (sample.valid && sample.referenceL > 0.0 && Double.isFinite(sample.bv)) {
            valid.add(sample);
        }
    }
    if (valid.size() < minSamples) return null;

    double minBv = Double.POSITIVE_INFINITY;
    double maxBv = Double.NEGATIVE_INFINITY;
    for (CalibrationSample s : valid) {
        minBv = Math.min(minBv, s.bv);
        maxBv = Math.max(maxBv, s.bv);
    }
    if (maxBv - minBv < minBvSpread) return null;

    double sumX = 0.0;
    double sumY = 0.0;
    for (CalibrationSample s : valid) {
        sumX += s.bv;
        sumY += Math.log(s.referenceL);
    }
    double meanX = sumX / valid.size();
    double meanY = sumY / valid.size();

    double numerator = 0.0;
    double denominator = 0.0;
    for (CalibrationSample s : valid) {
        double x = s.bv - meanX;
        double y = Math.log(s.referenceL) - meanY;
        numerator += x * y;
        denominator += x * x;
    }
    if (denominator < 1e-8) return null;

    double b = numerator / denominator;
    double intercept = meanY - b * meanX;
    double a = Math.exp(intercept);
    if (!(a > 0.0) || !Double.isFinite(a) || !Double.isFinite(b) || Math.abs(b) > 5.0) {
        return null;
    }
    return new CurveParams(a, b, CurveParams.Source.CALIBRATED, System.currentTimeMillis());
}
```

**逐段解释**：

| 代码段 | 做了什么 | 为什么这样写 | 知识点 |
|--------|----------|--------------|--------|
| `valid` 过滤 | 只保留 `valid`、\(L>0\)、BV 有限的样本 | \(L\le 0\) 时 \(\ln L\) 无定义；无效样本会污染回归 | 指数模型定义域、数值稳定性 |
| 样本数与 BV 跨度 | `minSamples`、`maxBv-minBv` | 样本太少或 BV 几乎不变时，斜率不可辨识 | 病态最小二乘、可辨识性 |
| `sumX`/`sumY` 与 `meanX`/`meanY` | \(x=BV\)，\(y=\ln L\)，算均值 | 为**中心化**线性回归做准备 | 减少数值误差（相较直接用 \(\sum xy\) 的展开式） |
| `numerator`/`denominator` | \(\sum (x-\bar x)(y-\bar y)\) 与 \(\sum (x-\bar x)^2\) | 等价于一元线性回归斜率 \(b=\frac{\mathrm{Cov}(x,y)}{\mathrm{Var}(x)}\) | 最小二乘；与「原始形式 \(\sum x_i y_i\)」代数等价 |
| `b`、`intercept`、`a` | \(\ln L=\ln a + b\cdot BV\) → \(b\) 为斜率，\(\ln a=\) 截距，\(a=e^{\ln a}\) | 将 **\(L=a e^{b\cdot BV}\)** 线性化为 **\(\ln L=\ln a + b\cdot BV\)** | 指数取自然对数：`Math.log` 与 `exp` 互逆 |
| 合法性检查 | `denominator`、\(a,b\) 有限、`\|b\|` 上限 | 避免除零、爆炸参数 | 工程护栏 |

**为何用 `Math.log`（自然对数）**：与模型 \(e^{b\cdot BV}\) 一致；取 \(\ln\) 后得到**线性模型**，系数与 `Math.exp` 配对。若误用 \(\log_{10}\)，仅相当于整体缩放常数，但与本代码中 `Math.exp(intercept)` 的语义不一致。

**说明**：本实现未显式维护 `sumXY`、`sumX2` 变量名；数学上与「先算 \(\sum x,\sum y,\sum xy,\sum x^2\) 再代公式」等价，此处采用**均值中心化**写法以减少中间量精度损失。

---

### 【2. ImageAnalysis 测光：YUV Y 平面与 BV】

**文件位置**：`app/src/main/java/com/example/camera/model/CameraModel.java`（`analyzeFrameForAutoExposure`）；BV 换算见同文件 `calculateBVFromLuminance`。

**核心代码**：

```java
ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
ByteBuffer yBuffer = yPlane.getBuffer().duplicate();
int width = image.getWidth();
int height = image.getHeight();
int rowStride = yPlane.getRowStride();
int pixelStride = yPlane.getPixelStride();

long sumLuma = 0L;
int sampleCount = 0;
int rowStep = Math.max(1, LUMA_SAMPLE_STEP);
int colStep = Math.max(1, LUMA_SAMPLE_STEP);

for (int y = 0; y < height; y += rowStep) {
    int rowStart = y * rowStride;
    for (int x = 0; x < width; x += colStep) {
        int index = rowStart + x * pixelStride;
        if (index >= 0 && index < yBuffer.limit()) {
            sumLuma += (yBuffer.get(index) & 0xFF);
            sampleCount++;
        }
    }
}

double averageLuma = sampleCount > 0 ? (sumLuma * 1.0 / sampleCount) : 128.0;
double currentSceneBV = calculateBVFromLuminance(averageLuma);
// calculateBVFromLuminance: normalized = max(averageLuma/255, 1e-4); return log(normalized)/log(2);
double targetBV = DEFAULT_TARGET_BV;
double bvDelta = clampDouble(targetBV - currentSceneBV, -MAX_BV_STEP, MAX_BV_STEP);
double evFactor = Math.pow(2.0, bvDelta);
long recommendedExposure = clampLong((long) (baseExposure * evFactor), minExposure, maxExposure);
double remainFactor = evFactor / Math.max(1e-6, recommendedExposure * 1.0 / Math.max(1L, baseExposure));
int recommendedIso = clampInt((int) Math.round(baseIso * remainFactor), minIso, maxIso);
```

**逐段解释**：

| 代码段 | 做了什么 | 为什么这样写 | 知识点 |
|--------|----------|--------------|--------|
| `getPlanes()[0]` | 取第一平面作亮度 | CameraX `YUV_420_888` 约定 **plane0 为 Y** | `ImageProxy` 多平面布局 |
| `getBuffer().duplicate()` | 得到可独立读指针的缓冲区视图 | 避免改动 ImageProxy 内部 position；**不拷贝整帧像素** | `ByteBuffer` API |
| `rowStride` / `pixelStride` | 行可能有 padding；像素步长可能 >1 | NV21/NV12 等对齐方式下，索引必须是 `y*rowStride + x*pixelStride` | YUV stride、内存对齐 |
| `rowStep`/`colStep` | 隔行隔列采样 | 降低 CPU 负载，测光只需统计稳定均值 | 下采样近似 |
| `& 0xFF` | 将 `byte` 转无符号 0–255 | Java `byte` 有符号，直接加会错 | 位运算 |
| `calculateBVFromLuminance` | \(\log_2(\mathrm{norm})\)，norm 带下限 | 防止 \(\log(0)\)；得到场景相对亮度标量 BV | 对数、曝光「档」直觉 |
| `evFactor` 与曝光/ISO | 先乘快门，剩余倍数乘 ISO | **先尽量用曝光时间吃满 EV，再用 ISO 补**（与注释一致） | 曝光分解、clamp 到设备与安全范围 |

**配套：`ImageAnalysis` 构建与线程**（`CameraPresenter.startAutoExposureAnalysis`）：`STRATEGY_KEEP_ONLY_LATEST` + 640×480 + 单线程 `Executor`；analyzer 内 `try/finally { image.close(); }`。

---

### 【3. 自动微调状态判定与节流、手动暂停】

**文件位置**：`app/src/main/java/com/example/camera/presenter/CameraPresenter.java`

**核心代码**：

```java
public void onUserManualAdjustmentStarted() {
    manualModeUntilMs = System.currentTimeMillis() + MANUAL_MODE_HOLD_MS;
    resetAutoTuneStability();
}

public boolean isManualModeActive() {
    return System.currentTimeMillis() < manualModeUntilMs;
}

private void maybeAutoApplyFromAnalyzer(CameraModel.ExposureRecommendation recommendation) {
    if (!autoTuneState.enabled || recommendation == null || isManualModeActive() || aeLocked) return;
    double bvDelta = recommendation.targetBV - recommendation.currentSceneBV;
    if (Math.abs(bvDelta) < autoTuneState.bvDeltaThreshold) {
        resetAutoTuneStability();
        return;
    }

    int direction = bvDelta > 0 ? 1 : -1;
    if (direction == autoTuneState.lastDirection) {
        autoTuneState.stableFrameCount++;
    } else {
        autoTuneState.lastDirection = direction;
        autoTuneState.stableFrameCount = 1;
    }
    if (autoTuneState.stableFrameCount < autoTuneState.requiredStableFrameCount) return;

    long now = System.currentTimeMillis();
    if (now - autoTuneState.lastAutoApplyTs < AUTO_APPLY_MIN_INTERVAL_MS) return;
    autoTuneState.lastAutoApplyTs = now;
    resetAutoTuneStability();
    view.requestAutoApplyRecommendation();
}
```

**逐段解释**：

| 代码段 | 做了什么 | 为什么这样写 | 知识点 |
|--------|----------|--------------|--------|
| `onUserManualAdjustmentStarted` | 将 `manualModeUntilMs` 设为「现在 + 5s」并重置稳定计数 | 用户拖滑杆后**短时间禁止自动下发**，避免与人抢参数 | 防抖、UX |
| `isManualModeActive` | 与当前时间比较 | 无单独 `Timer`，用**时间戳截止**判断 | 轻量状态机 |
| 前置 `return` | 关自动、无推荐、手动窗口、AE-L | 明确互斥条件 | 守卫子句 |
| `bvDelta` 阈值 | 偏差过小则重置稳定计数并返回 | 已在目标附近则不必微调，且清除累积 | 滞回/死区 |
| `direction` 与 `stableFrameCount` | 同向连续帧累加；反向则重置为 1 | 要求**趋势稳定**再动作，抑制抖动 | 稳定帧 FSM |
| `AUTO_APPLY_MIN_INTERVAL_MS` | 两次下发至少间隔 500ms | 限制 Camera2 参数轰炸，减轻卡顿 | 节流（throttle） |
| `requestAutoApplyRecommendation` | 通知 View 走「按步进比例应用推荐」 | View/Presenter 分工：Presenter 决策**何时**，具体 ISO/快门插值在 `applyLatestExposureRecommendationWithRatio` | MVP、回调 |

---

### 【4. Camera2 Interop 手动参数下发与 `unbindAll`】

**文件位置**：`app/src/main/java/com/example/camera/MainActivity.java`

**核心代码**：

```java
private void bindCameraPreview(SurfaceTexture surfaceTexture) {
    // ...
    releasePreviewSurface();
    cameraProvider.unbindAll();

    surfaceTexture.setDefaultBufferSize(1280, 720);
    final android.view.Surface requestSurface = new android.view.Surface(surfaceTexture);
    previewSurface = requestSurface;
    boundSurfaceTexture = surfaceTexture;
    // ... Preview.SurfaceProvider provideSurface(requestSurface) ...
    camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture, imageAnalysis);
    readCameraRanges();
}

@ExperimentalCamera2Interop
private void applyCamera2Options() {
    if (camera == null) return;
    CaptureRequestOptions.Builder builder = new CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF);
    if (currentIso > 0)
        builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, currentIso);
    if (currentExposure > 0)
        builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposure);

    Camera2CameraControl.from(camera.getCameraControl())
            .setCaptureRequestOptions(builder.build());
}
```

**逐段解释**：

| 代码段 | 做了什么 | 为什么这样写 | 知识点 |
|--------|----------|--------------|--------|
| `unbindAll` + 再 `bindToLifecycle` | 解绑所有用例后重新绑定 | **换 Surface / 换 Texture 时**避免旧会话持有已释放的 `Surface`；README 亦提到防竞态 | CameraX 生命周期 |
| `CaptureRequestOptions` | 注入 `CONTROL_AE_MODE_OFF`、ISO、曝光 | 本工程**未**使用 `Preview.Builder` 的 `Camera2Interop.Extender`，而是在绑定后通过 **`Camera2CameraControl.setCaptureRequestOptions`** 统一下发 | CameraX Interop 实验 API |
| `SENSOR_SENSITIVITY` | ISO，整型，设备 `Range<Integer>` | 与 Camera2 一致 | `CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE`（在 `readCameraRanges` 读取） |
| `SENSOR_EXPOSURE_TIME` | 曝光时间，**纳秒** | Camera2 标准单位 ns | `SENSOR_INFO_EXPOSURE_TIME_RANGE` |

**为何常先 `unbindAll`**：重新预览目标（尤其 GL 重建 `SurfaceTexture`）时，旧 `Preview` 可能仍关联失效 `Surface`；解绑后重绑保证 **SurfaceProvider 与当前纹理一致**。

---

### 【5. OpenGL 片元着色器：预览伪彩（与 Java 对齐）】

**文件位置**：`app/src/main/java/com/example/camera/CameraRenderer.java`（`FRAGMENT_SHADER` 字符串常量；**非** `res/raw/*.frag`）

**核心代码**：

```glsl
uniform samplerExternalOES uTexture;
varying vec2 vTexCoord;
uniform float uBrightness;
uniform float uHueMin;
uniform float uHueMax;
void main() {
    vec4 color = texture2D(uTexture, vTexCoord);
    float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    float boosted = clamp(luma * uBrightness, 0.0, 1.0);
    float hr = clamp((boosted - uHueMin) / max(uHueMax - uHueMin, 1e-4), 0.0, 1.0);
    float g = hr * 255.0;
    vec3 pseudo;
    if (g < 64.0) {
        pseudo = vec3(0.0, 0.0, g * 4.0 / 255.0);
    } else if (g < 128.0) {
        float t = (g - 64.0) * 4.0;
        pseudo = vec3(0.0, t / 255.0, (255.0 - t) / 255.0);
    } else if (g < 192.0) {
        float t = (g - 128.0) * 4.0;
        pseudo = vec3(t / 255.0, (255.0 - t) / 255.0, 0.0);
    } else {
        float t = (g - 192.0) * 4.0;
        pseudo = vec3(1.0, t / 255.0, 0.0);
    }
    gl_FragColor = vec4(pseudo, 1.0);
}
```

**逐段解释**：

| 代码段 | 做了什么 | 为什么这样写 | 知识点 |
|--------|----------|--------------|--------|
| `samplerExternalOES` | 采样相机外部纹理 | Android 相机预览典型 **OES 纹理** | `GL_TEXTURE_EXTERNAL_OES` |
| `dot` 系数 | Rec.601 luma | 与 `CameraModel` 中 Java 伪彩一致 | 色度学 |
| `uHueMin`/`uHueMax` | 将 `boosted` 线性归一到 [0,1] 再映射到 0–255 分段索引 | 对应 Java 里「全局 min/max 拉伸」后再查伪彩表 | 直方图拉伸 |
| 四段 `if` | 蓝→青→绿→黄→红分段 | **不是** HSV 三角公式；是**查表式分段线性 RGB** | 伪彩色映射 |
| **无 `texelSize`** | — | 本工程片元着色器**未**使用邻域采样；**无**局部对比度 | 与模板假设不同 |
| **局部对比度** | 在 **Java** `CameraModel.createPseudoColorImage` 用 3×3 **max−min** | 预览为性能与一致性当前为全饱和 Hue 路径 | CPU/GPU 分工 |

若老师问「HSV 转 RGB」：应答**本预览着色器未实现通用 HSV→RGB**；色相由分段 RGB 直接给出。

---

### 【6. 拍照后 Bitmap 伪彩与图例、EXIF】

**文件位置**：`app/src/main/java/com/example/camera/model/CameraModel.java`（`createPseudoColorImage` / `addLegendAndInfo`）；EXIF 读取在 `ImageRepository.readExifBrightnessFromBytes`；保存流程在 `MainActivity.processCapture`。

**核心代码**：

```java
int[] pixels = new int[width * height];
originalBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
// ... 填充 gray[]、全局 min/max、双循环写回 pixels[]（含 3×3 对比度与分段 Hue）...
Bitmap pseudoBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
// addLegendAndInfo: Canvas canvas = new Canvas(finalBitmap);
// canvas.drawBitmap(rotatedPseudoBitmap, 0, 0, null);
// drawLegend(...); drawStatistics(...);
```

**逐段解释**：

| 要点 | 说明 |
|------|------|
| **为何 Java 再做伪彩** | 拍照结果需**落盘、叠加图例与文字、与 EXIF 解析同一管线**；预览在 GPU 实时，离线在 CPU 一次性高质量处理（含 3×3 对比度分支，见源码）。 |
| `getPixels` / `setPixels` | 一次拷出整帧 `int[]`，避免 `getPixel` 逐点 JNI 开销 | `Bitmap` 批量 API |
| 图例 | `Canvas` + `LinearGradient` 在 `drawLegend` 中绘制 | 2D 绘制、`Shader` |
| **EXIF 时机** | **读取**在 `processCapture`：对**内存 JPEG bytes** 调 `readExifBrightnessFromBytes`（`TAG_BRIGHTNESS_VALUE`）；**写入**亮度 EXIF 到保存文件：**当前 `ImageRepository.saveJpeg*` 未写入该字段**，仅压缩/写字节 | `ExifInterface` |

---

### 【7. MediaStore 写入（分区存储）】

**文件位置**：`app/src/main/java/com/example/camera/data/ImageRepository.java`

**核心代码**：

```java
private Uri insertImage(String displayName) {
    ContentValues values = new ContentValues();
    values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
    values.put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH);
    return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
}

public Uri saveJpegBytes(byte[] data, String displayName) {
    Uri uri = insertImage(displayName);
    if (uri == null) return null;
    try (OutputStream os = resolver.openOutputStream(uri)) {
        if (os == null) {
            resolver.delete(uri, null, null);
            return null;
        }
        os.write(data);
        os.flush();
        return uri;
    } catch (IOException e) {
        resolver.delete(uri, null, null);
        return null;
    }
}
```

**逐段解释**：

| 字段/API | 作用 | 说明 |
|----------|------|------|
| `DISPLAY_NAME` | 文件名 | 用户可见名 |
| `MIME_TYPE` | `image/jpeg` | 类型 |
| `RELATIVE_PATH` | `DCIM/Camera` | **Android 10+** 媒体相对路径；与公开相册习惯一致 |
| `insert` → `Uri` | 登记媒体项 | 再 `openOutputStream(uri)` 写入 |
| 失败 `delete` | 清理空项 | 避免媒体库垃圾记录 |
| **Android 9 分支** | **本文件无 SDK 分支** | `RELATIVE_PATH` 为 **API 29+**；`minSdk` 为 24，低版本行为依赖系统实现，**答辩可说明若需全版本需补兼容** |
| **为何 DCIM/Camera** | 系统相机默认相册路径 | 用户易在「相机」相册中找到 |

---

### 【8. GLSurfaceView 生命周期、纹理重建与黑屏】

**文件位置**：`app/src/main/java/com/example/camera/CameraRenderer.java`（`onSurfaceCreated`、`release`）；`app/src/main/java/com/example/camera/MainActivity.java`（`setupGLSurfaceView`、`onPause`、`onDestroy`）

**核心代码**：

```java
// CameraRenderer.onSurfaceCreated
GLES20.glGenTextures(1, cameraTextureId, 0);
GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId[0]);
// ... tex params ...
surfaceTexture = new SurfaceTexture(cameraTextureId[0]);
surfaceTexture.setOnFrameAvailableListener(st -> { /* requestRender */ });
if (listener != null) {
    listener.onSurfaceTextureAvailable(surfaceTexture);
}

// MainActivity.setupGLSurfaceView
cameraRenderer.setOnSurfaceTextureAvailableListener(surfaceTexture ->
        runOnUiThread(() -> bindCameraPreview(surfaceTexture)));

// MainActivity.onDestroy
if (cameraRenderer != null && glSurfaceView != null) {
    glSurfaceView.queueEvent(() -> cameraRenderer.release());
}
```

**`CameraRenderer.release()`（节选）**：`surfaceTexture.release()`、`glDeleteTextures`、`glDeleteProgram`。

**逐段解释**：

| 要点 | 说明 |
|------|------|
| **纹理 ID 失效** | Activity 暂停/销毁时 EGL 上下文可能被销毁，**旧 `glGenTextures` 的 ID 在新上下文中无效** | OpenGL 状态机 |
| **为何在 `onSurfaceCreated` 里 `glGenTextures`** | 保证纹理在**当前有效上下文**中创建 | GL 线程、生命周期 |
| **通知 CameraX** | `onSurfaceTextureAvailable` → 主线程 `bindCameraPreview` → 新建 `Surface` + `unbindAll` + `bindToLifecycle` | 与第 4 节联动 |
| **`onSurfaceDestroyed`** | **本类未实现 `GLSurfaceView.Renderer.onSurfaceDestroyed` 覆写**；释放集中在 `release()`，由 `onDestroy` **`queueEvent` 到 GL 线程**调用 | 避免在错误线程删 GL 对象 |
| **`setPreserveEGLContextOnPause`** | **本工程未调用** | 可保留上下文减少重建，但有**设备兼容与资源泄漏**风险；当前策略是**重建纹理与 Surface 并重绑相机** |

---

### 【9. 标定会话状态与均匀性（无 R²）】

**文件位置**：`app/src/main/java/com/example/camera/model/calibration/CalibrationSession.java`；均匀性逻辑在 `CameraPresenter.isFrameStable`。

**核心代码（会话）**：

```java
public enum Status {
    IDLE,
    INSTRUCTION,
    SAMPLING,
    FITTING,
    DONE,
    FAILED
}
private final List<CalibrationSample> samples = new ArrayList<>();
private Status status = Status.IDLE;
```

**核心代码（稳定性/“均匀性”代理）**：

```java
private boolean isFrameStable() {
    if (recentMeanY.size() < 4) return false;
    double mean = 0.0;
    for (double value : recentMeanY) mean += value;
    mean /= recentMeanY.size();
    double variance = 0.0;
    for (double value : recentMeanY) {
        double diff = value - mean;
        variance += diff * diff;
    }
    variance /= recentMeanY.size();
    return Math.sqrt(variance) <= CALIBRATION_STABILITY_STD_MAX;
}
```

**逐段解释**：

| 要点 | 说明 |
|------|------|
| 状态枚举 | `IDLE`→`SAMPLING`→`FITTING`→`DONE`/`FAILED`；`INSTRUCTION` 预留 | 会话生命周期清晰 |
| BV 动态范围 | 由 **`CalibrationFitter`** 中 **`maxBv-minBv >= minBvSpread`** 保证，非 `CalibrationSession` 内计算 | 职责分离 |
| 均匀性 | 对最近若干帧 **meanY** 算方差，标准差 ≤ 阈值则视为稳定；**不是**单帧中心 ROI 方差（中心 ROI 用于 `estimateMeanLuma` 采样） | 时间域稳定性 |
| **R²** | **工程中未实现** \(R^2\) 计算与阈值判定 | 答辩如实说明；拟合失败由 `fitExpCurve` 返回 `null` |

---

### 【10. 曝光推荐的应用：步进插值与安全范围】

**说明**：不存在名为 `calculateRecommendedParams` 的方法；**推荐 ISO/快门**在 `CameraModel.analyzeFrameForAutoExposure` 生成；**应用到硬件**时通过 `applyLatestExposureRecommendationWithRatio` 做比例收敛。

**文件位置**：`app/src/main/java/com/example/camera/presenter/CameraPresenter.java`

**核心代码**：

```java
private void applyLatestExposureRecommendationWithRatio(double ratio, boolean forcePreviewSafeRange) {
    if (lastRecommendation == null) return;
    CameraSettings settings = model.getCameraSettings();
    Range<Integer> isoRange = settings.getIsoRange();
    Range<Long> exposureRange = settings.getExposureRange();
    if (isoRange == null || exposureRange == null) return;

    double safeRatio = Math.max(0.0, Math.min(1.0, ratio));
    int smoothedIso = (int) Math.round(
            settings.getIso() + (lastRecommendation.recommendedIso - settings.getIso()) * safeRatio);
    long smoothedExposure = Math.round(
            settings.getExposureTime() + (lastRecommendation.recommendedExposureTime - settings.getExposureTime()) * safeRatio);
    smoothedIso = Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), smoothedIso));
    smoothedExposure = Math.max(exposureRange.getLower(), Math.min(exposureRange.getUpper(), smoothedExposure));
    if (forcePreviewSafeRange) {
        smoothedIso = Math.max(PREVIEW_SAFE_MIN_ISO, Math.min(PREVIEW_SAFE_MAX_ISO, smoothedIso));
        smoothedExposure = Math.max(PREVIEW_SAFE_MIN_EXPOSURE_NS,
                Math.min(PREVIEW_SAFE_MAX_EXPOSURE_NS, smoothedExposure));
    }
    // ... 写回 settings、SeekBar、applyCameraIsoParameter / Exposure ...
}
```

**逐段解释**：

| 代码段 | 做了什么 | 为什么这样写 | 知识点 |
|--------|----------|--------------|--------|
| `ratio` | 当前值向推荐值插值的比例 | 自动微调用小比例（如 0.2–0.3），一键应用可用较大平滑系数 | 凸组合、收敛 |
| 先 `isoRange`/`exposureRange` clamp | 不超过 **CameraCharacteristics 查询到的范围** | 非法请求会导致失败或无效果 | Camera2 范围 |
| `forcePreviewSafeRange` | 再限制到 `PREVIEW_SAFE_*` | 预览流畅、防极端参数 | 产品层安全区间 |

**设备范围来源**：`MainActivity.readCameraRanges` 通过 `Camera2CameraInfo.getCameraCharacteristic` 读取 `SENSOR_INFO_*_RANGE` 并 `presenter.setCameraRanges(...)`。

---

### 【11. 推荐模式 UI：实时推荐文案、一键应用、自动微调入口、AE-L】

**文件位置**：`app/src/main/java/com/example/camera/MainActivity.java`

**核心代码**：

```java
private void setupRecommendationControls() {
    btnApplyRecommendation.setOnClickListener(v -> {
        presenter.applyLatestExposureRecommendation();
        presenter.onUserManualAdjustmentStarted();
    });
    btnAutoTune.setOnClickListener(v -> {
        boolean enabled = presenter.toggleAutoTune();
        btnAutoTune.setText(enabled ? "微调:开" : "微调:关");
    });
    btnStableFrames.setOnClickListener(v -> {
        int nextValue = getNextStableFrameCount(presenter.getAutoApplyStableFrameCount());
        presenter.setAutoApplyStableFrameCount(nextValue);
        btnStableFrames.setText("帧:" + nextValue);
    });
    btnAutoStepRatio.setOnClickListener(v -> {
        int currentPercent = (int) Math.round(presenter.getAutoApplyStepRatio() * 100.0);
        int nextPercent = getNextAutoStepPercent(sanitizeAutoStepPercent(currentPercent));
        presenter.setAutoApplyStepRatio(nextPercent / 100.0);
        btnAutoStepRatio.setText("步进:" + nextPercent + "%");
    });
    btnBvThreshold.setOnClickListener(v -> {
        int currentMilli = (int) Math.round(presenter.getAutoApplyBvDeltaThreshold() * 1000.0);
        int nextMilli = getNextBvThresholdMilli(sanitizeBvThresholdMilli(currentMilli));
        presenter.setAutoApplyBvDeltaThreshold(nextMilli / 1000.0);
    });
    btnModeToggle.setOnClickListener(v -> {
        isRecommendationMode = !isRecommendationMode;
        persistControlMode();
        updateControlModeUI();
    });
    btnAELock.setOnClickListener(v -> {
        boolean locked = presenter.toggleAELock();
        btnAELock.setText(locked ? "取消锁定推荐值" : "锁定当前推荐值");
    });
}

@Override
public void onExposureRecommendationChanged(CameraModel.ExposureRecommendation recommendation) {
    String text = String.format(
            "推荐 ISO: %d, 曝光: %.2f ms, BV: %.2f",
            recommendation.recommendedIso,
            recommendation.recommendedExposureTime / 1_000_000.0,
            recommendation.currentSceneBV);
    runOnUiThread(() -> tvAutoExposureRecommendation.setText(text));
}

@Override
public void requestAutoApplyRecommendation() {
    runOnUiThread(() -> presenter.applyLatestExposureRecommendationInAutoMode());
}
```

**逐段解释**：

| 代码段 | 做了什么 | 为什么这样写 | 知识点 |
|--------|----------|--------------|--------|
| `applyLatestExposureRecommendation` | 用户点击「应用推荐值」 | 走 `APPLY_SMOOTHING`（如 0.6）向推荐值靠拢，且**非**强制预览安全范围（与自动微调区分） | 一键应用 vs 自动微调 |
| `onUserManualAdjustmentStarted` | 应用后立即进入手动优先窗口 | 防止刚应用完又被自动微调立刻改掉 | 与 README「手动优先」一致 |
| `toggleAutoTune` / 三枚高级按钮 | 开关自动微调；切换稳定帧、步进比例、BV 阈值 | 对应 README「20/25/30%」「3/4/5 帧」「BV 阈值」；值经 `SharedPreferences` 持久化（`loadRecommendationPrefs`） | 可调 FSM 参数 |
| `isRecommendationMode` | 推荐/手动模式切换 | 推荐模式下 SeekBar 拖动不直接改 ISO/曝光（见 `setupSeekBars` 中 `if (isRecommendationMode) return`） | 双模式 UX |
| `toggleAELock` | AE-L：锁定后 Presenter 不再刷新推荐到硬件 | `maybeAutoApplyFromAnalyzer` 与 `handleExposureRecommendation` 内均尊重 `aeLocked` | 曝光锁定语义 |
| `onExposureRecommendationChanged` | 仅更新 TextView | View 只负责展示；计算在 `CameraModel.analyzeFrameForAutoExposure` | MVP 边界 |
| `requestAutoApplyRecommendation` | 主线程调用 `applyLatestExposureRecommendationInAutoMode` | Presenter 在分析线程触发，**必须回到主线程**改 UI/硬件 | 线程模型 |

---

### 【12. 手动三参数：ISO、曝光、亮度增益与预览实时反馈】

**文件位置**：`app/src/main/java/com/example/camera/presenter/CameraPresenter.java`（`onIsoChanged` / `onExposureChanged` / `onBrightnessChanged`）；硬件下发见 `MainActivity.applyCameraIsoParameter` + `scheduleApplyCamera2Options`。

**核心代码**：

```java
@Override
public void onBrightnessChanged(int progress) {
    CameraSettings settings = model.getCameraSettings();
    Range<Integer> isoRange = settings.getIsoRange();
    Range<Long> exposureRange = settings.getExposureRange();
    // ... 计算 minIso/maxIso、minExposure/maxExposure（含 MANUAL_SAFE_*）...
    int iso = minIso + (int) ((maxIso - minIso) * (progress / (float) SEEK_MAX));
    long exposure = minExposure + (long) ((maxExposure - minExposure) * (progress / (float) SEEK_MAX));
    settings.setIso(iso);
    settings.setExposureTime(exposure);
    model.updateCameraSettings(settings);

    view.setSeekBarProgress(R.id.seekBarIso, 0);
    view.setSeekBarProgress(R.id.seekBarExposure, 0);
    view.updateIsoDisplay(-1);
    view.updateExposureDisplay("—");

    if (!deferHardwareApply) {
        view.applyCameraIsoParameter(iso);
        view.applyCameraExposureParameter(exposure);
    }

    float gain = 0.5f + (progress / (float) SEEK_MAX) * 1.5f;
    lastPreviewBrightnessGain = gain;
    view.setPreviewBrightness(gain);
    view.updateBrightnessMode(String.format("×%.1f", gain));
    updateExposureValueDisplay(settings);
}
```

**逐段解释**：

| 代码段 | 做了什么 | 为什么这样写 | 知识点 |
|--------|----------|--------------|--------|
| 亮度滑杆同时写 ISO/曝光 | 单滑杆映射两条轴 | 产品定义：「亮度增益」调整时**同步拉 ISO 与快门**，并清零另两条 SeekBar 的语义显示 | 耦合控制 |
| `deferHardwareApply` | 拖动中可暂缓下发 | `onStartTrackingTouch` 置 `true`，`onStopTrackingTouch` 再应用，减少卡顿 | SeekBar 交互 |
| `setPreviewBrightness` | 把增益送进 `CameraRenderer` | **实时预览**伪彩与离线 `createPseudoColorImage(..., gain)` 共用 `lastPreviewBrightnessGain` | 预览/离线一致 |
| `applyCameraIsoParameter` | 更新 `currentIso` 并 `scheduleApplyCamera2Options` | 主线程 **节流**（`postDelayed`）合并连续 `CaptureRequest` 更新 | 防抖、Camera2 负载 |

`onIsoChanged` / `onExposureChanged` 为各自单轴映射 + `applyCamera*`，并将亮度 SeekBar 置 0，与 README「精确调节 ISO、曝光时间、亮度增益」对应。

---

### 【13. 曝光量 E 与曲线亮度 L：计算与结果图叠加】

**文件位置**：`app/src/main/java/com/example/camera/model/CameraModel.java`（`calculateExposureValue`、`computeLFromBv`）；统计绘制见 `drawStatistics`。

**核心代码**：

```java
@Override
public double calculateExposureValue(int iso, long exposureTime, float aperture) {
    double exposureSeconds = exposureTime / 1_000_000_000.0;
    return (iso * exposureSeconds) / (aperture * aperture);
}

public double computeLFromBv(float bv, CurveParams params) {
    CurveParams effective = params != null && params.isValid() ? params : CurveParams.prior();
    return effective.a * Math.exp(effective.b * bv);
}

// drawStatistics（节选）
if (!Float.isNaN(bv)) {
    double L = computeLFromBv(bv, activeCurveParams);
    CurveParams curve = activeCurveParams != null ? activeCurveParams : CurveParams.prior();
    lResult = String.format("L = %.3f × exp(%.3f×BV) = %.2f", curve.a, curve.b, L);
}
```

**逐段解释**：

| 代码段 | 做了什么 | 为什么这样写 | 知识点 |
|--------|----------|--------------|--------|
| `calculateExposureValue` | \(E \propto ISO \times t / N^2\) | 界面展示「曝光量」标量，与 APEX 思想一致（口径为工程定义） | 曝光量合成 |
| `computeLFromBv` | \(L=a e^{b\cdot BV}\) | README 先验/标定曲线统一入口；无效参数回退 `CurveParams.prior()` | 指数模型 |
| `drawStatistics` | 把公式与数值画在伪彩图上 | **亮度计算与显示**：与 README「基于公式估计场景亮度」对应 | `Canvas` 文本 |

---

### 【14. EXIF 亮度（BrightnessValue）读取】

**文件位置**：`app/src/main/java/com/example/camera/data/ImageRepository.java`

**核心代码**：

```java
public String readExifBrightnessFromBytes(byte[] jpegBytes) {
    if (jpegBytes == null || jpegBytes.length == 0) return "N/A";
    try (InputStream inputStream = new ByteArrayInputStream(jpegBytes)) {
        ExifInterface exif = new ExifInterface(inputStream);
        String val = exif.getAttribute(ExifInterface.TAG_BRIGHTNESS_VALUE);
        return val != null ? val : "N/A";
    } catch (Exception ignore) {
        return "读取失败";
    }
}
```

**逐段解释**：

| 要点 | 说明 |
|------|------|
| **为何从 bytes 读** | 拍照回调已持有 JPEG 内存，**避免先写入 MediaStore 再打开文件**，降低延迟（与类注释一致） |
| `TAG_BRIGHTNESS_VALUE` | EXIF 中 APEX 亮度相关标量；可能为有理数字符串（`addLegendAndInfo` 中按 `/` 解析） | `ExifInterface` |
| **与 README** | 「读取亮度相关元数据（可用时）」；**写入**该字段到保存的 JPEG **未在本类实现** | 读/写不对称需口头说明 |

---

### 【15. 先验曲线、标定结果持久化与冷启动加载】

**文件位置**：`app/src/main/java/com/example/camera/model/calibration/CurveParams.java`；`app/src/main/java/com/example/camera/data/CalibrationRepository.java`；`app/src/main/java/com/example/camera/model/CameraModel.java`（构造）。

**核心代码**：

```java
// CurveParams.java
public static CurveParams prior() {
    return new CurveParams(PRIOR_A, PRIOR_B, Source.PRIOR, 0L);
}

// CalibrationRepository.java
public CurveParams loadCurveParams() {
    if (!sharedPreferences.contains(KEY_A) || !sharedPreferences.contains(KEY_B)) {
        return CurveParams.prior();
    }
    double a = Double.longBitsToDouble(sharedPreferences.getLong(KEY_A, ...));
    double b = Double.longBitsToDouble(sharedPreferences.getLong(KEY_B, ...));
    CurveParams params = new CurveParams(a, b, parseSource(source), updatedAt);
    return params.isValid() ? params : CurveParams.prior();
}

// CameraModel 构造（节选）
this.activeCurveParams = calibrationRepository != null
        ? calibrationRepository.loadCurveParams()
        : CurveParams.prior();
```

**逐段解释**：

| 要点 | 说明 |
|------|------|
| 先验 \(a_0,b_0\) | `PRIOR_A=2.9`、`PRIOR_B=0.729`，与 README 公式一致 | 默认物理先验 |
| `double` 存 `SharedPreferences` | 使用 `doubleToLongBits` / `longBitsToDouble` | SP 无原生 double |
| `parseSource` | 区分 `CALIBRATED` / `PRIOR`，供 UI「曲线来源」 | 枚举持久化 |
| 无效回退 | `loadCurveParams` 与 `setActiveCurveParams` 均可能回到 `prior()` | 鲁棒性 |

---

### 【16. 结果图：多点亮度采样、L 中心与亮度图例】

**文件位置**：`app/src/main/java/com/example/camera/model/CameraModel.java`（`addLegendAndInfo`、`drawLegend`）

**核心代码**：

```java
private Bitmap addLegendAndInfo(Bitmap rotatedPseudoBitmap, Bitmap originalBitmap, String exifBrightness) {
    int[][] points = {
            {originalBitmap.getWidth() / 4, originalBitmap.getHeight() / 4},
            {originalBitmap.getWidth() * 3 / 4, originalBitmap.getHeight() / 4},
            {originalBitmap.getWidth() / 4, originalBitmap.getHeight() * 3 / 4},
            {originalBitmap.getWidth() * 3 / 4, originalBitmap.getHeight() * 3 / 4},
            {originalBitmap.getWidth() / 2, originalBitmap.getHeight() / 2}
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
    // ... 解析 exifBrightness → bv，computeLFromBv 得 Lcenter，或 yValue 回退 ...
    Bitmap finalBitmap = Bitmap.createBitmap(rotatedWidth + legendWidth, rotatedHeight, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(finalBitmap);
    canvas.drawBitmap(rotatedPseudoBitmap, 0, 0, null);
    drawLegend(canvas, rotatedWidth, rotatedHeight, legendWidth, Lcenter);
    drawStatistics(canvas, avgR, avgG, avgB, yValue, exifBrightness, bv);
    return finalBitmap;
}
```

**逐段解释**：

| 要点 | 说明 |
|------|------|
| **五点采样** | 四角 + 中心取 RGB 平均 | 对应 README「多点采样计算平均亮度信息并叠加到结果图」 |
| **Lcenter** | 优先 EXIF BV → `computeLFromBv`；否则用灰度经验式 | 图例刻度锚定 |
| **图例 `drawLegend`** | 以 `Lcenter`±25% 分四段，渐变条 + 文字 | 「输出亮度区间可视化图例」 |
| **宽图拼接** | `rotatedWidth + legendWidth` | 伪彩主图与右侧图例同一张输出图 |

---

## 附录：与需求清单对照（避免答辩口径偏差）

| 需求描述 | 仓库实际情况 |
|----------|----------------|
| `CalibrationCurve.java` | 无；曲线参数为 `CurveParams.java` |
| `evaluateAutoTune` | 无；核心为 `maybeAutoApplyFromAnalyzer` |
| `Camera2Interop.Extender` 绑定 | 未采用；使用 `Camera2CameraControl.setCaptureRequestOptions` |
| 着色器 `texelSize`、3×3 标准差 | 无；对比度在 Java 伪彩；预览着色器为分段 RGB |
| `processPseudoColor` | 无；方法名为 `createPseudoColorImage` |
| 标定 R² | 无 |
| `onSurfaceDestroyed`（Renderer） | 未覆写；用 `release()` + `queueEvent` |

---

*文档基于当前 `main` 源码路径整理，若后续重构类名/方法名，请以 IDE 跳转为准。*
