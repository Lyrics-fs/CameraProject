package com.example.camera.network

import com.example.camera.calibration.model.CalibrationUploadData
import com.example.camera.calibration.model.DeviceProfile
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface ApiService {

    @POST("/api/calibration/upload")
    fun uploadCalibrationData(@Body data: CalibrationUploadData): Call<UploadResponse>

    /**
     * 批量上传：合并多条 [CalibrationUploadData] 为一次请求。
     * 若服务端仅提供单条路径，可将此处改为与 [uploadCalibrationData] 相同 path 并调整后端契约。
     */
    @POST("/api/calibration/upload/batch")
    fun uploadCalibrationBatch(@Body body: CalibrationUploadBatchRequest): Call<UploadResponse>

    @GET("/api/device-profile/{deviceModel}")
    fun getDeviceProfile(@Path("deviceModel") model: String): Call<DeviceProfile>
}
