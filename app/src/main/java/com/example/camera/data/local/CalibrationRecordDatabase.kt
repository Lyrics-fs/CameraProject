package com.example.camera.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** 已上传成功记录：超过此时长（自 [CalibrationRecord.markedUploadedAt] 起）可被清理删除。 */
private const val UPLOAD_SUCCESS_RETENTION_MS: Long = 30L * 86_400_000L

/** 上传失败记录：超过此时长（自 [CalibrationRecord.lastFailedAt] 起）标记为 [CalibrationSyncStatus.ABANDONED]。 */
private const val FAILURE_ABANDON_AFTER_MS: Long = 7L * 86_400_000L

@Database(
    entities = [CalibrationRecord::class],
    version = 2,
    exportSchema = true,
)
abstract class CalibrationRecordDatabase : RoomDatabase() {

    abstract fun calibrationRecordDao(): CalibrationRecordDao

    /**
     * 启动时维护：删除「已成功上传且超过保留期」的行；将「失败过久」的行标记为放弃。
     */
    fun runStartupMaintenance() {
        val now = System.currentTimeMillis()
        runInTransaction {
            calibrationRecordDao().runStartupCleanup(
                uploadedCutoff = now - UPLOAD_SUCCESS_RETENTION_MS,
                failedCutoff = now - FAILURE_ABANDON_AFTER_MS,
            )
        }
    }

    companion object {
        @Volatile
        private var instance: CalibrationRecordDatabase? = null

        fun getInstance(context: Context): CalibrationRecordDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CalibrationRecordDatabase::class.java,
                    "calibration_records.db",
                )
                    .addMigrations(*CalibrationDatabaseMigrations.ALL)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
