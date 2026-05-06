package com.example.camera.model.calibration

data class LookupEntry(
    val dn: Double,
    val luminance: Double,
)

data class LookupTable(
    val modelName: String = "",
    val entries: List<LookupEntry> = emptyList(),
    val isoUsed: Int = 100,
    val calibratedAt: Long = System.currentTimeMillis(),
    /** 非空时表示来源说明（如启动时云端同步写入的「云端查表」）；旧版 JSON 无该字段时为 null。 */
    val source: String? = null,
) {
    val sortedEntries: List<LookupEntry>
        get() = entries.sortedBy { it.dn }

    fun isAvailable(): Boolean = entries.size >= 3

    /**
     * 线性插值查表
     * @param dn 灰卡区域平均像素值 0-255
     * @return 亮度 cd/m²
     */
    fun lookup(dn: Double): Double? {
        if (entries.isEmpty()) return null
        val sorted = sortedEntries

        if (dn <= sorted.first().dn) return sorted.first().luminance
        if (dn >= sorted.last().dn) return sorted.last().luminance

        val upperIndex = sorted.indexOfFirst { it.dn > dn }
        if (upperIndex <= 0) return sorted.last().luminance

        val lower = sorted[upperIndex - 1]
        val upper = sorted[upperIndex]
        val denom = upper.dn - lower.dn
        if (denom <= 0.0) return lower.luminance

        val ratio = (dn - lower.dn) / denom
        return lower.luminance + ratio * (upper.luminance - lower.luminance)
    }
}
