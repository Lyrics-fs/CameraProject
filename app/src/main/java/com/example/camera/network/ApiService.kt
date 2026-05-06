package com.example.camera.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiService {

    /** 阿里云 FC：按机型查询云端标定（查表 / 曲线等，由服务端 JSON 决定）。 */
    @GET("query")
    fun queryCalibration(@Query("model") model: String): Call<ResponseBody>

    /** 健康检查。 */
    @GET("ping")
    fun ping(): Call<ResponseBody>

    /** Level2 达标后上报 Debevec 曲线与 K 等（JSON body）。 */
    @POST("upload-curve")
    fun uploadCurve(@Body body: RequestBody): Call<ResponseBody>
}
