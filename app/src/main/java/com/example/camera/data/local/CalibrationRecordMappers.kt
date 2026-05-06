package com.example.camera.data.local

import com.example.camera.calibration.model.CalibrationUploadData

fun List<CalibrationRecord>.toUploadDataList(): List<CalibrationUploadData> =
    map { it.toUploadData() }
