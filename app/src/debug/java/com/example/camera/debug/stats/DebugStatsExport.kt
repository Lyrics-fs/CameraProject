package com.example.camera.debug.stats

import android.content.Context
import com.example.camera.calibration.model.CalibrationUploadData
import com.example.camera.data.local.CalibrationRecord
import com.example.camera.data.local.CalibrationRecordDatabase
import com.example.camera.data.local.CalibrationSyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DebugStatsExport {

    suspend fun exportCsv(context: Context): File = withContext(Dispatchers.IO) {
        val dao = CalibrationRecordDatabase.getInstance(context).calibrationRecordDao()
        val all = dao.getAllRecordsDebug()
        val models = dao.getModelStatsForDebug(
            CalibrationUploadData.QUALITY_HIGH,
            CalibrationUploadData.QUALITY_MEDIUM,
        )
        val pending = dao.countBySyncStatus(CalibrationSyncStatus.PENDING)
        val uploaded = dao.countBySyncStatus(CalibrationSyncStatus.UPLOADED)
        val dir = File(context.cacheDir, "debug_stats").apply { mkdirs() }
        val f = File(dir, "calibration_stats_${System.currentTimeMillis()}.csv")
        f.bufferedWriter(Charsets.UTF_8).use { w ->
            w.appendLine("# summary,pending,$pending,uploaded,$uploaded,total_rows,${all.size}")
            w.appendLine("deviceModel,cnt,avgR2,avgA,avgB")
            for (m in models) {
                w.appendLine(
                    "${csvEsc(m.deviceModel)},${m.cnt}," +
                        "${m.avgR2 ?: ""},${m.avgA ?: ""},${m.avgB ?: ""}",
                )
            }
            w.appendLine("# records")
            w.appendLine(
                "uploadId,deviceModel,quality,syncStatus,rSquared,paramA,paramB,sampleCount,uploadTime," +
                    "debugExcluded,debugExclusionReason,debugAutoAnomaly,debugAutoAnomalyReason",
            )
            for (r in all) {
                w.appendLine(
                    listOf(
                        r.uploadId,
                        r.deviceModel,
                        r.quality,
                        r.syncStatus,
                        r.rSquared,
                        r.paramA,
                        r.paramB,
                        r.sampleCount,
                        r.uploadTime,
                        r.debugExcluded,
                        r.debugExclusionReason ?: "",
                        r.debugAutoAnomaly,
                        r.debugAutoAnomalyReason ?: "",
                    ).joinToString(",") { csvEsc(it.toString()) },
                )
            }
        }
        f
    }

    suspend fun exportJson(context: Context): File = withContext(Dispatchers.IO) {
        val dao = CalibrationRecordDatabase.getInstance(context).calibrationRecordDao()
        val all = dao.getAllRecordsDebug()
        val models = dao.getModelStatsForDebug(
            CalibrationUploadData.QUALITY_HIGH,
            CalibrationUploadData.QUALITY_MEDIUM,
        )
        val root = JSONObject()
        root.put("pendingCount", dao.countBySyncStatus(CalibrationSyncStatus.PENDING))
        root.put("uploadedCount", dao.countBySyncStatus(CalibrationSyncStatus.UPLOADED))
        val ma = JSONArray()
        for (m in models) {
            ma.put(
                JSONObject()
                    .put("deviceModel", m.deviceModel)
                    .put("count", m.cnt)
                    .put("avgR2", m.avgR2 ?: JSONObject.NULL)
                    .put("avgA", m.avgA ?: JSONObject.NULL)
                    .put("avgB", m.avgB ?: JSONObject.NULL),
            )
        }
        root.put("modelAggregates", ma)
        val arr = JSONArray()
        for (r in all) {
            arr.put(recordToJson(r))
        }
        root.put("records", arr)
        val dir = File(context.cacheDir, "debug_stats").apply { mkdirs() }
        val f = File(dir, "calibration_full_${System.currentTimeMillis()}.json")
        f.writeText(root.toString(2), Charsets.UTF_8)
        f
    }

    private fun recordToJson(r: CalibrationRecord): JSONObject =
        JSONObject()
            .put("uploadId", r.uploadId)
            .put("deviceModel", r.deviceModel)
            .put("manufacturer", r.manufacturer)
            .put("quality", r.quality)
            .put("syncStatus", r.syncStatus)
            .put("rSquared", r.rSquared)
            .put("paramA", r.paramA)
            .put("paramB", r.paramB)
            .put("sampleCount", r.sampleCount)
            .put("uploadTime", r.uploadTime)
            .put("debugExcluded", r.debugExcluded)
            .put("debugExclusionReason", r.debugExclusionReason ?: JSONObject.NULL)
            .put("debugAutoAnomaly", r.debugAutoAnomaly)
            .put("debugAutoAnomalyReason", r.debugAutoAnomalyReason ?: JSONObject.NULL)

    private fun csvEsc(s: String): String {
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            return '"' + s.replace("\"", "\"\"") + '"'
        }
        return s
    }
}
