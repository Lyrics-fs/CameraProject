package com.example.camera.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters

/**
 * 每周一次的云端标准曲线检查（与冷启动时的 [StandardCurveSync] 互补）。
 */
class CurveSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result {
        StandardCurveSync.run(applicationContext)
        return ListenableWorker.Result.success()
    }

    companion object {
        const val UNIQUE_PERIODIC = "standard_curve_weekly_sync"
        const val TAG = "curve_sync"
    }
}
