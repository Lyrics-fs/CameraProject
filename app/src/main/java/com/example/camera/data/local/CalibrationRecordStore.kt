package com.example.camera.data.local

import android.content.Context
import com.example.camera.calibration.model.CalibrationUploadData
import com.example.camera.calibration.model.CalibrationUploadPolicy
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 标定上传记录在 Room 中的读写封装；单线程执行 DB 操作，供 [com.example.camera.data.CalibrationRepository] 等 Java 层同步调用。
 */
class CalibrationRecordStore(context: Context) {

    private val app = context.applicationContext
    private val db: CalibrationRecordDatabase
        get() = CalibrationRecordDatabase.getInstance(app)
    private val dao: CalibrationRecordDao
        get() = db.calibrationRecordDao()

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "calibration-record-store").apply { isDaemon = true }
    }

    private fun <T> execute(block: () -> T): T =
        io.submit(Callable { block() }).get()

    fun saveCalibrationRecord(record: CalibrationUploadData) = execute {
        val status =
            if (CalibrationUploadPolicy.shouldUpload(record.quality)) {
                CalibrationSyncStatus.PENDING
            } else {
                CalibrationSyncStatus.NOT_ELIGIBLE
            }
        dao.insert(record.toEntity(syncStatus = status))
    }

    fun getPendingUploads(): List<CalibrationUploadData> = execute {
        dao.getPendingUploads(
            CalibrationUploadData.QUALITY_HIGH,
            CalibrationUploadData.QUALITY_MEDIUM,
            CalibrationSyncStatus.PENDING,
        ).toUploadDataList()
    }

    fun countPendingUploads(): Int = execute {
        dao.countPendingUploads(
            CalibrationUploadData.QUALITY_HIGH,
            CalibrationUploadData.QUALITY_MEDIUM,
            CalibrationSyncStatus.PENDING,
        )
    }

    fun markAsUploaded(uploadId: String) = execute {
        dao.markAsUploaded(
            uploadId,
            CalibrationSyncStatus.UPLOADED,
            System.currentTimeMillis(),
        )
    }

    fun getUploadHistory(): List<CalibrationUploadData> = execute {
        dao.getUploadHistory(CalibrationSyncStatus.UPLOADED).toUploadDataList()
    }

    fun getFailedUploads(): List<CalibrationUploadData> = execute {
        dao.getFailedUploads(CalibrationSyncStatus.FAILED).toUploadDataList()
    }

    /** 上传失败时由同步模块调用。 */
    fun markUploadFailed(uploadId: String) = execute {
        dao.markUploadFailed(
            uploadId,
            CalibrationSyncStatus.FAILED,
            System.currentTimeMillis(),
        )
    }
}
