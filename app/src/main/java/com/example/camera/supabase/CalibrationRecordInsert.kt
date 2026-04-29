package com.example.camera.supabase

import com.example.camera.calibration.model.CalibrationUploadData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CalibrationRecordInsert(
    @SerialName("upload_id") val uploadId: String,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("manufacturer") val manufacturer: String? = null,
    @SerialName("android_version") val androidVersion: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("param_a") val paramA: Double,
    @SerialName("param_b") val paramB: Double,
    @SerialName("sample_count") val sampleCount: Int,
    @SerialName("r_squared") val rSquared: Double,
    @SerialName("quality") val quality: String,
    @SerialName("calibration_time") val calibrationTime: Long? = null,
    @SerialName("upload_time") val uploadTime: Long? = null,
)

fun CalibrationUploadData.toSupabaseRow(): CalibrationRecordInsert =
    CalibrationRecordInsert(
        uploadId = uploadId,
        deviceModel = deviceModel,
        manufacturer = manufacturer,
        androidVersion = androidVersion,
        appVersion = appVersion,
        paramA = paramA,
        paramB = paramB,
        sampleCount = sampleCount,
        rSquared = rSquared,
        quality = quality,
        calibrationTime = calibrationTime,
        uploadTime = uploadTime,
    )
