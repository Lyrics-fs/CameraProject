package com.example.camera.data.local

/**
 * 标定记录在本地库中的同步状态（非业务质量档位 HIGH/MEDIUM/LOW）。
 */
object CalibrationSyncStatus {
    /** 待上传（仅 [com.example.camera.calibration.model.CalibrationUploadPolicy] 允许上传的质量会进入此状态）。 */
    const val PENDING = "PENDING"

    /** 已成功同步到云端。 */
    const val UPLOADED = "UPLOADED"

    /** 上传失败，可重试直至被清理逻辑放弃。 */
    const val FAILED = "FAILED"

    /** 失败超过保留期，不再重试。 */
    const val ABANDONED = "ABANDONED"

    /** 质量为 LOW / ABNORMAL 等，不参与云端上传，仅本地留存。 */
    const val NOT_ELIGIBLE = "NOT_ELIGIBLE"
}
