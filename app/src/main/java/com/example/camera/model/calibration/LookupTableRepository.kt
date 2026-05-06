package com.example.camera.model.calibration

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

class LookupTableRepository(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(table: LookupTable) {
        val json = gson.toJson(table)
        prefs.edit()
            .putString(KEY_CURRENT_TABLE, json)
            .putBoolean(KEY_HAS_TABLE, true)
            .putString(KEY_LOOKUP_TABLE_UPLOAD_ID, UUID.randomUUID().toString())
            .remove(KEY_LOOKUP_TABLE_UPLOADED)
            .apply()
    }

    /**
     * 当前持久化查表对应的云端幂等键（每次 [save] 换新；重传同一查表时保持不变，直至再次 [save]）。
     */
    fun getLookupTableUploadId(): String {
        var id = prefs.getString(KEY_LOOKUP_TABLE_UPLOAD_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_LOOKUP_TABLE_UPLOAD_ID, id).apply()
        }
        return id
    }

    fun load(): LookupTable? {
        if (!prefs.getBoolean(KEY_HAS_TABLE, false)) return null
        val json = prefs.getString(KEY_CURRENT_TABLE, null) ?: return null
        return try {
            gson.fromJson(json, TABLE_TYPE)
        } catch (_: Exception) {
            null
        }
    }

    fun isAvailable(): Boolean = load()?.isAvailable() == true

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** 标记查表数据已成功上传到 FC `/upload-table`（重新 [save] 会清除）。 */
    fun markAsUploaded() {
        prefs.edit().putBoolean(KEY_LOOKUP_TABLE_UPLOADED, true).apply()
    }

    /** 检查当前持久化查表是否已标记为上传成功。 */
    fun isUploaded(): Boolean = prefs.getBoolean(KEY_LOOKUP_TABLE_UPLOADED, false)

    private companion object {
        private const val PREFS_NAME = "lookup_table"
        private const val KEY_CURRENT_TABLE = "current_table"
        private const val KEY_HAS_TABLE = "has_table"
        private const val KEY_LOOKUP_TABLE_UPLOADED = "lookup_table_uploaded"
        private const val KEY_LOOKUP_TABLE_UPLOAD_ID = "lookup_table_upload_id"
        private val TABLE_TYPE = object : TypeToken<LookupTable>() {}.type
    }
}
