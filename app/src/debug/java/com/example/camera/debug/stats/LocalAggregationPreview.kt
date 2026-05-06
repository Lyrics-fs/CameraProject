package com.example.camera.debug.stats

import com.example.camera.calibration.model.CalibrationUploadData
import com.example.camera.data.local.CalibrationRecord
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 与云端聚合思路一致的本地预览（2σ 剔除后取平均 a、b）。
 */
object LocalAggregationPreview {

    data class PreviewRow(
        val deviceModel: String,
        val eligibleCount: Int,
        val after2SigmaCount: Int,
        val defaultA: Double,
        val defaultB: Double,
        val confidenceHint: Double,
    )

    fun previewAllModels(records: List<CalibrationRecord>): List<PreviewRow> =
        records.asSequence()
            .filter { it.quality == CalibrationUploadData.QUALITY_HIGH || it.quality == CalibrationUploadData.QUALITY_MEDIUM }
            .filter { !it.debugExcluded && !it.debugAutoAnomaly }
            .map { it.deviceModel }
            .distinct()
            .mapNotNull { model -> previewForModel(records, model) }
            .sortedByDescending { it.eligibleCount }
            .toList()

    fun previewForModel(records: List<CalibrationRecord>, deviceModel: String): PreviewRow? {
        val pool = records.filter {
            it.deviceModel == deviceModel &&
                (it.quality == CalibrationUploadData.QUALITY_HIGH || it.quality == CalibrationUploadData.QUALITY_MEDIUM) &&
                !it.debugExcluded &&
                !it.debugAutoAnomaly
        }
        if (pool.size < 5) return null

        val meanA = pool.map { it.paramA }.average()
        val meanB = pool.map { it.paramB }.average()
        val stdA = popStd(pool.map { it.paramA }, meanA)
        val stdB = popStd(pool.map { it.paramB }, meanB)

        val filtered = pool.filter { r ->
            (stdA == 0.0 || abs(r.paramA - meanA) <= 2.0 * stdA) &&
                (stdB == 0.0 || abs(r.paramB - meanB) <= 2.0 * stdB)
        }
        val used = if (filtered.size >= 3) filtered else pool
        val fa = used.map { it.paramA }.average()
        val fb = used.map { it.paramB }.average()
        val n = used.size
        val conf = kotlin.math.min(1.0, n / 50.0)
        return PreviewRow(
            deviceModel = deviceModel,
            eligibleCount = pool.size,
            after2SigmaCount = used.size,
            defaultA = fa,
            defaultB = fb,
            confidenceHint = conf,
        )
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
