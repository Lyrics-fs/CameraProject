package com.example.camera.supabase

import com.example.camera.calibration.model.DeviceProfile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns

object SupabaseCalibrationRemote {

    suspend fun fetchDeviceProfile(client: SupabaseClient, deviceModel: String): DeviceProfile? {
        val rows = client.from("device_profiles").select(
            columns = Columns.ALL,
        ) {
            filter {
                eq("device_model", deviceModel)
            }
            limit(1)
        }.decodeList<DeviceProfileRow>()
        return rows.firstOrNull()?.toDomain()
    }
}
