# CameraProject - 基于相机响应曲线的 Android 相机应用

## 项目简介

CameraProject 是河海大学大创项目，面向移动端相机响应曲线应用场景。项目基于 `CameraX + Camera2 Interop + OpenGL ES`，支持手动控制 ISO/曝光，并在拍照后生成伪彩色结果图用于亮度可视化分析。

## 当前实现概览（已与代码同步）

- 预览链路：`CameraX Preview` 输出到 `SurfaceTexture`，由 `GLSurfaceView` 渲染。
- 渲染模式：`RENDERMODE_WHEN_DIRTY`，按帧触发 `requestRender()`，降低空闲耗电。
- 参数控制：通过 Camera2 Interop 设置 `SENSOR_SENSITIVITY` 与 `SENSOR_EXPOSURE_TIME`。
- 拍照链路：`ImageCapture.OnImageCapturedCallback` + `ImageProxy`，并使用 `try/finally` 保证释放。
- 图像处理：拍照后执行伪彩色映射、图例绘制、亮度信息叠加。
- 存储策略：使用 `MediaStore` 保存到 `DCIM/Camera`，兼容 Android 10+ 分区存储。
- 架构分层：`MainActivity(View/I-O) + Presenter(编排) + Model(算法) + ImageRepository(存储/EXIF)`。
- 自动曝光辅助：`ImageAnalysis(640x480)` 实时测光，支持推荐模式/手动模式切换。
- 自动微调策略：可开关自动下发，含稳定帧、防抖阈值、节流与手动后暂停。
- 参数面板：推荐模式下提供可折叠“高级参数”（自动微调、稳定帧、步进、BV 阈值、AE-L）。
- 错误处理：统一状态区展示 + Toast + 重试按钮。
- 测试：包含 Presenter 单元测试与 androidTest 基础验证。

## 功能特性

### 核心功能

- 手动相机参数控制：精确调节 ISO、曝光时间、亮度增益。
- 推荐模式控制：显示实时推荐 ISO/曝光，支持手动“一键应用推荐值”。
- 自动微调开关：开启后满足条件自动下发参数，关闭后仅推荐不自动下发。
- 自动微调防抖：连续稳定帧、BV 差值阈值、500ms 最小下发间隔。
- 自动微调安全控制：自动模式每次仅走小步（20/25/30%）并限制在预览安全范围。
- 手动优先：用户拖动滑杆后自动微调立即暂停（默认 5s）。
- 实时预览：参数修改可实时反馈到预览画面。
- 伪彩色图像生成：将图像亮度映射为伪彩色并附带图例。
- 亮度计算与显示：基于公式估计场景亮度。
- EXIF 信息读取：读取亮度相关元数据（可用时）。
- 图像保存：保存原图与伪彩图到系统图库路径 `DCIM/Camera`。
- 设备响应曲线标定：支持灰卡/色卡多点采样，拟合设备特有的 `L = a × exp(b × BV)` 参数。

### 相机响应曲线相关

- 默认使用先验公式 `L = 2.9 × exp(0.729 × BV)` 估算亮度。
- 支持标定模式，采集多组样本后拟合设备特有参数 `L = a × exp(b × BV)`。
- 标定结果会持久化保存，后续优先使用“已标定”曲线，无有效标定时回退先验参数。
- 多点采样计算平均亮度信息并叠加到结果图。
- 输出亮度区间可视化图例，便于分析分布。

## 技术架构

### 环境要求

- Android SDK 24+（Android 7.0 及以上）
- Compile SDK 35
- JDK 11+
- 支持 Camera2 的 Android 设备

### 主要技术栈

- `CameraX`：预览与拍照主流程
- `Camera2 Interop`：手动参数下发
- `GLSurfaceView + OpenGL ES 2.0`：实时伪彩渲染
- `ExifInterface`：EXIF 读取
- `MediaStore`：分区存储写入
- `Canvas & Bitmap`：拍照结果图后处理

### 关键模块

- `MainActivity`：UI、权限流转、相机回调与流程协调
- `CameraRenderer`：OpenGL 渲染与 `SurfaceTexture` 管理
- `CameraPresenter`：参数变化、状态与业务编排（含自动微调触发链路）
- `presenter/state/AutoTuneState`：自动微调状态聚合（开关、节流、稳定帧、步进、阈值）
- `CameraModel`：伪彩算法与亮度计算
- `ImageRepository`：MediaStore 存储与 EXIF 读取
- `CalibrationRepository`：标定参数与摘要持久化
- `model/calibration/*`：曲线参数、样本、会话与拟合算法

## 安装与运行

### 编译步骤

1. 克隆项目：
   ```bash
   git clone https://github.com/Lyrics-fs/CameraProject.git
   ```
2. 使用 Android Studio 打开项目并同步 Gradle。
3. 连接真机（开启 USB 调试）或启动模拟器。
4. 运行：
   ```bash
   ./gradlew :app:assembleDebug
   ```

### 常用命令

- 编译 Debug：
  ```bash
  ./gradlew :app:assembleDebug
  ```
- 运行单元测试：
  ```bash
  ./gradlew testDebugUnitTest
  ```
- 安装到设备：
  ```bash
  ./gradlew :app:installDebug
  ```

## 使用说明

1. 首次启动授予相机权限（若永久拒绝，可通过设置页引导恢复）。
2. 通过“切换到推荐模式 / 切换到手动模式”选择控制方式。
3. 推荐模式下可点击“应用推荐值”，或在“高级参数”中开启自动微调。
4. “高级参数”可调：稳定帧（3/4/5）、步进（20/25/30%）、BV 阈值（0.15/0.20/0.25）、AE-L。
5. 手动模式下使用滑杆调节 ISO/曝光/亮度增益。
6. 如需做设备标定，点击“开始标定”，对准灰卡或色卡中心区域，在不同曝光下多次点击“采样”，最后点击“完成拟合”。
7. 标定成功后，“曲线来源”会显示为“已标定”；若失败，请补充更多亮度差异明显的稳定样本。
8. 点击拍照按钮生成原图与伪彩图，并在界面查看最近结果缩略图与曝光量状态。
9. 若出现异常，可在状态区点击“重试”。

### 标定说明文档

- 技术方案：`docs/CameraResponseCalibration_TechnicalDesign.md`
- 用户操作说明：`docs/CameraResponseCalibration_UserGuide.md`

## 参考资料

- [CameraX 文档](https://developer.android.com/media/camera/camerax)
- [Camera2 文档](https://developer.android.com/reference/android/hardware/camera2/package-summary)
- [MediaStore 文档](https://developer.android.com/training/data-storage/shared/media)

## 最近优化记录

### 2026-04（稳定性与架构优化）

- 响应曲线标定：新增灰卡/色卡采样、指数曲线拟合、参数持久化与曲线来源展示。
- 文档补充：新增标定技术方案与用户操作说明文档。
- 黑屏稳定性修复：修正 CameraX `Surface` 释放时机与重复启动竞态，降低冷启动/前后台切换黑屏概率。
- 存储策略升级：从旧外部目录写入迁移到 `MediaStore`，提升 Android 10+ 兼容性。
- 资源释放完善：补齐 `ImageProxy`、`ExecutorService`、`Surface`、`SurfaceTexture`、GL 纹理与 Program 的释放链路。
- 内存峰值优化：拍照链路改为采样解码，回收中间 `Bitmap`，降低卡顿与 OOM 风险。
- 架构整理：推进 `MainActivity + Presenter + Model + ImageRepository` 分层，减少主界面业务耦合。
- 权限流程升级：迁移到 `ActivityResultContracts`，补充永久拒绝后“去设置”引导。
- 参数下发优化：滑杆联动增加防重入与节流（Debug/Release 可配置），降低抖动。
- 渲染降耗：改为 `RENDERMODE_WHEN_DIRTY` + 按帧 `requestRender()`。
- 错误处理统一：增加状态区展示、错误高亮、重试按钮与统一错误入口。
- 依赖治理：统一使用 `libs.versions.toml` 管理 CameraX/Exif/Mockito 版本。
- 测试补强：新增 Presenter 单测，修复 instrumentation 包名断言并补充基础资源校验。
- 自动曝光辅助：引入 `ImageAnalysis` 实时测光与推荐值生成（低分辨率 + 最新帧策略）。
- 自动微调落地：支持开关、3/4/5 稳定帧判定、BV 阈值判定、500ms 节流、手动后暂停。
- 参数策略完善：自动下发采用小步收敛（20/25/30%）并强制预览安全范围。
- 推荐模式 UX：新增模式切换、应用推荐值、AE-L 与可折叠高级参数面板。
- 架构可维护性：将自动微调状态抽取为 `AutoTuneState`，并拆分 Presenter/Activity 方法以降低复杂度。

### 按优先级归类（用于汇报）

- **P0（高优先，稳定性/兼容性）**
  - 启动/前后台切换黑屏修复（`Surface` 生命周期与重复绑定防护）
  - `MediaStore` 存储迁移（替代旧外部目录写法）
  - `ImageProxy/线程池/GL 资源` 生命周期释放完善
  - 权限流程升级（`ActivityResultContracts` + 永久拒绝引导）

- **P1（中优先，性能/体验）**
  - 拍照链路采样解码与中间位图回收，降低内存峰值
  - 滑杆参数下发防重入 + 节流，减少抖动
  - 预览渲染从持续模式改为按帧触发，降低耗电
  - 统一错误状态区 + 重试按钮，提升可恢复性

- **P2（中低优先，工程治理）**
  - 推进 MVP 分层，降低 `MainActivity` 复杂度
  - 响应曲线标定模块化（样本/会话/拟合/持久化）
  - 依赖统一接入 version catalog，减少版本漂移
  - 测试覆盖从模板扩展到可回归用例（单测 + instrumentation）

## 许可证

本项目使用仓库中的 `LICENSE` 许可条款。
