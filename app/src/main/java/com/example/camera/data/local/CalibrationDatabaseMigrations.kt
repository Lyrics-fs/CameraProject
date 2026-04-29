package com.example.camera.data.local

import androidx.room.migration.Migration

/**
 * Room 版本迁移入口：**升级 [CalibrationRecordDatabase] 的 version 时**在此添加 [Migration]，
 * 并填入 [CalibrationDatabaseMigrations.ALL]，禁止使用仅依赖破坏性重建的方式，以免已有标定记录丢失。
 *
 * 示例（假设从 1 升到 2 且新增一列）：
 * ```
 * private val MIGRATION_1_2 = object : Migration(1, 2) {
 *   override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
 *     db.execSQL(
 *       "ALTER TABLE calibration_records ADD COLUMN new_column INTEGER NOT NULL DEFAULT 0"
 *     )
 *   }
 * }
 *
 * val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
 * ```
 */
object CalibrationDatabaseMigrations {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE calibration_records ADD COLUMN debugExcluded INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE calibration_records ADD COLUMN debugExclusionReason TEXT",
            )
            db.execSQL(
                "ALTER TABLE calibration_records ADD COLUMN debugAutoAnomaly INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE calibration_records ADD COLUMN debugAutoAnomalyReason TEXT",
            )
        }
    }

    @JvmField
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
