package com.example.camera.debug.stats

import com.example.camera.calibration.model.CalibrationUploadData
import com.example.camera.data.local.CalibrationRecordDao
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 按机型对 HIGH/MEDIUM 记录做 2σ 异常检测并写入 [CalibrationRecord.debugAutoAnomaly]。
 */
object DebugAnomalyMarker {

    suspend fun runAutoMark(dao: CalibrationRecordDao) {
        dao.clearAllAutoAnomalyFlags()
        val all = dao.getAllRecordsDebug()
        val grouped = all
            .filter {
                it.quality == CalibrationUploadData.QUALITY_HIGH ||
                    it.quality == CalibrationUploadData.QUALITY_MEDIUM
            }
            .filter { !it.debugExcluded }
            .groupBy { it.deviceModel }

        for ((_, rows) in grouped) {
            if (rows.size < 3) continue
            val meanA = rows.map { it.paramA }.average()
            val meanB = rows.map { it.paramB }.average()
            val meanR = rows.map { it.rSquared }.average()
            val stdA = popStd(rows.map { it.paramA }, meanA)
            val stdB = popStd(rows.map { it.paramB }, meanB)
            val stdR = popStd(rows.map { it.rSquared }, meanR)

            for (r in rows) {
                val reasons = mutableListOf<String>()
                if (stdA > 0 && abs(r.paramA - meanA) > 2 * stdA) {
                    reasons.add("paramA 偏离>2σ")
                }
                if (stdB > 0 && abs(r.paramB - meanB) > 2 * stdB) {
                    reasons.add("paramB 偏离>2σ")
                }
                if (stdR > 0 && abs(r.rSquared - meanR) > 2 * stdR) {
                    reasons.add("R² 偏离>2σ")
                }
                if (reasons.isNotEmpty()) {
                    dao.updateDebugAutoAnomaly(
                        r.uploadId,
                        true,
                        reasons.joinToString("；"),
                    )
                }
            }
        }
    }

    private fun popStd(values: List<Double>, mean: Double): Double {
        if (values.isEmpty()) return 0.0
        var s = 0.0
        for (v in values) {
            val d = v - mean
            s += d * d
        }
        return sqrt(s / values.size)
    }
}
