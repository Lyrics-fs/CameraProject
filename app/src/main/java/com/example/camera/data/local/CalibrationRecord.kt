package com.example.camera.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.camera.calibration.model.CalibrationUploadData

/**
 * 本地持久化的单次标定上传快照（与 [CalibrationUploadData] 字段对齐，并附加同步元数据）。
 *
 * 不包含任何用户身份字段。
 */
@Entity(
    tableName = "calibration_records",
    indices = [
        Index(value = ["deviceModel"]),
        Index(value = ["quality"]),
        Index(value = ["uploadTime"]),
    ],
)
data class CalibrationRecord(
    @PrimaryKey val uploadId: String,
    val deviceModel: String,
    val manufacturer: String,
    val androidVersion: String,
    val appVersion: String,
    val paramA: Double,
    val paramB: Double,
    val sampleCount: Int,
    val rSquared: Double,
    val calibrationTime: Long,
    val uploadTime: Long,
    val quality: String,
    /** 见 [CalibrationSyncStatus]。 */
    val syncStatus: String,
    /** 标记为已上传时的时刻（毫秒）；用于「成功记录保留 30 天」清理。 */
    val markedUploadedAt: Long,
    /** 最近一次标记上传失败的时刻（毫秒）；用于「失败超过 7 天放弃」清理。 */
    val lastFailedAt: Long,
    /** Debug 后台：手动标记不参与聚合/统计展示。 */
    @ColumnInfo(defaultValue = "0") val debugExcluded: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val debugExclusionReason: String? = null,
    /** Debug 后台：自动 2σ 异常标记。 */
    @ColumnInfo(defaultValue = "0") val debugAutoAnomaly: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val debugAutoAnomalyReason: String? = null,
)

fun CalibrationRecord.toUploadData(): CalibrationUploadData =
    CalibrationUploadData(
        uploadId = uploadId,
        deviceModel = deviceModel,
        manufacturer = manufacturer,
        androidVersion = androidVersion,
        appVersion = appVersion,
        paramA = paramA,
        paramB = paramB,
        sampleCount = sampleCount,
        rSquared = rSquared,
        calibrationTime = calibrationTime,
        uploadTime = uploadTime,
        quality = quality,
    )

fun CalibrationUploadData.toEntity(
    syncStatus: String,
    markedUploadedAt: Long = 0L,
    lastFailedAt: Long = 0L,
    debugExcluded: Boolean = false,
    debugExclusionReason: String? = null,
    debugAutoAnomaly: Boolean = false,
    debugAutoAnomalyReason: String? = null,
): CalibrationRecord =
    CalibrationRecord(
        uploadId = uploadId,
        deviceModel = deviceModel,
        manufacturer = manufacturer,
        androidVersion = androidVersion,
        appVersion = appVersion,
        paramA = paramA,
        paramB = paramB,
        sampleCount = sampleCount,
        rSquared = rSquared,
        calibrationTime = calibrationTime,
        uploadTime = uploadTime,
        quality = quality,
        syncStatus = syncStatus,
        markedUploadedAt = markedUploadedAt,
        lastFailedAt = lastFailedAt,
        debugExcluded = debugExcluded,
        debugExclusionReason = debugExclusionReason,
        debugAutoAnomaly = debugAutoAnomaly,
        debugAutoAnomalyReason = debugAutoAnomalyReason,
    )
