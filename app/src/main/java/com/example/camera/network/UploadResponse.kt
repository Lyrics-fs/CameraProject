package com.example.camera.network

import com.google.gson.annotations.SerializedName

data class UploadResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String? = null,
)
