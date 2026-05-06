# CameraProject — 基于相机响应曲线的 Android 应用

## 项目简介

CameraProject 为河海大学大创相关工程，面向移动端 **相机响应曲线** 与 **物理亮度（cd/m²）** 应用。采用 **CameraX + Camera2 Interop + OpenGL ES 2.0**：支持手动 **ISO / 曝光时间 / 亮度增益**，拍照后生成 **伪彩色** 结果图；物理亮度以 **Debevec–Malik 离散 g(DN)** 与 **灰卡绝对标定系数 K** 为主路径，满足 **L = K×exp(g(DN))** 时显示真实 cd/m²，否则为「未校准」等占位说明。

- **包名 / applicationId**：`com.example.camera`
- **Gradle 根工程名**（`settings.gradle`）：`camera`

## 仓库结构（概览）

| 路径 | 说明 |
|------|------|
| `app/` | 单模块 Android 应用源码、资源、测试 |
| `app/src/main/` | 正式功能与 Release 可运行代码 |
| `app/src/debug/` | 仅 Debug 构建：如后台统计 `DebugStatsDashboardActivity` |
| `docs/` | 标定相关说明文档（见下文「文档」） |
| `gradle/libs.versions.toml` | 依赖与插件版本目录（Version Catalog） |

## 当前实现概览（与代码一致）

- **预览**：`CameraX Preview` → `SurfaceTexture`，`GLSurfaceView` + OpenGL 渲染；`RENDERMODE_WHEN_DIRTY`，按帧 `requestRender()`。
- **手动参数**：Camera2 Interop 设置 `SENSOR_SENSITIVITY`、`SENSOR_EXPOSURE_TIME`。
- **拍照**：`ImageCapture` + `ImageProxy`，`try/finally` 释放资源。
- **后处理**：伪彩色、右侧图例、左侧统计（含 Debevec 公式行、**中心亮度 L** 等）；**不再**使用历史 APEX **L = a×exp(b×BV)** 作为物理亮度主路径。
- **存储**：`MediaStore` 写入 `DCIM/Camera`（Android 10+ 分区存储）。
- **分层**：`MainActivity`（视图与 I/O）+ `CameraPresenter`（编排）+ `CameraModel`（算法）+ `ImageRepository`（存储 / EXIF）。
- **测光**：`ImageAnalysis`（如 640×480）实时推荐；自动微调可开关（稳定帧、BV 阈值、节流、手动后暂停等）。
- **UI**：预览约上 2/3；左下为模式切换、高级参数、**「响应曲线标定 (Debevec)」** 折叠块；**「亮度计校准」整块及 CSV 工具链仅在 Debug 构建显示**（`BuildConfig.DEBUG`）。

## 功能特性

### 核心功能

- 手动调节 ISO、曝光时间、亮度增益。
- 推荐模式：实时推荐值、「应用推荐值」、锁定当前推荐（AE-L 类行为）。
- 自动微调：可开关；稳定帧、BV 差阈值、下发节流；小步进与安全范围限制；手动拖杆后暂停。
- 实时预览与伪彩色导出（色相 + 局部对比度饱和度），含图例。
- **中心 L（cd/m²）**：`CameraModel.computeLFromDn` — 仅当持久化 **g** 与有效 **K** 齐全时为 **`L = K×exp(g(DN))`**，否则 NaN / 界面占位文案。
- EXIF 读取；保存原图与伪彩图到相册路径。
- **Debevec**：曝光序列 → **g**；灰卡区域 + 亮度计 cd/m² → **K**；数据经 `CalibrationRepository` 等持久化。

### 相机响应与标定（当前范围）

- **已实现**：Debevec **g[0..255]**、灰卡平均 DN、**K**、预览/导出中心 L、曲线来源角标。
- **应用内已移除**：BV 多点成像采样、本机 APEX **a/b** 拟合 UI 与主亮度路径；设置里「清除旧版 A/B」仅清历史 SharedPreferences。
- **遗留数据**：Room `calibration_records` 等仍可能含旧字段（上传快照 / 演示）；新流程不产生本机 **a/b** 拟合。

## 技术架构

### 环境要求

| 项 | 版本 |
|----|------|
| minSdk | 24（Android 7.0+） |
| compileSdk / targetSdk | 35 |
| JDK | 11（`compileOptions` / `jvmTarget`） |
| 设备 | 建议支持 Camera2 的真机 |

构建所用版本见 `gradle/libs.versions.toml`（摘录）：Android Gradle Plugin **8.8.0**、Kotlin **2.0.21**、CameraX **1.3.4**、Room **2.7.0** 等。

### 主要技术栈

- **CameraX**（含 Camera2）：预览、拍照、生命周期绑定。
- **Camera2 Interop**：手动 sensor 参数。
- **GLSurfaceView + OpenGL ES 2.0**：伪彩预览。
- **View Binding**：布局绑定（`viewBinding true`）。
- **ExifInterface**、**MediaStore**、**Canvas / Bitmap**：导出与叠加。
- **Room**：标定上传快照 `calibration_records`；启动时在 **IO 协程** 中维护（见 `CameraApplication`）。
- **WorkManager**：「数据分享」打开时的队列上传。
- **Kotlin 协程**：应用内异步与 Room / 同步任务。
- **DataStore Preferences**：部分配置持久化。
- **Retrofit + OkHttp + Gson**：HTTP API（如标定接口占位）。
- **Supabase Kotlin SDK（BOM）+ PostgREST / Auth + Ktor Android**：可选云端同步路径。
- **kotlinx-serialization**：JSON 等序列化。
- **Apache Commons Math3**：数值计算（如 Debevec 相关求解辅助）。
- **MPAndroidChart**（`debugImplementation`）：Debug 统计图表。

### 关键类型与路径（便于检索）

- `com.example.camera.MainActivity`：权限、UI、与 Presenter 协作。
- `com.example.camera.CameraRenderer`：OpenGL 与 `SurfaceTexture`。
- `com.example.camera.presenter.CameraPresenter`：业务编排、自动微调、Debevec / 灰卡流程衔接。
- `com.example.camera.presenter.state.AutoTuneState`：自动微调状态。
- `com.example.camera.model.CameraModel`：伪彩与 `computeLFromDn`。
- `com.example.camera.data.ImageRepository`：MediaStore、EXIF。
- `com.example.camera.data.CalibrationRepository`：**g**、**K**、SharedPreferences 等标定持久化。
- `com.example.camera.data.local.CalibrationRecordDatabase`：Room。
- `com.example.camera.model.calibration.DebevecSolver`、`MatrixBuilder`、`SvdSolver`：g(DN) 求解。
- `com.example.camera.model.calibration.AbsoluteLuminanceCalibration`：由灰卡与已知 cd/m² 求 **K**。
- `com.example.camera.presenter.ExposureSequenceCapture`：固定 ISO、变快门连拍供 Debevec。
- `com.example.camera.ui.GreyCardSelector`：预览上框选灰卡。
- `com.example.camera.sync.StandardCurveSync`、`CurveSyncScheduler`：设备曲线元数据同步（**不再**写入本地 A/B 亮度模型作为主路径）。
- `com.example.camera.SettingsActivity`：分享、上传、清除旧版 A/B 等；Debug 下「后台数据统计」入口。
- `com.example.camera.debug.stats.DebugStatsDashboardActivity`（**仅 debug 源码集 + manifest**）：聚合与导出演示。

## 开发者配置（构建前）

`app/build.gradle` 的 `defaultConfig` 中定义了后端占位，对接真实服务前请替换：

- `CALIBRATION_API_BASE_URL`：标定 HTTP API 根地址（保留末尾 `/`）。
- `SUPABASE_URL`、`SUPABASE_ANON_KEY`：Supabase 项目 URL 与 anon key。

未配置时仍可本地编译运行标定与拍照流程；上传 / 云端同步行为取决于实现与网络。

## 安装与运行

### 克隆与同步

```bash
git clone https://github.com/Lyrics-fs/CameraProject.git
cd CameraProject
```

使用 **Android Studio** 打开根目录并 Gradle Sync（推荐；可选用 IDE 自带 Gradle 发行版）。

### Gradle Wrapper 说明

当前仓库中的 `gradle/wrapper/gradle-wrapper.properties` 可能指向 **本机文件路径** 的 Gradle 压缩包；在其他机器克隆后若 Wrapper 无法下载，请改为官方发行地址（例如 `https://services.gradle.org/distributions/gradle-8.10.2-bin.zip`）或仅用 Android Studio 指定 Gradle 版本。仓库根目录提供 **`gradle-local.bat`**（Windows）：可通过环境变量 `GRADLE_INSTALL` 调用本机已解压的 Gradle 执行构建。

### 常用命令

**Linux / macOS：**

```bash
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest
./gradlew :app:installDebug
```

**Windows（PowerShell / CMD）：**

```bat
gradlew.bat :app:assembleDebug
gradlew.bat testDebugUnitTest
gradlew.bat :app:installDebug
```

## 使用说明

1. 首次启动授予相机权限（永久拒绝时可从系统设置恢复）。
2. 预览左下角：**推荐模式 / 手动模式**；推荐模式下底栏可「应用推荐值」「锁定当前推荐值」。
3. **高级参数**（2×2）：自动微调开关、稳定帧、步进、BV 阈值等 — **用于测光推荐链路**，非物理 cd/m² 主模型。
4. **手动模式**：三条滑杆调节 ISO、曝光、增益；底栏居中快门。
5. **「响应曲线标定 (Debevec)」**（建议顺序）：① 曝光序列求 **g** → ② `GreyCardSelector` 圈选灰卡 → ③ 输入亮度计 cd/m² 保存 **K**。
6. **环境光传感器校准（lux）**（入口在 **Debug** 下「亮度计校准」面板内 →「传感器校准」）：与 Debevec 物理 **L** 独立，用于改善 lux 显示及 Debug 流程中的参考换算。
7. **（仅 Debug）** 亮度计 CSV 记录 / 导出 / 拟合传感器系数、校准验证 Activity；设置页「后台数据统计」。
8. 拍照生成原图 + 伪彩图；左上角 HUD：**E 值**、环境光、**中心 L**（**g+K** 齐全时为 cd/m²）、状态与「重试」。
9. **设置**：数据分享、仅 Wi‑Fi 上传、立即上传、清除旧版 A/B、隐私分享页等。

## 文档

- **`docs/CameraResponseCalibration_UserGuide.md`**：仍以历史 **BV / APEX a×exp(b×BV)** 流程撰写，**与当前仅 Debevec + 灰卡 K 的实现不一致**；**实际操作请以本文「使用说明」与界面文案为准**。
- 仓库内**暂无**单独的 `CameraResponseCalibration_TechnicalDesign.md`；技术细节以源码与本文「当前实现概览」「关键类型」为准。

## 测试

- **单元测试**：`app/src/test/`（含 Presenter、Debevec、`ExposureSequenceCapture`、`SvdSolver`、`LumaMetrics` 等）。
- **Instrumented**：`app/src/androidTest/`（如 `ExampleInstrumentedTest`）。

```bash
./gradlew testDebugUnitTest
# Windows: gradlew.bat testDebugUnitTest
```

## 参考资料

- [CameraX](https://developer.android.com/media/camera/camerax)
- [Camera2](https://developer.android.com/reference/android/hardware/camera2/package-summary)
- [MediaStore](https://developer.android.com/training/data-storage/shared/media)

## 最近优化记录（摘要）

### 2026-05

- Debevec 左下独立折叠块：`ExposureSequenceCapture`、`DebevecSolver`、`CalibrationRepository.saveDebevecG`；灰卡 + `AbsoluteLuminanceCalibration` → **K**。
- `CameraModel.computeLFromDn`：**仅 g+K** 给出物理 **L**；移除 APEX **a/b** 作为 cd/m² 主路径。
- 移除本机 **CurveParams / CalibrationFitter**、多点 lux 标定 UI 等；`StandardCurveSync` 不再写入本地 A/B 亮度曲线。

### 2026-04

- Room `calibration_records`、Debug 后台统计页（MPAndroidChart）；启动时 IO 协程执行 `runStartupMaintenance()`。
- 测光、导出统计与 UI 布局调整；黑屏与资源释放、`MediaStore`、按帧渲染与错误态统一等稳定性改进。
- 依赖接入 Version Catalog；Presenter / instrumentation 测试补强。

（更细的按条目 changelog 可与 Git 历史对照；上述与当前主干功能一致即可。）

## 许可证

本项目遵循仓库根目录 **`LICENSE`** 文件中的条款。
