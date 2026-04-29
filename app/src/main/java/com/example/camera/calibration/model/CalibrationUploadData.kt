package com.example.camera.calibration.model

import com.google.gson.annotations.SerializedName

/**
 * 单设备一次标定结果的上传载荷（服务端按 [uploadId] 去重、按机型聚合等）。
 *
 * **隐私**：仅包含设备**产品级**信息（型号、厂商）、Android/API 级别、应用版本号，
 * 以及标定曲线参数与拟合统计量。不包含用户标识、账号、定位、通讯录、IMEI/Android ID 等个人数据。
 *
 * @property uploadId 客户端生成的 UUID 字符串，用于幂等与去重。
 * @property quality 质量档位：HIGH / MEDIUM / LOW / [QUALITY_ABNORMAL]；
 *   **LOW** 与 **ABNORMAL** 仅本地留存，不上传（见 [CalibrationUploadPolicy.shouldUpload]）。
 */
data class CalibrationUploadData(
    @SerializedName("upload_id") val uploadId: String,
    @SerializedName("device_model") val deviceModel: String,
    @SerializedName("manufacturer") val manufacturer: String,
    @SerializedName("android_version") val androidVersion: String,
    @SerializedName("app_version") val appVersion: String,
    @SerializedName("param_a") val paramA: Double,
    @SerializedName("param_b") val paramB: Double,
    @SerializedName("sample_count") val sampleCount: Int,
    @SerializedName("r_squared") val rSquared: Double,
    @SerializedName("calibration_time") val calibrationTime: Long,
    @SerializedName("upload_time") val uploadTime: Long,
    @SerializedName("quality") val quality: String,
) {
    init {
        require(
            quality == QUALITY_HIGH ||
                quality == QUALITY_MEDIUM ||
                quality == QUALITY_LOW ||
                quality == QUALITY_ABNORMAL,
        ) {
            "quality must be one of HIGH, MEDIUM, LOW, ABNORMAL"
        }
    }

    companion object {
        const val QUALITY_HIGH = "HIGH"
        const val QUALITY_MEDIUM = "MEDIUM"
        const val QUALITY_LOW = "LOW"

        /**
         * 拟合参数超出物理/经验范围（如 paramA≤0、paramB∉[0.5,1.5]），仅本地记录，不上传。
         */
        const val QUALITY_ABNORMAL = "ABNORMAL"

        /**
         * 由样本数与 R² 推导质量档位（规则与 [CalibrationUploadPolicy.qualityFor] 一致）。
         */
        fun deriveQuality(sampleCount: Int, rSquared: Double): String =
            CalibrationUploadPolicy.qualityFor(sampleCount, rSquared)

        /** LOW / ABNORMAL 不应上传云端。 */
        fun isEligibleForCloudUpload(quality: String): Boolean =
            quality != QUALITY_LOW && quality != QUALITY_ABNORMAL
    }
}

/**
 * 标定上传质量规则与上传门禁（与业务字段解耦，便于单测）。
 */
object CalibrationUploadPolicy {

    /** 进入 MEDIUM/HIGH 档位的最小样本数（步骤 5.5：低于此视为 LOW，不上传）。 */
    const val MIN_SAMPLE_COUNT_FOR_UPLOAD = 5

    /** 进入 MEDIUM/HIGH 档位的最小 R²（步骤 5.5：低于此视为 LOW，不上传）。 */
    const val MIN_R_SQUARED_FOR_UPLOAD = 0.85

    const val PARAM_B_MIN = 0.5
    const val PARAM_B_MAX = 1.5

    /**
     * 拟合曲线参数异常：a 须为正，b 须在 [[PARAM_B_MIN], [PARAM_B_MAX]]（与云端 CHECK 及聚合前提一致）。
     */
    fun isAbnormalParams(paramA: Double, paramB: Double): Boolean {
        if (!paramA.isFinite() || !paramB.isFinite()) return true
        if (paramA <= 0.0) return true
        if (paramB < PARAM_B_MIN || paramB > PARAM_B_MAX) return true
        return false
    }

    /**
     * - **HIGH**：[sampleCount] ≥ 8 且 [rSquared] ≥ 0.95
     * - **MEDIUM**：[sampleCount] ≥ [MIN_SAMPLE_COUNT_FOR_UPLOAD] 且 [rSquared] ≥ [MIN_R_SQUARED_FOR_UPLOAD]（且不满足 HIGH）
     * - **LOW**：其余情况（含非有限 R²、负样本数等）
     */
    fun qualityFor(sampleCount: Int, rSquared: Double): String {
        if (sampleCount < 0 || !rSquared.isFinite()) {
            return CalibrationUploadData.QUALITY_LOW
        }
        if (sampleCount >= 8 && rSquared >= 0.95) {
            return CalibrationUploadData.QUALITY_HIGH
        }
        if (sampleCount >= MIN_SAMPLE_COUNT_FOR_UPLOAD && rSquared >= MIN_R_SQUARED_FOR_UPLOAD) {
            return CalibrationUploadData.QUALITY_MEDIUM
        }
        return CalibrationUploadData.QUALITY_LOW
    }

    /** LOW / ABNORMAL 不上传云端。 */
    fun shouldUpload(quality: String): Boolean =
        quality != CalibrationUploadData.QUALITY_LOW &&
            quality != CalibrationUploadData.QUALITY_ABNORMAL

    fun shouldUpload(sampleCount: Int, rSquared: Double): Boolean =
        shouldUpload(qualityFor(sampleCount, rSquared))
}
