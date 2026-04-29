# 教师汇报与答辩准备材料

面向口头汇报与答辩，语言力求专业、精炼。内容与仓库 `README.md`、`docs/CameraResponseCalibration_TechnicalDesign.md` 及当前 Java 源码一致；若与课堂讲义中的符号约定不同，以**本工程实现**为准。

---

## 一、原理与模型

### 1. 相机响应曲线的物理意义

- **DN 与物理亮度非线性**：传感器输出、模拟前端增益、ISP（去马赛克、降噪、色调映射、伽马）等多级处理使**数字值（DN）与场景辐射或物理亮度 L** 通常不满足简单线性关系；同一 DN 在不同增益、不同 ISP 路径下可对应不同辐射量。
- **在光度测量中的作用**：若要用像素读数解释「多亮」，需要显式或隐式的**响应模型**，把可观测量（BV、DN、曝光参数）映射到可比较的标量 L，便于跨帧、跨设备对比与可视化。

### 2. 本项目采用的数学模型

#### 2.1 基础公式 \(L = a \cdot e^{b \cdot BV}\)

- **形式**：与先验文档一致，采用单指数关系描述 BV 到应用内亮度标量 L 的映射；标定前后**形式不变**，仅估计设备相关的 \((a,b)\)。
- **推导逻辑（工程化）**：对 \(L>0\) 取自然对数得 \(\ln L = \ln a + b \cdot BV\)，在 \((BV,\ln L)\) 平面为直线，便于用**线性最小二乘**估计参数（见 `CalibrationFitter`）。

#### 2.2 BV 与曝光参数（APEX 视角）

- **本工程中的 BV**：由分析帧 **Y 通道均值归一化后取 \(\log_2\)** 得到的场景相对亮度标量（见 `CameraModel.calculateBVFromLuminance`），用于内部一致的光度层级描述。
- **与经典 APEX 的关系（答辩用语）**：经典体系中 \(A_v\)、\(S_v\)、\(T_v\) 等将**光圈、快门、ISO** 与曝光量联系；本项目的 `CameraModel.calculateExposureValue` 使用 **\(E \propto ISO \times t / N^2\)** 形式的曝光量标量用于界面展示，而 **BV 的计算链路独立于该 E 值**，二者分工为：BV 驱动测光与曲线映射，E 值用于「曝光量」直观显示。

#### 2.3 标定拟合：指数关系线性化与最小二乘

- 有效样本需满足 `referenceL > 0`、BV 有限且标为 `valid`。
- **BV 跨度**：有效样本中 \(\max BV - \min BV\) 须不小于配置阈值（代码中为 `CALIBRATION_MIN_BV_SPREAD`），避免共线样本导致病态解。
- **求解**：对 \(\ln L\) 与 BV 做一元线性回归得斜率 \(b\)、截距 \(\ln a\)，再 \(a=e^{\ln a}\)；并对 \(a,b\) 做有限性与粗范围校验（如 `CalibrationFitter` 中对 \(|b|\) 的上限）。

### 3. 伪彩色双变量映射原理

实现以 `CameraModel.createPseudoColorImage` 与 `CameraRenderer` 片元着色器为对照，二者色相映射**刻意对齐**。

| 通道 | 编码内容 | 要点 |
|------|----------|------|
| **H（色相）** | 亮度层级 | Rec.601 luma，可乘亮度增益；再按**本帧全局 min/max** 将亮度拉伸到索引，映射为蓝→青→绿→黄→红分段伪彩。 |
| **S（饱和度）** | 局部对比度 | 3×3 邻域 max−min 归一化后经门限与幂次映射；**当前离线路径中饱和度系数固定为全饱和**（与预览片段着色器「全饱和伪彩」策略一致，便于一致性与性能）。 |
| **V（明度）** | 固定策略 | 输出在灰度与伪彩色之间按饱和度插值（`out = gray + (pseudo − gray) * s`）；当 \(s=1\) 时即完全显示伪彩色分量，**明度不再单独作为第三维自由参数**，避免与 Hue 双重编码亮度。 |

### 4. 自动曝光辅助的决策模型

- **场景 BV**：对 `ImageAnalysis` 输出帧的 **Y 平面** 下采样求平均 → 归一化 → \(\log_2\)（`CameraModel.analyzeFrameForAutoExposure`）。
- **目标 BV**：默认 `DEFAULT_TARGET_BV = -2.47`（\(\log_2(0.18)\)，中灰参考量级），与 README 描述一致。
- **推荐 ISO/快门**：\(\Delta BV = \mathrm{target} - \mathrm{current}\) 限幅后，\(2^{\Delta BV}\) 先乘到当前曝光时间，剩余倍数再乘到 ISO；结果 clamp 在设备范围与**预览安全范围**内（避免推荐一键应用后黑屏、卡顿）。

**自动微调（Auto Tune）**与「全量推荐」分离：稳定帧 + BV 阈值 + 最小间隔满足后触发 UI 请求，实际下发采用**当前值与推荐值的按比例插值**（步进比例可配置，默认 0.25），自动模式下并**强制预览安全 ISO/快门区间**。

---

## 二、实现与架构

### 1. 整体架构（文字 + ASCII）

采用 **MVP 契约**（`CameraContract`）划分 View 与 Presenter；算法与曲线状态在 `CameraModel`，存储在 `ImageRepository` / `CalibrationRepository`。

```
┌─────────────────────────────────────────────────────────┐
│                    MainActivity                          │
│     View 实现 / 生命周期 / 权限 / CameraX 绑定 / GL 视图   │
└───────────────────────────┬─────────────────────────────┘
                            │ CameraContract.View
┌───────────────────────────▼─────────────────────────────┐
│                  CameraPresenter                         │
│   编排：分析帧、推荐、自动微调、标定会话、回调聚合          │
└───────┬─────────────────┬─────────────────┬─────────────┘
        │                 │                 │
┌───────▼────────┐ ┌──────▼──────┐ ┌────────▼────────────┐
│  CameraModel   │ │ImageRepository│ │ CalibrationRepository│
│ BV/L、伪彩、E值 │ │ MediaStore/   │ │ 曲线参数与标定摘要   │
│                │ │ EXIF 等       │ │ 持久化               │
└────────────────┘ └───────────────┘ └────────────────────┘

      ┌──────────────────┐          ┌─────────────────────┐
      │  CameraRenderer   │ ◄────── │ Preview → Surface   │
      │  GLSurfaceView    │          │ Texture → OES 纹理  │
      └──────────────────┘          └─────────────────────┘
```

### 2. CameraX + Camera2 Interop 协作机制

| 层级 | 职责 |
|------|------|
| **CameraX** | `Preview` 输出到自定义 `SurfaceProvider`（包装 `SurfaceTexture` 的 `Surface`）、`ImageCapture` 拍照、`ImageAnalysis` 测光分析；统一生命周期与用例绑定。 |
| **Camera2 Interop** | 通过 `Camera2CameraControl.from(camera.getCameraControl()).setCaptureRequestOptions(...)` 下发 `CaptureRequest` 级别参数：关闭 AE（`CONTROL_AE_MODE_OFF`）、设置 `SENSOR_SENSITIVITY` 与 `SENSOR_EXPOSURE_TIME`（见 `MainActivity.applyCamera2Options`）。 |
| **协同方式** | CameraX 维护会话与 Surface 管线；Interop 将底层 **CaptureRequest 键值** 注入重复请求，使手动 ISO/曝光在预览与拍照链路上生效，无需手写整套 Camera2 Session。 |

### 3. OpenGL ES 渲染链路

1. **`GLSurfaceView` + `CameraRenderer`**：`Renderer` 在 `onSurfaceCreated` 中 `glGenTextures` 绑定 **OES 外部纹理**，创建 **`SurfaceTexture(textureId)`**，并回调 `MainActivity` 将 `Surface` 交给 CameraX `Preview`。
2. **帧到达**：`SurfaceTexture.OnFrameAvailable` → `requestRender()`（`RENDERMODE_WHEN_DIRTY`），避免持续刷新耗电。
3. **片元着色器**：`samplerExternalOES` 采样 → Rec.601 luma → 乘 `uBrightness` → 用 `uHueMin`/`uHueMax` 做与离线一致的全局拉伸 → 分段伪彩输出 RGB。

### 4. 自动微调状态机（`AutoTuneState`）

**关键字段**（见 `presenter/state/AutoTuneState.java`）：

- `enabled`：是否开启自动微调。
- `requiredStableFrameCount` / `stableFrameCount` / `lastDirection`：同向 BV 偏差连续满足的稳定帧计数。
- `bvDeltaThreshold`：偏差小于阈值则重置稳定计数（不触发）。
- `stepRatio`：应用推荐时的插值比例（与 UI 高级参数联动）。
- `lastAutoApplyTs`：配合全局常量最小间隔（500 ms）节流。

**协作逻辑要点**：`maybeAutoApplyFromAnalyzer` 中判定手动模式窗口、AE-L、阈值与稳定帧 → 触发 `requestAutoApplyRecommendation()` → `applyLatestExposureRecommendationInAutoMode()` 内按 `stepRatio` 向推荐值收敛并限制预览安全范围；用户拖动滑杆触发 `onUserManualAdjustmentStarted()` 进入短暂「手动优先」窗口。

### 5. 标定模块类设计

| 类型 | 职责 |
|------|------|
| **`CalibrationSample`** | 单次采样：BV、`referenceL`（由中心 ROI 均值映射）、原始 meanY、时间戳、`valid` 标记。 |
| **`CalibrationSession`** | 会话状态（空闲/采样中/拟合中/完成/失败）、样本列表、拟合结果与失败原因。 |
| **`CurveParams`**（非「CalibrationCurve」） | 存储 \(a,b\)、来源（先验/已标定）、时间戳；先验常数 `PRIOR_A/B` 与 `CalibrationRepository` 持久化一致。 |
| **`CalibrationFitter`** | `fitExpCurve`：过滤样本、跨度检查、对数线性回归。 |
| **`CalibrationRepository`** | 曲线参数与标定摘要的读写。 |

---

## 三、具体实现细节（深水区应答要点）

### 1. ImageAnalysis 实时测光如何保证流畅？

- **分辨率**：`640×480` 目标分辨率（`CameraPresenter` 常量），降低每帧处理量。
- **背压**：`STRATEGY_KEEP_ONLY_LATEST`，分析跟不上时丢弃旧帧，保证处理最新场景。
- **数据路径**：在分析回调中直接读 **Y 平面 `ByteBuffer`**，不整帧转 `Bitmap`。
- **线程**：专用单线程 `ExecutorService` 执行 analyzer；回调内 **`try/finally` 中 `image.close()`**，避免帧堆积与泄漏。

### 2. 拍照链路如何控制内存峰值？

- **`ImageProxy`**：`onCaptureSuccess` 内 **`try/finally` 保证 `imageProxy.close()`**。
- **采样解码**：`decodeSampledBitmap` 先 `inJustDecodeBounds` 再计算 `inSampleSize`，长边目标上限为 `MAX_DECODE_DIMENSION`（如 1920）。
- **Bitmap 回收**：处理失败路径、`setImageViewBitmapAndRecycleOld` 替换旧图、伪彩处理结束后对原图 **`recycle()`**。
- **GL 资源**：`onDestroy` 中 **`glSurfaceView.queueEvent(() -> cameraRenderer.release())`** 在 GL 线程删除纹理与 Program、释放 `SurfaceTexture`。

### 3. 退出重进黑屏问题如何应对？

- **机制**：Activity/GL 生命周期导致 **EGL 上下文与纹理 ID 失效**；若仍使用旧 `Surface` 或旧纹理绑定 Preview，会出现黑屏。
- **本工程做法**：`CameraRenderer.onSurfaceCreated` **重新 `glGenTextures`、新建 `SurfaceTexture`、编译 Program`**；`MainActivity` 在 `onSurfaceTextureAvailable` / `bindCameraPreview` 中把**新 Surface** 交给 `Preview`，并处理与上次绑定去重、释放旧 `Surface`。
- **渲染模式**：`RENDERMODE_WHEN_DIRTY` + 帧到达再 `requestRender()`。
- **说明**：当前仓库**未**调用 `setPreserveEGLContextOnPause`；答辩时可说明依赖**纹理与 Surface 重生绑定**为主；若未来启用 preserve EGL，需评估设备兼容性与资源生命周期。

### 4. 最小二乘法拟合的具体实现

- 模型 \(L = a \cdot e^{b \cdot BV}\) → \(\ln L = \ln a + b \cdot BV\)。
- 令 \(y=\ln L\)，\(x=BV\)，用**中心化后的斜率公式**（协方差/方差）求 \(b\)，截距得 \(\ln a\)，\(a=e^{\ln a}\)。
- 实现见 `CalibrationFitter.fitExpCurve`；**代码中未计算判定系数 R²**，拟合成败依赖样本数量、BV 跨度与系数合法性检查。若老师追问 R²，可答：**可作为质量评价与改进项**，当前以跨度与样本数为主控。

### 5. 自动微调的「步进收敛」策略

- **比例插值**：`applyLatestExposureRecommendationWithRatio`：`smoothed = current + (recommended − current) × ratio`；一键应用推荐用较小默认平滑系数，自动模式用 `autoTuneState.stepRatio`（UI 可选约 **20% / 25% / 30%**）。
- **目的**：避免一次性跳到推荐值导致预览闪烁或过冲。
- **范围**：自动应用路径下 **`forcePreviewSafeRange`** 为真时，将 ISO/曝光限制在 `PREVIEW_SAFE_*` 与设备 `Range` 交集内。

### 6. 标定采样如何保证数据有效性？（与代码一致）

- **最少样本**：有效样本数 ≥ **8**（`CALIBRATION_MIN_SAMPLES`）；**BV 跨度** ≥ **0.5**（`CALIBRATION_MIN_BV_SPREAD`）。
- **稳定性**：最近若干帧中心区域 meanY 的**标准差**需 ≤ **2.0**（`CALIBRATION_STABILITY_STD_MAX`），否则标为「抖动」、样本可判无效。
- **曝光合理性**：中心 meanY 约在 **(12, 245)** 内，避免过曝/欠曝样本。
- **采样节流**：两次采样间隔 ≥ **700 ms**，防止连点引入相关样本。
- **说明**：**未**实现「R² < 0.95 则失败」；失败文案为「样本不足或变化范围过小」等，与 `CalibrationFitter` 返回 `null` 条件对应。

---

## 四、常见追问速查表

| 问题 | 关键词 | 回答要点 |
|------|--------|----------|
| DN 为何不是亮度？ | ISP、伽马、增益 | 多级非线性处理，DN 是数字输出，需模型才能对应物理量或可比 L。 |
| 为何选指数模型？ | 可线性化、标定 | 形式简单；取对数后最小二乘；标定只更新 \(a,b\)。 |
| BV 在本项目怎么算？ | Y 均值、log2 | 归一化亮度后 \(\log_2\)，工程近似，重在单调与可复现。 |
| 先验 \((a,b)\) 从哪来？ | PRIOR_A/B | `CurveParams.prior()`，标定无效时回退。 |
| CameraX 为何要 Interop？ | 手动曝光 | CameraX 高级 API 不直接暴露 SENSOR 键；Interop 注入 CaptureRequest。 |
| 伪彩 Hue/Sat 分工？ | luma、邻域对比度 | Hue 主亮度；Sat 由局部对比度设计，当前实现可全饱和与预览对齐。 |
| 分析为何不卡？ | 640×480、KEEP_ONLY_LATEST、子线程 | 降分辨率、丢帧策略、非 UI 线程、及时 close。 |
| 拍照内存怎么控？ | inSampleSize、recycle、close | 下采样解码、替换 ImageView 时回收旧 Bitmap、释放 ImageProxy。 |
| 黑屏怎么修？ | onSurfaceCreated、重新 bind | 新纹理与 SurfaceTexture；Preview 绑定新 Surface；queueEvent 释放 GL。 |
| 步进 25% 指什么？ | 插值 | 当前参数向推荐值走一定比例，非一次到位。 |
| 标定至少几次？ | 8 点、BV 差 0.5 | 代码常量；跨度不足拟合返回 null。 |
| 有 R² 检验吗？ | 未实现 | 可答改进方向；现有为跨度+样本数+稳定性。 |

---

*文档生成依据：`README.md`、`docs/CameraResponseCalibration_TechnicalDesign.md` 及 `app/src/main/java/com/example/camera/` 下源码。*
