package com.example.camera.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Level1 查表上传入队：**仅非计费网络（通常为 Wi‑Fi）** + 指数退避重试，避免仅依赖保存瞬间的 Retrofit 异步回调。
 */
object LookupTableUploadScheduler {

    const val UNIQUE_WORK_NAME = "lookup_table_upload_wifi_unmetered"

    /**
     * 在保存查表或用户点「再次上传」后调用；若未配置 LAB_UPLOAD_SECRET 或无可上传数据，Worker 内会快速 success，不弹错误。
     */
    @JvmStatic
    fun enqueue(context: Context) {
        val app = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val request = OneTimeWorkRequestBuilder<UploadLookupTableWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10_000L,
                TimeUnit.MILLISECONDS,
            )
            .addTag(UploadLookupTableWorker.TAG)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
