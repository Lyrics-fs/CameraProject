package com.example.camera.upload

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.example.camera.R
import com.example.camera.model.calibration.LookupTable
import com.example.camera.model.calibration.LookupTableRepository
import com.example.camera.network.CloudRepository
import com.example.camera.network.LookupTableUploadWorkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后台执行 Level1 查表 [CloudRepository.uploadLookupTableBlocking]；失败时按 WorkManager 退避策略重试（网络类错误）。
 */
class UploadLookupTableWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result = withContext(Dispatchers.IO) {
        val repo = LookupTableRepository(applicationContext)
        val table: LookupTable = repo.load() ?: return@withContext ListenableWorker.Result.success()
        if (table.entries.size < 5) {
            return@withContext ListenableWorker.Result.success()
        }
        var model = table.modelName.trim()
        if (model.isEmpty()) {
            model = Build.MODEL?.trim().orEmpty()
        }
        val uploadId = repo.getLookupTableUploadId()
        val result = CloudRepository.uploadLookupTableBlocking(
            applicationContext,
            model,
            table.entries,
            table.isoUsed,
            table.calibratedAt,
            uploadId,
        )
        when (result) {
            LookupTableUploadWorkResult.Success -> {
                repo.markAsUploaded()
                ListenableWorker.Result.success(
                    Data.Builder()
                        .putBoolean(KEY_OUTPUT_UPLOADED, true)
                        .build(),
                )
            }
            LookupTableUploadWorkResult.SkippedNoSecret, LookupTableUploadWorkResult.SkippedInvalidTable ->
                ListenableWorker.Result.success()

            is LookupTableUploadWorkResult.Forbidden -> {
                ListenableWorker.Result.failure(
                    Data.Builder().putString(KEY_OUTPUT_ERROR, result.message).build(),
                )
            }
            is LookupTableUploadWorkResult.BusinessError -> {
                ListenableWorker.Result.failure(
                    Data.Builder().putString(KEY_OUTPUT_ERROR, result.message).build(),
                )
            }
            is LookupTableUploadWorkResult.UnparsedResponse -> {
                val msg = applicationContext.getString(
                    R.string.level1_upload_table_response_unparsed,
                    result.httpCode,
                    result.snippet,
                )
                ListenableWorker.Result.failure(
                    Data.Builder().putString(KEY_OUTPUT_ERROR, msg).build(),
                )
            }
            is LookupTableUploadWorkResult.NetworkError -> {
                if (runAttemptCount < MAX_ATTEMPTS) {
                    ListenableWorker.Result.retry()
                } else {
                    ListenableWorker.Result.failure(
                        Data.Builder()
                            .putString(KEY_OUTPUT_ERROR, result.message)
                            .build(),
                    )
                }
            }
        }
    }

    companion object {
        const val TAG = "lookup_table_upload_worker"
        const val KEY_OUTPUT_UPLOADED = "lookup_uploaded"
        const val KEY_OUTPUT_ERROR = "lookup_upload_error"
        private const val MAX_ATTEMPTS = 5
    }
}
