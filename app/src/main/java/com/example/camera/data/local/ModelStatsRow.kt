package com.example.camera.data.local

/**
 * Debug 统计：按机型聚合（仅 HIGH/MEDIUM 且未手动排除）。
 */
data class ModelStatsRow(
    val deviceModel: String,
    val cnt: Int,
    val avgR2: Double?,
    val avgA: Double?,
    val avgB: Double?,
)
