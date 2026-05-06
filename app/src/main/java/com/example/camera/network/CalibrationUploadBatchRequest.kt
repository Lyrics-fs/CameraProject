package com.example.camera.network

import com.example.camera.calibration.model.CalibrationUploadData
import com.google.gson.annotations.SerializedName

data class CalibrationUploadBatchRequest(
    @SerializedName("records") val records: List<CalibrationUploadData>,
)
