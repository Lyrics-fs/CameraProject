package com.example.camera.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.camera.data.CalibrationShareRepository
import java.util.concurrent.TimeUnit

object UploadManager {

    @JvmStatic
    fun scheduleAutoUpload(context: Context) {
        val app = context.applicationContext
        if (!CalibrationShareRepository.isShareDataEnabledBlocking(app)) {
            return
        }
        val wifiOnly = CalibrationShareRepository.isWifiOnlyAutoUploadBlocking(app)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<UploadCalibrationWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10_000L,
                TimeUnit.MILLISECONDS,
            )
            .setInputData(
                Data.Builder()
                    .putBoolean(UploadCalibrationWorker.KEY_FORCE_ANY_NETWORK, false)
                    .build(),
            )
            .addTag(UploadCalibrationWorker.TAG)
            .build()

        WorkManager.getInstance(app).enqueueUniqueWork(
            UploadCalibrationWorker.UNIQUE_WORK_AUTO,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * 用户手动触发：允许蜂窝网络，仍受「已开启数据分享」约束（Worker 内会校验）。
     */
    @JvmStatic
    fun scheduleManualUploadNow(context: Context) {
        val app = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<UploadCalibrationWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10_000L,
                TimeUnit.MILLISECONDS,
            )
            .setInputData(
                Data.Builder()
                    .putBoolean(UploadCalibrationWorker.KEY_FORCE_ANY_NETWORK, true)
                    .build(),
            )
            .addTag(UploadCalibrationWorker.TAG)
            .build()

        WorkManager.getInstance(app).enqueueUniqueWork(
            UploadCalibrationWorker.UNIQUE_WORK_MANUAL,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    @JvmStatic
    fun rescheduleAutoUploadIfPolicyChanged(context: Context) {
        scheduleAutoUpload(context.applicationContext)
    }
}
