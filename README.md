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
- **UI**：预览约上 2/3；左下为模式切换、高级参数、**「响应曲线标定 (Debevec)」** 折叠块（含亮度计 / L3 绝对标定）。

## 功能特性

### 核心功能

- 手动调节 ISO、曝光时间、亮度增益。
- 推荐模式：实时推荐值、「应用推荐值」、锁定当前推荐（AE-L 类行为）。
- 自动微调：可开关；稳定帧、BV 差阈值、下发节流；小步进与安全范围限制；手动拖杆后暂停。
- 实时预览与伪彩色导出（色相 + 局部对比度饱和度），含图例。
- **中心 L（cd/m²）**：`CameraModel.computeLFromDn` — 仅当持久化 **g** 与有效 **K** 齐全时为 **`L = K×exp(g(DN))`**，否则 NaN / 界面占位文案。
- EXIF 读取；保存原图与伪彩图到相册路径。
- **Debevec + 绝对亮度**：曝光序列 → **g**；灰卡区域 + **亮度计** 或 **环境光传感器估算（L3）** → **K**；数据经 `CalibrationRepository` 等持久化。

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
- **Room**：`calibration_records` 库表；启动时在 **IO 协程** 中 `runStartupMaintenance()`（见 `CameraApplication`）；**不再**由应用写入新快照（旧上传管线已移除）。
- **WorkManager**：Level 1 查表上传（`UploadLookupTableWorker` / `LookupTableUploadScheduler`）；旧版 `UploadCalibrationWorker` / `UploadManager` 已移除。
- **Kotlin 协程**：应用内异步与 Room / 云端同步任务。
- **SharedPreferences**：标定 **g/K**、界面与测光相关偏好等。**AndroidX DataStore** 仍在 `build.gradle` 依赖中，当前业务源码 **未引用**。
- **Retrofit + OkHttp + Gson**：FC `query` / `ping` / `upload-curve` / `upload-table` 等（`NetworkModule` 统一 `baseUrl`）。
- **Supabase Kotlin SDK（BOM）+ PostgREST / Auth + Ktor Android**：可选云端同步路径。
- **kotlinx-serialization**：JSON 等序列化。
- **Apache Commons Math3**：数值计算（如 Debevec 相关求解辅助）。
- **MPAndroidChart**（`debugImplementation`）：Debug 统计图表。

### 关键类型与路径（便于检索）

- `com.example.camera.MainActivity`：权限、UI、Level1/2 标定区、灰卡圈选、WorkManager 上传结果观察。
- `com.example.camera.CameraRenderer`：OpenGL 与 `SurfaceTexture`。
- `com.example.camera.presenter.CameraPresenter`：业务编排、自动微调、Debevec / 灰卡 / Level1 查表与上传调度衔接。
- `com.example.camera.presenter.state.AutoTuneState`：自动微调状态。
- `com.example.camera.model.CameraModel`：伪彩与 `computeLFromDn`。
- `com.example.camera.data.ImageRepository`：MediaStore、EXIF。
- `com.example.camera.data.CalibrationRepository`：**g**、**K**、SharedPreferences 标定持久化（**不承担** Room 写入）。
- `com.example.camera.model.calibration.LookupTableRepository`：Level1 查表持久化、`upload_id`、`isUploaded`。
- `com.example.camera.data.local.CalibrationRecordDatabase`：Room（Debug 统计读历史行 + 启动清理）。
- `com.example.camera.network.NetworkModule`：Retrofit 单例，`baseUrl` = `CloudRepository.BASE_URL`。
- `com.example.camera.network.CloudRepository`：**Kotlin** `object`；`BASE_URL` = `BuildConfig.CALIBRATION_API_BASE_URL`；`uploadLookupTableBlocking`、`uploadCurve`。
- `com.example.camera.network.ApiService` / `CalibrationApiService`：FC 路径声明。
- `com.example.camera.network.UploadTableResponseResolver`、`RetrofitResponses`：`/upload-table` JSON 解析与响应关闭。
- `com.example.camera.upload.LookupTableUploadScheduler`、`UploadLookupTableWorker`：查表上传队列（UNMETERED + 退避）。
- `com.example.camera.model.calibration.DebevecSolver`、`MatrixBuilder`、`SvdSolver`：g(DN) 求解。
- `com.example.camera.model.calibration.AbsoluteLuminanceCalibration`：由灰卡与已知 cd/m² 求 **K**。
- `com.example.camera.presenter.ExposureSequenceCapture`：固定 ISO、变快门连拍供 Debevec。
- `com.example.camera.ui.GreyCardSelector`：**Kotlin**，预览上框选灰卡。
- `com.example.camera.sync.CloudCalibrationSync`：启动拉取 **`query`**（查表 / Debevec 快照写入本地）。
- `com.example.camera.sync.StandardCurveSync`、`CurveSyncScheduler`：设备曲线元数据同步（**不再**写入本地 A/B 作为主路径）。
- `com.example.camera.SettingsActivity`：云端上传说明文案、清除旧版 A/B；**Debug** 下「后台数据统计」入口。
- `com.example.camera.debug.stats.DebugStatsDashboardActivity`（**仅 debug**）：聚合与导出演示。

## 开发者配置（构建前）

`app/build.gradle` → `defaultConfig` → `buildConfigField`：

- **`CALIBRATION_API_BASE_URL`**：阿里云 FC HTTP 根地址（**末尾必须有 `/`**）。运行时 **`CloudRepository.BASE_URL`** 与此字段一致，`NetworkModule` 用于 Retrofit。
- **`SUPABASE_URL`、`SUPABASE_ANON_KEY`**：Supabase 占位；对接真实项目请替换。

**仓库根目录 `local.properties`（已被 `.gitignore`，勿提交）**

- **`sdk.dir`**：本机 Android SDK（Android Studio 通常自动生成）。
- **`LAB_UPLOAD_SECRET`**：与 FC **`LAB_SECRET`** / `app.py` 校验一致；由 `readLabUploadSecretForBuildConfig` 读入并写入 **`BuildConfig.LAB_UPLOAD_SECRET`**。  
  **注意**：Gradle **只读取名为 `local.properties` 的文件**，`local.properties.txt` 等扩展名 **无效**。

未配置 Supabase / 密钥时仍可本地编译运行拍照与标定；查表上传在无密钥或仅蜂窝网下可能跳过或排队失败；**`CloudCalibrationSync`** 仍可在有网时尝试 **`query`**。

### 实验室上传密钥（Level 1 `upload-table` / 云函数 `LAB_SECRET`）

查表上传到阿里云 FC **`/upload-table`** 时，请求体中的 `secret` 来自构建期 **`BuildConfig.LAB_UPLOAD_SECRET`**，**不再**在源码中写死。须与函数计算环境变量 **`LAB_SECRET`** 一致（云函数侧如 `app.py` 内校验与此相同）。

- **当前仓库配套 FC（大创实验室约定）**：上传校验密钥为 **`camera-project-lab-2026`**。请在 FC 控制台将 **`LAB_SECRET`**（或 `app.py` 读取的环境变量）设为该值；本机构建时在 **`local.properties`** 写 `LAB_UPLOAD_SECRET=camera-project-lab-2026`。**勿**把含真实密钥的 `local.properties` 提交 Git。

1. **本机开发（推荐）**：在仓库根目录 **`local.properties`**（已被 `.gitignore` 忽略）增加一行，例如：  
   `LAB_UPLOAD_SECRET=camera-project-lab-2026`（须与线上 **`LAB_SECRET`** 一致；若云端已轮换则改用新值）  
   然后 **Sync / 重新编译**；`LAB_UPLOAD_SECRET` 会写入 `BuildConfig`。
2. **命令行 / CI**：`gradle -PLAB_UPLOAD_SECRET=你的密钥 :app:assembleDebug`（或在 CI 密钥库中注入同名 Gradle 属性）。若同时存在 `local.properties` 与 `-P`，**以 `local.properties` 为准**（与 `readLabUploadSecretForBuildConfig` 实现一致）。
3. **轮换**：在 FC 控制台更新 `LAB_SECRET` → 同步更新各开发者 `local.properties` / CI 变量 → **重新出包**；旧 APK 内仍为旧密钥，上传会失败直至重装新构建。
4. **空密钥**：未配置时 **不会发起** `upload-table`，界面会提示「未配置 LAB_UPLOAD_SECRET…」；本地查表数据照常使用。

**Level 1 上传条件与本地可用性（与代码一致）**

- **上传门槛**：持久化查表需 **至少 5 组** `(dn, luminance)` 才会入队并成功走 `/upload-table`；不足 5 组时 Worker / `CloudRepository` 会 **跳过上传**（不视为网络错误）。本地用于测光/显示的「查表是否可用」另按 **`LookupTable.isAvailable()`（≥3 组）`** 判断，与上传门槛 **不同**。
- **密钥**：`LAB_UPLOAD_SECRET` 必须与 FC 环境变量 **`LAB_SECRET`** 一致，否则 HTTP/JSON 会返回 403 等业务失败。
- **失败不影响本机标定**：上传失败、未配置密钥、仅蜂窝网络（不满足 **UNMETERED** 队列约束）或用户从未触发上传时，**已保存的查表仍保留在本地**，测光与界面逻辑照常使用已持久化数据；云端仅用于备份/聚合/多端拉取（`CloudCalibrationSync` 等）。

**远端下发**：若需正式渠道动态密钥，可在应用内增加一次「拉取上传令牌」接口，将令牌写入 `EncryptedSharedPreferences` 再参与请求；当前仓库仅实现 **构建期注入**，便于实验室内测包与 CI 对齐云函数。

### 发版、灰度与 FC / 密钥策略

- **与 FC 版本对齐**：发版说明或内测公告中应注明 **本构建依赖的 FC 部署版本**（例如函数/HTTP 触发器修订号、变更日期），以及 **`/upload-table` 请求体 / 响应 JSON** 是否与当前 `CloudRepository`、`UploadTableResponseResolver` 契约一致；升级 FC 若收紧字段或改 `code` 语义，须 **同步发客户端** 或保持服务端向后兼容。
- **密钥与灰度**：`LAB_UPLOAD_SECRET` 打进 APK，**旧包无法随服务端单方轮换密钥**而自动更新。轮换 `LAB_SECRET` 时要么 **先发新包再切密钥**，要么在 FC 侧短期 **双密钥校验**（灰度窗口），避免线上旧构建集体 403。内测 / 灰度渠道包应使用 **对应该环境 FC** 的密钥与 **`CALIBRATION_API_BASE_URL`**（改 `app/build.gradle` 后重新编译），勿混用生产与实验函数。
- **应用版本字段**：请求体带 **`app_version`**（`BuildConfig.VERSION_NAME`），便于 FC 或聚合层按版本统计、限流或分支逻辑；发版说明可要求后端按 `app_version` 过滤或打标。

### HTTPS 与 PKIX（运行设备 vs 本机构建）

- **真机 / 模拟器访问 FC**：`https://calibration-api-…fcapp.run/` 使用公有 CA 证书链，应用走 **系统信任库**，一般无需 `network_security_config`。
- **Gradle Wrapper 下载或依赖拉取报 PKIX**：属于 **开发机 JVM** 信任链问题（与 Android 应用无关）。可尝试：使用 **Android Studio 自带 JDK** 执行 Gradle、公司网络 **导入根证书到 JVM cacerts**、或换可访问 `services.gradle.org` 的网络/代理；**勿**为绕过校验在应用内关闭 HTTPS 校验。
- **调试抓包（Charles / mitmproxy）**：仅在 **debug** 包为调试域名配置 `res/xml/network_security_config.xml` 信任用户 CA，且勿用于生产发布。

### `upload-table` 响应契约、`upload_id` 幂等与 `/query` 查表格式

**客户端行为（与 `CloudRepository` / `BaseResponse` 一致）**

- 成功以 JSON 业务字段 **`code == 200`** 为准（`code` 支持 number 或 string）；**`message` / `msg` / `error` / `detail`** 任一为可读错误文案。
- **HTTP 2xx 且 body 可解析**：以 body 内 `code` 为准（例如 HTTP 200 + `{"code":403}` → 按密钥失败处理）。
- **HTTP 非 2xx**：优先从 **errorBody** 再解析同一 JSON 形态（网关常在 403 时把 JSON 放在 errorBody）；纯 HTTP 403 且无 JSON → 提示「密钥错误或无权访问（HTTP 403）」。

**请求体（Level 1 查表）**

- 除 `secret`、`model`、`iso`、`app_version`、`entries` 外，客户端会发送 **`calibrated_at`**（毫秒，与本地 `LookupTable.calibratedAt` 一致）与 **`upload_id`**（UUID；每次「保存查表」换新，**再次上传**同一查表复用同一 id，便于服务端按 `upload_id` 或 `model + calibrated_at + upload_id` 去重）。

**`/query` 与上传对齐**

- 启动拉取由 `CloudCalibrationSync.tryParseLookup` 解析；建议聚合层返回的查表块与上表字段兼容：`entries[].dn` / `luminance`（及 `L`、`luminance_cd_m2`、`brightness_cd_m2` 等别名）、`model` 或 `model_name`、`iso` 或 `iso_used`、`calibrated_at`。可选嵌套键名：`lookup_table`、`lookupTable`、`level1`、`table`、`lookup`，或根级直接含 `entries` 数组。

**Level 1 查表上传入队（WorkManager）**

- 保存查表或「再次上传」后由 **`LookupTableUploadScheduler`** 入队 **`UploadLookupTableWorker`**：约束为 **`NetworkType.UNMETERED`**（通常为 Wi‑Fi），**指数退避**重试；实际 HTTP 由 **`CloudRepository.uploadLookupTableBlocking`** 执行。
- 成功后在 Worker 内 **`markAsUploaded()`**；`MainActivity` 观察 **`UNIQUE_WORK_NAME`** 的 `WorkInfo`，再 Toast 与刷新「已上传」UI。

### Room `calibration_records` 与当前上传的关系

- **Level 1（`/upload-table`）** 与 **Level 2（`/upload-curve`）** 均 **不经过** Room；分别为 **`LookupTableUploadScheduler`** + **`CloudRepository.uploadLookupTableBlocking`**，以及亮度计绝对标定成功后的 **`CloudRepository.uploadCurve`**。
- 工程仍保留 **Room 库与 `CalibrationUploadData` 模型**，供 **Debug「后台数据统计」** 经 `CalibrationRecordDatabase` 读取本机历史行；已删除 **`CalibrationRecordStore`**、**`UploadCalibrationWorker` / `UploadManager`**、设置页的「数据分享 / 立即上传」及 **`PrivacyCalibrationShareActivity`**；`CalibrationRepository` 不再承担 Room 写入。

### 单元测试（查表上传相关）

- `UploadTableResponseResolverTest`：JSON `code` / HTTP 与 body 组合。
- `LookupTableUploadMockWebServerTest`：`MockWebServer` + Retrofit 对 `/upload-table` 的 200 / JSON 403。
- `LookupTableRepositoryTest`（Robolectric）：`save` 后清除 **`isUploaded()`** 标记；`testOptions.unitTests.includeAndroidResources = true`（`app/build.gradle`）。

### 云端上行 / 下行（与当前代码一致）

| 方向 | 触发 / 入口 | 接口或机制 |
|------|-------------|------------|
| **下行** | `CameraApplication` 启动 IO 协程 | **`GET query`**（`CloudCalibrationSync`）解析并写入查表 / Debevec；**`ping`** 健康探测 |
| **上行 · Level1** | 保存查表或「再次上传」 | **`POST upload-table`**，`secret` = `BuildConfig.LAB_UPLOAD_SECRET`；**WorkManager**（UNMETERED） |
| **上行 · Level2** | 亮度计灰卡标定成功且帧数、R² 达标 | **`POST upload-curve`**（同步调用，非 WorkManager）；**L3 传感器路径不上传** |

主界面与设置页含 **L3 精度说明**、**匿名上传说明**（亮度计路径）、**Debevec 连拍须保持手机不动**、**灰卡仅圈本体** 等文案（`strings.xml` / `MainActivity`）。

## 安装与运行

### 克隆与同步

```bash
git clone https://github.com/Lyrics-fs/CameraProject.git
cd CameraProject
```

使用 **Android Studio** 打开根目录并 Gradle Sync（推荐；可选用 IDE 自带 Gradle 发行版）。

### Gradle 与 Wrapper

命令行构建需在终端中能执行 **`gradle`**（已安装 Gradle 并加入 **PATH**，版本建议与 `gradle/wrapper/gradle-wrapper.properties` 一致，当前为 **8.10.2**）。若无全局 Gradle，可在项目根目录使用 **`./gradlew`**（Linux / macOS）或 **`gradlew.bat`**（Windows），由 Wrapper 自动下载对应发行版。Windows 下也可用 **`gradle-local.bat`**（通过环境变量 **`GRADLE_INSTALL`** 指向本机解压目录）。

### 常用命令

在项目根目录执行（Windows / Linux / macOS 相同）：

```bash
gradle :app:assembleDebug
gradle testDebugUnitTest
gradle :app:installDebug
```

## 使用说明

1. 首次启动授予相机权限（永久拒绝时可从系统设置恢复）。
2. 预览左下角：**推荐模式 / 手动模式**；推荐模式下底栏可「应用推荐值」「锁定当前推荐值」。
3. **高级参数**（2×2）：自动微调开关、稳定帧、步进、BV 阈值等 — **用于测光推荐链路**，非物理 cd/m² 主模型。
4. **手动模式**：三条滑杆调节 ISO、曝光、增益；底栏居中快门。
5. **Level 1 查表法采集**（折叠块）：多组 DN + 亮度计 cd/m²；≥5 组可保存并排队上传；与灰卡选区与 Level2/3 共用圈选。
6. **「响应曲线标定 (Debevec)」**（建议顺序）：① 曝光序列求 **g**（连拍时须保持机身稳定）→ ② 圈选灰卡 → ③ **绝对亮度**：「亮度计实测」或「传感器估算（L3）」**二选一** 保存 **K**；**L3 精度低于亮度计**，界面有提示。
7. **环境光**：左上角 HUD 仍显示 lux / 换算 cd/m²（与 **L3** 估算同源公式）；主界面**已移除**左下角「亮度计校准」调试面板。工程内仍保留 `SensorCalibrationActivity`（lux 分段校准），可通过 IDE/ADB 启动。
8. 拍照生成原图 + 伪彩图；左上角 HUD：**E 值**、环境光、**中心 L**（**g+K** 齐全时为 cd/m²）、状态与「重试」。
9. **设置**：云端上传与隐私说明摘要、清除旧版 A/B；**（仅 Debug）**「后台数据统计」入口。

## 文档

- **`docs/CameraResponseCalibration_UserGuide.md`**：正文仍以历史 **BV / APEX** 采样流程为主（与当前主界面 **Debevec + 灰卡 K** 不完全一致）；文末已补充 **Level 1 查表云端上传** 与 **发版 / FC 对齐** 说明。**实际操作与上传条件请以本文与界面为准**。
- 仓库内**暂无**单独的 `CameraResponseCalibration_TechnicalDesign.md`；技术细节以源码与本文「当前实现概览」「关键类型」为准。

## 测试

- **单元测试**：`app/src/test/`（含 Presenter、Debevec、`ExposureSequenceCapture`、`SvdSolver`、`LumaMetrics` 等）。
- **Instrumented**：`app/src/androidTest/`（如 `ExampleInstrumentedTest`）。

```bash
gradle testDebugUnitTest
```

## 参考资料

- [CameraX](https://developer.android.com/media/camera/camerax)
- [Camera2](https://developer.android.com/reference/android/hardware/camera2/package-summary)
- [MediaStore](https://developer.android.com/training/data-storage/shared/media)

## 最近优化记录（摘要）

### 2026-05

- Debevec 左下独立折叠块：`ExposureSequenceCapture`、`DebevecSolver`、`CalibrationRepository.saveDebevecG`；灰卡 + `AbsoluteLuminanceCalibration` → **K**（亮度计 / L3 二选一）。
- `CameraModel.computeLFromDn`：**仅 g+K** 给出物理 **L**；移除 APEX **a/b** 作为 cd/m² 主路径。
- 移除本机 **CurveParams / CalibrationFitter**、多点 lux 标定 UI 等；`StandardCurveSync` 不再写入本地 A/B 亮度曲线。
- Level 1：`LookupTableUploadScheduler` / `UploadLookupTableWorker`、`CloudRepository.uploadLookupTableBlocking`；**MockWebServer / Robolectric** 相关单测；`RetrofitResponses` 等与 Retrofit 2.11 兼容。
- 移除旧 **Room 队列上传**（`UploadCalibrationWorker`、`UploadManager`、`CalibrationShareRepository`、`PrivacyCalibrationShareActivity` 等）；设置页改为上传说明 + 清 A/B + Debug 统计。
- UI：L3 精度提示、亮度计路径匿名上传说明、Debevec 连拍勿动、灰卡圈选引导；`CloudRepository.BASE_URL` 绑定 **`BuildConfig.CALIBRATION_API_BASE_URL`**。
- 文档：`README`、**`docs/CameraResponseCalibration_UserGuide.md`** 与实验室密钥 **`camera-project-lab-2026`**、`local.properties` 读取说明同步。

### 2026-04

- Room `calibration_records`、Debug 后台统计页（MPAndroidChart）；启动时 IO 协程执行 `runStartupMaintenance()`。
- 测光、导出统计与 UI 布局调整；黑屏与资源释放、`MediaStore`、按帧渲染与错误态统一等稳定性改进。
- 依赖接入 Version Catalog；Presenter / instrumentation 测试补强。

（更细的按条目 changelog 可与 Git 历史对照；上述与当前主干功能一致即可。）

## 许可证

本项目遵循仓库根目录 **`LICENSE`** 文件中的条款。
