package com.example.camera.calibration

import android.content.Context
import android.os.Build
import com.example.camera.BuildConfig
import com.example.camera.calibration.model.CalibrationUploadData
import com.example.camera.model.calibration.CurveParams
import java.util.UUID

object CalibrationUploadDataFactory {

    @JvmStatic
    fun fromCalibration(
        context: Context,
        fitted: CurveParams,
        quality: String,
    ): CalibrationUploadData {
        val now = System.currentTimeMillis()
        return CalibrationUploadData(
            uploadId = UUID.randomUUID().toString(),
            deviceModel = Build.MODEL.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            appVersion = BuildConfig.VERSION_NAME,
            paramA = fitted.a,
            paramB = fitted.b,
            sampleCount = fitted.fitSampleCount,
            rSquared = fitted.rSquared,
            calibrationTime = if (fitted.updatedAt > 0L) fitted.updatedAt else now,
            uploadTime = now,
            quality = quality,
        )
    }
}
