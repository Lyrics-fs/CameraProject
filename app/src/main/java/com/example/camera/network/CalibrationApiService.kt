package com.example.camera.network

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 标定相关 HTTP 接口（与 [ApiService] 共用同一 Retrofit 根地址）。
 * Kotlin 声明以避免与 Java `Map<String, Object>` 在调用端的泛型互操作问题。
 */
interface CalibrationApiService {

    @POST("upload-table")
    fun uploadTable(@Body body: Map<String, @JvmSuppressWildcards Any?>): Call<BaseResponse>
}
