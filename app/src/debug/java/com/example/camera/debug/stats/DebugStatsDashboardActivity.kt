package com.example.camera.debug.stats

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.camera.R
import com.example.camera.calibration.model.CalibrationUploadData
import com.example.camera.data.CalibrationRepository
import com.example.camera.data.local.CalibrationRecord
import com.example.camera.data.local.CalibrationRecordDatabase
import com.example.camera.data.local.CalibrationSyncStatus
import com.example.camera.data.local.ModelStatsRow
import com.example.camera.databinding.ActivityDebugStatsDashboardBinding
import com.example.camera.databinding.DialogDebugRecordDetailBinding
import com.example.camera.databinding.ItemDebugCalibrationRecordBinding
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.Chart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class DebugStatsDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugStatsDashboardBinding
    private val recentAdapter = RecentRecordsAdapter { showRecordDetail(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityDebugStatsDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recyclerRecent.layoutManager = LinearLayoutManager(this)
        binding.recyclerRecent.adapter = recentAdapter
        binding.recyclerRecent.isNestedScrollingEnabled = false

        applyChartTheme(binding.chartModelBar)
        applyChartTheme(binding.chartModelPie)
        applyChartTheme(binding.chartR2Hist)
        applyChartTheme(binding.chartUploadTrend)
        binding.chartModelBar.axisRight.isEnabled = false
        binding.chartR2Hist.axisRight.isEnabled = false
        binding.chartUploadTrend.axisRight.isEnabled = false
        binding.chartModelBar.xAxis.position = XAxis.XAxisPosition.BOTTOM
        binding.chartR2Hist.xAxis.position = XAxis.XAxisPosition.BOTTOM
        binding.chartUploadTrend.xAxis.position = XAxis.XAxisPosition.BOTTOM

        binding.btnAutoAnomaly.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val dao = CalibrationRecordDatabase.getInstance(applicationContext).calibrationRecordDao()
                    DebugAnomalyMarker.runAutoMark(dao)
                }
                Toast.makeText(this@DebugStatsDashboardActivity, R.string.debug_stats_auto_done, Toast.LENGTH_SHORT).show()
                refreshDashboard()
            }
        }
        binding.btnAggPreview.setOnClickListener {
            lifecycleScope.launch {
                val lines = withContext(Dispatchers.IO) {
                    val dao = CalibrationRecordDatabase.getInstance(applicationContext).calibrationRecordDao()
                    val all = dao.getAllRecordsDebug()
                    LocalAggregationPreview.previewAllModels(all)
                }
                val sb = StringBuilder()
                if (lines.isEmpty()) {
                    sb.append("暂无满足条件的机型（需 HIGH/MEDIUM、未排除、未自动异常，且≥5 条）。")
                } else {
                    for (row in lines) {
                        sb.append(row.deviceModel).append('\n')
                        sb.append("  可用 ").append(row.eligibleCount).append(" 条 → 2σ 后 ")
                            .append(row.after2SigmaCount).append(" 条\n")
                        sb.append("  a=").append(String.format("%.4f", row.defaultA))
                            .append("  b=").append(String.format("%.4f", row.defaultB))
                            .append("  置信度提示=").append(String.format("%.2f", row.confidenceHint))
                            .append('\n').append('\n')
                    }
                }
                MaterialAlertDialogBuilder(this@DebugStatsDashboardActivity)
                    .setTitle(R.string.debug_stats_preview_title)
                    .setMessage(sb.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
        binding.btnExportCsv.setOnClickListener {
            lifecycleScope.launch {
                val f = DebugStatsExport.exportCsv(this@DebugStatsDashboardActivity)
                shareExport(f, "text/csv")
            }
        }
        binding.btnExportJson.setOnClickListener {
            lifecycleScope.launch {
                val f = DebugStatsExport.exportJson(this@DebugStatsDashboardActivity)
                shareExport(f, "application/json")
            }
        }

        refreshDashboard()
    }

    private fun refreshDashboard() {
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) {
                val dao = CalibrationRecordDatabase.getInstance(applicationContext).calibrationRecordDao()
                val modelStats = dao.getModelStatsForDebug(
                    CalibrationUploadData.QUALITY_HIGH,
                    CalibrationUploadData.QUALITY_MEDIUM,
                )
                DashboardSnapshot(
                    uploaded = dao.countBySyncStatus(CalibrationSyncStatus.UPLOADED),
                    pending = dao.countBySyncStatus(CalibrationSyncStatus.PENDING),
                    modelKinds = dao.countDistinctModels(),
                    modelStats = modelStats,
                    recent = dao.getRecentRecords(20),
                    allForCharts = dao.getAllRecordsDebug(),
                )
            }
            binding.cardUploaded.text = getString(R.string.debug_stats_card_uploaded, snap.uploaded)
            binding.cardPending.text = getString(R.string.debug_stats_card_pending, snap.pending)
            binding.cardModels.text = getString(R.string.debug_stats_card_models, snap.modelKinds)
            binding.textModelStatsTable.text = formatModelTable(snap.modelStats)
            binding.textCurrentCurve.text = formatCurrentCurve()
            bindModelCharts(snap.modelStats)
            bindR2Histogram(snap.allForCharts)
            bindUploadTrend(snap.allForCharts)
            recentAdapter.submit(snap.recent)
        }
    }

    private fun formatModelTable(rows: List<ModelStatsRow>): String {
        if (rows.isEmpty()) return "（暂无）"
        val sb = StringBuilder()
        for (m in rows) {
            val r2 = m.avgR2?.let { String.format("%.4f", it) } ?: "—"
            val a = m.avgA?.let { String.format("%.4f", it) } ?: "—"
            val b = m.avgB?.let { String.format("%.4f", it) } ?: "—"
            sb.append(m.deviceModel).append('\n')
            sb.append("  n=").append(m.cnt).append("  均R²=").append(r2)
                .append("  均a=").append(a).append("  均b=").append(b).append('\n')
                .append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun formatCurrentCurve(): String {
        val repo = CalibrationRepository(this)
        val hasG = repo.hasDebevecG()
        val abs = repo.isAbsoluteLuminanceCalibrated()
        return buildString {
            append("亮度模型: Debevec g(DN) + 灰卡 K\n")
            append("Debevec g: ").append(if (hasG) "已保存" else "未保存").append('\n')
            append("绝对标定: ").append(if (abs) "已校准" else "未完成").append('\n')
            append("云端 A/B 曲线: 已停用（应用不再读取）")
        }
    }

    private fun applyChartTheme(chart: Chart<*>) {
        val c = Color.WHITE
        chart.description.isEnabled = false
        chart.legend.textColor = c
        when (chart) {
            is BarChart -> {
                chart.axisLeft.textColor = c
                chart.xAxis.textColor = c
            }
            is LineChart -> {
                chart.axisLeft.textColor = c
                chart.xAxis.textColor = c
            }
            is PieChart -> {
                chart.setEntryLabelColor(c)
            }
        }
    }

    private fun bindModelCharts(models: List<ModelStatsRow>) {
        if (models.isEmpty()) {
            binding.chartModelBar.clear()
            binding.chartModelPie.clear()
            binding.chartModelBar.invalidate()
            binding.chartModelPie.invalidate()
            return
        }
        val labels = models.map { shortenLabel(it.deviceModel, 10) }
        val barEntries = models.mapIndexed { i, m -> BarEntry(i.toFloat(), m.cnt.toFloat()) }
        val barSet = BarDataSet(barEntries, getString(R.string.debug_stats_chart_models_bar))
        barSet.colors = ColorTemplate.MATERIAL_COLORS.toList()
        barSet.valueTextColor = Color.WHITE
        val barData = BarData(barSet)
        barData.barWidth = 0.55f
        binding.chartModelBar.data = barData
        binding.chartModelBar.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        binding.chartModelBar.xAxis.labelRotationAngle = -35f
        binding.chartModelBar.xAxis.granularity = 1f
        binding.chartModelBar.invalidate()

        val pieEntries = models.map { m ->
            PieEntry(m.cnt.toFloat(), shortenLabel(m.deviceModel, 14))
        }
        val pieSet = PieDataSet(pieEntries, "")
        pieSet.colors = ColorTemplate.MATERIAL_COLORS.toList()
        pieSet.sliceSpace = 2f
        val pieData = PieData(pieSet)
        pieData.setValueTextSize(10f)
        pieData.setValueTextColor(Color.WHITE)
        binding.chartModelPie.data = pieData
        binding.chartModelPie.setUsePercentValues(false)
        binding.chartModelPie.setDrawEntryLabels(true)
        binding.chartModelPie.invalidate()
    }

    private fun bindR2Histogram(all: List<CalibrationRecord>) {
        val bins = IntArray(10)
        for (r in all) {
            if (r.quality != CalibrationUploadData.QUALITY_HIGH &&
                r.quality != CalibrationUploadData.QUALITY_MEDIUM
            ) {
                continue
            }
            if (r.debugExcluded) continue
            val idx = r2Bin(r.rSquared)
            if (idx >= 0) bins[idx]++
        }
        val labels = (0 until 10).map { i ->
            val lo = i / 10.0
            val hi = (i + 1) / 10.0
            String.format("%.1f–%.1f", lo, hi)
        }
        val entries = bins.mapIndexed { i, c -> BarEntry(i.toFloat(), c.toFloat()) }
        val set = BarDataSet(entries, "R² 区间条数")
        set.colors = ColorTemplate.COLORFUL_COLORS.toList()
        set.valueTextColor = Color.WHITE
        val data = BarData(set)
        data.barWidth = 0.8f
        binding.chartR2Hist.data = data
        binding.chartR2Hist.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        binding.chartR2Hist.invalidate()
    }

    private fun r2Bin(r2: Double): Int {
        if (!r2.isFinite() || r2 < 0.0) return -1
        val idx = kotlin.math.floor(r2 * 10.0).toInt().coerceIn(0, 9)
        return idx
    }

    private fun bindUploadTrend(all: List<CalibrationRecord>) {
        val zone = ZoneId.systemDefault()
        val end = LocalDate.now(zone)
        val start = end.minusDays(29)
        val counts = LongArray(30)
        for (r in all) {
            val d = Instant.ofEpochMilli(r.uploadTime).atZone(zone).toLocalDate()
            if (d.isBefore(start) || d.isAfter(end)) continue
            val idx = ChronoUnit.DAYS.between(start, d).toInt()
            if (idx in counts.indices) counts[idx]++
        }
        val dayFmt = DateTimeFormatter.ofPattern("MM-dd")
        val labels = (0 until 30).map { i -> start.plusDays(i.toLong()).format(dayFmt) }
        val entries = counts.mapIndexed { i, c -> Entry(i.toFloat(), c.toFloat()) }
        val set = LineDataSet(entries, "条数/天")
        set.setDrawCircles(true)
        set.circleRadius = 3.5f
        set.lineWidth = 2f
        set.setColor(ContextCompat.getColor(this, R.color.accent_blue))
        set.valueTextColor = Color.WHITE
        set.setCircleColor(ContextCompat.getColor(this, R.color.accent_blue))
        val lineData = LineData(set)
        binding.chartUploadTrend.data = lineData
        binding.chartUploadTrend.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        binding.chartUploadTrend.axisLeft.axisMinimum = 0f
        binding.chartUploadTrend.invalidate()
    }

    private fun shortenLabel(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1) + "…"

    private fun shareExport(file: java.io.File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(send, getString(R.string.debug_stats_export_shared)))
    }

    private fun showRecordDetail(record: CalibrationRecord) {
        val dialogBinding = DialogDebugRecordDetailBinding.inflate(layoutInflater)
        dialogBinding.textDetailBody.text = buildRecordDetailText(record)
        dialogBinding.switchInvalid.isChecked = record.debugExcluded
        dialogBinding.editInvalidReason.setText(record.debugExclusionReason.orEmpty())
        if (record.debugAutoAnomaly && !record.debugAutoAnomalyReason.isNullOrBlank()) {
            dialogBinding.textAutoFlag.visibility = View.VISIBLE
            dialogBinding.textAutoFlag.text = getString(R.string.debug_stats_flag_auto, record.debugAutoAnomalyReason)
        } else {
            dialogBinding.textAutoFlag.visibility = View.GONE
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.debug_stats_detail_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.debug_stats_dialog_close, null)
            .setPositiveButton(R.string.debug_stats_dialog_save) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val dao = CalibrationRecordDatabase.getInstance(applicationContext).calibrationRecordDao()
                        val excluded = dialogBinding.switchInvalid.isChecked
                        val reason = dialogBinding.editInvalidReason.text?.toString()?.trim().orEmpty()
                        dao.updateDebugExcluded(
                            record.uploadId,
                            excluded,
                            if (excluded) reason.ifBlank { null } else null,
                        )
                    }
                    refreshDashboard()
                }
            }
            .create()
        dialog.show()
    }

    private fun buildRecordDetailText(r: CalibrationRecord): String {
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        return buildString {
            append("uploadId: ").append(r.uploadId).append('\n')
            append("机型: ").append(r.deviceModel).append('\n')
            append("厂商: ").append(r.manufacturer).append('\n')
            append("质量: ").append(r.quality).append("  同步: ").append(r.syncStatus).append('\n')
            append("R²: ").append(r.rSquared).append("  a: ").append(r.paramA).append("  b: ").append(r.paramB).append('\n')
            append("样本数: ").append(r.sampleCount).append('\n')
            append("标定时间: ").append(df.format(r.calibrationTime)).append('\n')
            append("记录时间: ").append(df.format(r.uploadTime)).append('\n')
            if (r.debugExcluded && !r.debugExclusionReason.isNullOrBlank()) {
                append('\n').append(getString(R.string.debug_stats_flag_manual, r.debugExclusionReason))
            }
        }
    }

    private data class DashboardSnapshot(
        val uploaded: Int,
        val pending: Int,
        val modelKinds: Int,
        val modelStats: List<ModelStatsRow>,
        val recent: List<CalibrationRecord>,
        val allForCharts: List<CalibrationRecord>,
    )

    private inner class RecentRecordsAdapter(
        private val onPick: (CalibrationRecord) -> Unit,
    ) : RecyclerView.Adapter<RecentRecordsAdapter.VH>() {

        private var items: List<CalibrationRecord> = emptyList()

        fun submit(list: List<CalibrationRecord>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemDebugCalibrationRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(private val b: ItemDebugCalibrationRecordBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(r: CalibrationRecord) {
                b.textTitle.text = "${r.deviceModel} · ${r.quality}"
                val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                b.textSub.text =
                    "R²=${String.format("%.3f", r.rSquared)}  a=${String.format("%.3f", r.paramA)}  b=${String.format("%.3f", r.paramB)}  ·  ${df.format(r.uploadTime)}"
                val flags = mutableListOf<String>()
                if (r.debugExcluded) {
                    flags.add(
                        getString(
                            R.string.debug_stats_flag_manual,
                            r.debugExclusionReason?.ifBlank { "（未填原因）" } ?: "（未填原因）",
                        ),
                    )
                }
                if (r.debugAutoAnomaly) {
                    flags.add(
                        getString(
                            R.string.debug_stats_flag_auto,
                            r.debugAutoAnomalyReason ?: "—",
                        ),
                    )
                }
                if (flags.isEmpty()) {
                    b.textFlags.visibility = View.GONE
                } else {
                    b.textFlags.visibility = View.VISIBLE
                    b.textFlags.text = flags.joinToString("\n")
                }
                b.root.setOnClickListener { onPick(r) }
            }
        }
    }
}
