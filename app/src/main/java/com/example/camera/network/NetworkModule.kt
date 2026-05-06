package com.example.camera.network

import android.content.Context
import com.example.camera.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {

    @Volatile
    private var retrofit: Retrofit? = null

    @JvmStatic
    fun apiService(context: Context): ApiService {
        return retrofit(context.applicationContext).create(ApiService::class.java)
    }

    @JvmStatic
    fun calibrationApiService(context: Context): CalibrationApiService {
        return retrofit(context.applicationContext).create(CalibrationApiService::class.java)
    }

    @Synchronized
    private fun retrofit(appContext: Context): Retrofit {
        var r = retrofit
        if (r == null) {
            val clientBuilder = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
            if (BuildConfig.DEBUG) {
                val logging = HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
                clientBuilder.addInterceptor(logging)
            }
            r = Retrofit.Builder()
                .baseUrl(CloudRepository.BASE_URL)
                .client(clientBuilder.build())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            retrofit = r
        }
        return r
    }
}
