package com.example.camera.supabase

import com.example.camera.calibration.model.DeviceProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceProfileRow(
    @SerialName("device_model") val deviceModel: String = "",
    @SerialName("version") val version: Int = 0,
    @SerialName("default_a") val defaultA: Double = 0.0,
    @SerialName("default_b") val defaultB: Double = 0.0,
    @SerialName("confidence") val confidence: Double? = null,
    @SerialName("sample_count") val sampleCount: Int? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
)

fun DeviceProfileRow.toDomain(): DeviceProfile =
    DeviceProfile(
        deviceModel = deviceModel,
        version = version,
        defaultA = defaultA,
        defaultB = defaultB,
        confidence = confidence ?: 0.0,
        sampleCount = sampleCount ?: 0,
        updatedAt = updatedAt ?: 0L,
    )
