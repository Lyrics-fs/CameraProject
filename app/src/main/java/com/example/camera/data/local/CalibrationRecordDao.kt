package com.example.camera.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CalibrationRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: CalibrationRecord)

    @Query(
        """
        SELECT * FROM calibration_records
        WHERE quality IN (:qHigh, :qMed)
          AND syncStatus = :pending
        ORDER BY uploadTime ASC
        """,
    )
    fun getPendingUploads(qHigh: String, qMed: String, pending: String): List<CalibrationRecord>

    @Query(
        """
        SELECT COUNT(*) FROM calibration_records
        WHERE quality IN (:qHigh, :qMed)
          AND syncStatus = :pending
        """,
    )
    fun countPendingUploads(qHigh: String, qMed: String, pending: String): Int

    @Query(
        """
        UPDATE calibration_records
        SET syncStatus = :uploaded, markedUploadedAt = :uploadedAt
        WHERE uploadId = :uploadId
        """,
    )
    fun markAsUploaded(uploadId: String, uploaded: String, uploadedAt: Long): Int

    @Query(
        """
        SELECT * FROM calibration_records
        WHERE syncStatus = :uploaded
        ORDER BY markedUploadedAt DESC
        """,
    )
    fun getUploadHistory(uploaded: String): List<CalibrationRecord>

    @Query(
        """
        SELECT * FROM calibration_records
        WHERE syncStatus = :failed
        ORDER BY lastFailedAt DESC
        """,
    )
    fun getFailedUploads(failed: String): List<CalibrationRecord>

    @Query(
        """
        UPDATE calibration_records
        SET syncStatus = :failed, lastFailedAt = :ts
        WHERE uploadId = :uploadId
        """,
    )
    fun markUploadFailed(uploadId: String, failed: String, ts: Long): Int

    @Query(
        """
        DELETE FROM calibration_records
        WHERE syncStatus = :uploaded
          AND markedUploadedAt > 0
          AND markedUploadedAt < :cutoff
        """,
    )
    fun deleteUploadedOlderThan(uploaded: String, cutoff: Long): Int

    @Query(
        """
        UPDATE calibration_records
        SET syncStatus = :abandoned
        WHERE syncStatus = :failed
          AND lastFailedAt > 0
          AND lastFailedAt < :cutoff
        """,
    )
    fun abandonFailuresOlderThan(abandoned: String, failed: String, cutoff: Long): Int

    @Transaction
    fun runStartupCleanup(uploadedCutoff: Long, failedCutoff: Long) {
        deleteUploadedOlderThan(CalibrationSyncStatus.UPLOADED, uploadedCutoff)
        abandonFailuresOlderThan(
            CalibrationSyncStatus.ABANDONED,
            CalibrationSyncStatus.FAILED,
            failedCutoff,
        )
    }

    // -------------------------------------------------------------------------
    // Debug 后台统计（答辩演示）
    // -------------------------------------------------------------------------

    @Query("SELECT COUNT(*) FROM calibration_records WHERE syncStatus = :status")
    suspend fun countBySyncStatus(status: String): Int

    @Query("SELECT COUNT(*) FROM calibration_records")
    suspend fun countAllRecords(): Int

    @Query("SELECT COUNT(DISTINCT deviceModel) FROM calibration_records")
    suspend fun countDistinctModels(): Int

    @Query(
        """
        SELECT deviceModel, COUNT(*) as cnt, AVG(rSquared) as avgR2, AVG(paramA) as avgA, AVG(paramB) as avgB
        FROM calibration_records
        WHERE quality IN (:qHigh, :qMed) AND debugExcluded = 0
        GROUP BY deviceModel
        ORDER BY cnt DESC
        """,
    )
    suspend fun getModelStatsForDebug(qHigh: String, qMed: String): List<ModelStatsRow>

    @Query(
        """
        SELECT * FROM calibration_records
        ORDER BY uploadTime DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentRecords(limit: Int): List<CalibrationRecord>

    @Query("SELECT * FROM calibration_records ORDER BY uploadTime DESC")
    suspend fun getAllRecordsDebug(): List<CalibrationRecord>

    @Query(
        """
        UPDATE calibration_records
        SET debugExcluded = :excluded, debugExclusionReason = :reason
        WHERE uploadId = :uploadId
        """,
    )
    suspend fun updateDebugExcluded(uploadId: String, excluded: Boolean, reason: String?)

    @Query(
        """
        UPDATE calibration_records
        SET debugAutoAnomaly = :flag, debugAutoAnomalyReason = :reason
        WHERE uploadId = :uploadId
        """,
    )
    suspend fun updateDebugAutoAnomaly(uploadId: String, flag: Boolean, reason: String?)

    @Query(
        """
        UPDATE calibration_records
        SET debugAutoAnomaly = 0, debugAutoAnomalyReason = NULL
        """,
    )
    suspend fun clearAllAutoAnomalyFlags(): Int
}
