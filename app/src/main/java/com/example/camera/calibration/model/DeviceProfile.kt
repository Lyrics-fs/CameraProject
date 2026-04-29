package com.example.camera.calibration.model

import com.google.gson.annotations.SerializedName

/**
 * 某机型在云端聚合后的**标准（默认）响应曲线**参数，用于客户端拉取或增量更新。
 *
 * **隐私**：仅描述机型维度的统计曲线与置信度，不包含任何用户级标识或行为轨迹。
 *
 * @property deviceModel 与上传数据中的设备型号对齐（如 Build.MODEL 规范化形式）。
 * @property version 曲线版本号，单调递增；客户端可用作增量同步游标。
 * @property defaultA 聚合后的曲线参数 a。
 * @property defaultB 聚合后的曲线参数 b。
 * @property confidence 置信度 ∈ [0, 1] 为宜，由服务端根据聚合样本量等策略给出。
 * @property sampleCount 参与聚合的标定样本条数（或等价权重）。
 * @property updatedAt 服务端最后更新时间戳（毫秒，Unix epoch）。
 */
data class DeviceProfile(
    @SerializedName("device_model") val deviceModel: String = "",
    @SerializedName("version") val version: Int = 0,
    @SerializedName("default_a") val defaultA: Double = 0.0,
    @SerializedName("default_b") val defaultB: Double = 0.0,
    @SerializedName("confidence") val confidence: Double = 0.0,
    @SerializedName("sample_count") val sampleCount: Int = 0,
    @SerializedName("updated_at") val updatedAt: Long = 0L,
)
