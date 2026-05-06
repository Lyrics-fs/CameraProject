package com.example.camera.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.example.camera.data.CalibrationShareRepository
import com.example.camera.data.local.CalibrationRecordStore
import com.example.camera.network.CalibrationUploadBatchRequest
import com.example.camera.network.NetworkModule
import com.example.camera.network.UploadResponse
import com.example.camera.supabase.SupabaseCalibrationRemote
import com.example.camera.supabase.SupabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

class UploadCalibrationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result = withContext(Dispatchers.IO) {
        if (!CalibrationShareRepository.isShareDataEnabledBlocking(applicationContext)) {
            setProgress(idleProgress())
            return@withContext ListenableWorker.Result.success()
        }

        val store = CalibrationRecordStore(applicationContext)
        val supabase = SupabaseProvider.getOrCreate(applicationContext)
        val legacyApi = if (supabase == null) NetworkModule.apiService(applicationContext) else null
        var totalUploaded = 0

        while (true) {
            val pending = store.getPendingUploads()
            if (pending.isEmpty()) {
                setProgress(
                    Data.Builder()
                        .putString(KEY_PROGRESS_PHASE, PHASE_DONE)
                        .putInt(KEY_PROGRESS_UPLOADED, totalUploaded)
                        .putInt(KEY_PROGRESS_TOTAL, totalUploaded)
                        .build(),
                )
                return@withContext ListenableWorker.Result.success()
            }

            val batch = pending.take(BATCH_SIZE)
            setProgress(
                Data.Builder()
                    .putString(KEY_PROGRESS_PHASE, PHASE_UPLOADING)
                    .putInt(KEY_PROGRESS_PENDING, pending.size)
                    .putInt(KEY_PROGRESS_BATCH, batch.size)
                    .putInt(KEY_PROGRESS_UPLOADED, totalUploaded)
                    .build(),
            )

            if (supabase != null) {
                try {
                    SupabaseCalibrationRemote.insertCalibrationBatch(supabase, batch)
                } catch (e: Exception) {
                    return@withContext handleFailure(
                        store,
                        batch,
                        e.message ?: "supabase error",
                    )
                }
            } else {
                val api = legacyApi
                    ?: return@withContext handleFailure(
                        store,
                        batch,
                        "Supabase 未配置",
                    )
                val call = api.uploadCalibrationBatch(CalibrationUploadBatchRequest(batch))
                val response: Response<UploadResponse> =
                    try {
                        call.execute()
                    } catch (e: Exception) {
                        return@withContext handleFailure(store, batch, e.message ?: "network error")
                    }

                val body = response.body()
                val ok = response.isSuccessful && body != null && body.success
                if (!ok) {
                    val msg = body?.message ?: response.message()
                    return@withContext handleFailure(store, batch, msg)
                }
            }

            batch.forEach { store.markAsUploaded(it.uploadId) }
            totalUploaded += batch.size
        }
        error("upload loop must return before exit")
    }

    private suspend fun handleFailure(
        store: CalibrationRecordStore,
        batch: List<com.example.camera.calibration.model.CalibrationUploadData>,
        message: String,
    ): ListenableWorker.Result {
        return if (runAttemptCount >= MAX_RUN_ATTEMPTS) {
            batch.forEach { store.markUploadFailed(it.uploadId) }
            val err = Data.Builder()
                .putString(KEY_PROGRESS_PHASE, PHASE_FAILED)
                .putString(KEY_PROGRESS_MESSAGE, message)
                .build()
            ListenableWorker.Result.failure(err)
        } else {
            ListenableWorker.Result.retry()
        }
    }

    private fun idleProgress(): Data =
        Data.Builder()
            .putString(KEY_PROGRESS_PHASE, PHASE_IDLE)
            .build()

    companion object {
        const val TAG = "calibration_upload"

        const val KEY_FORCE_ANY_NETWORK = "force_any_network"

        const val KEY_PROGRESS_PHASE = "phase"
        const val KEY_PROGRESS_PENDING = "pending"
        const val KEY_PROGRESS_BATCH = "batch"
        const val KEY_PROGRESS_UPLOADED = "uploaded"
        const val KEY_PROGRESS_TOTAL = "total"
        const val KEY_PROGRESS_MESSAGE = "message"

        const val PHASE_IDLE = "idle"
        const val PHASE_UPLOADING = "uploading"
        const val PHASE_DONE = "done"
        const val PHASE_FAILED = "failed"

        private const val BATCH_SIZE = 50
        private const val MAX_RUN_ATTEMPTS = 3

        const val UNIQUE_WORK_AUTO = "calibration_upload_auto"
        const val UNIQUE_WORK_MANUAL = "calibration_upload_manual"
    }
}
