package com.example.camera.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.example.camera.data.CalibrationRepository
import com.example.camera.model.calibration.CalibrationFactor
import com.example.camera.model.calibration.LookupEntry
import com.example.camera.model.calibration.LookupTable
import com.example.camera.network.ApiService
import com.example.camera.network.NetworkModule
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayList

/**
 * 启动时静默拉取阿里云 FC [query] / [ping]，将返回的查表或 Debevec 数据写入本地
 *（[CalibrationRepository] / [com.example.camera.model.calibration.LookupTableRepository]）；
 * 失败仅打日志，不打扰用户。
 *
 * **Level1 查表与 `/upload-table` 对齐**：聚合层宜返回与上传体一致或可解析子集，例如
 * `lookup_table` / `lookupTable` / `level1` / `table` / `lookup` 下含 `entries` 数组，
 * 每项 `dn` + `luminance`（或 `L`、`luminance_cd_m2` 等别名），以及 `model`/`model_name`、`iso`/`iso_used`、`calibrated_at`。
 */
object CloudCalibrationSync {

    private const val TAG = "CloudCalibrationSync"
    private const val SRC_LOOKUP = "云端查表"
    private const val SRC_CURVE = "云端用户曲线"

    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!hasInternet(app)) {
            Log.i(TAG, "skip cloud sync: no network")
            return@withContext
        }
        val modelName = Build.MODEL?.trim().orEmpty()
        if (modelName.isEmpty()) {
            Log.w(TAG, "skip cloud sync: empty Build.MODEL")
            return@withContext
        }
        val api = NetworkModule.apiService(app)
        val repo = CalibrationRepository(app)
        runCatching { fetchAndApplyQuery(api, modelName, repo) }
            .onFailure { Log.w(TAG, "query pass 1 failed", it) }
        runCatching {
            val r = api.ping().execute()
            try {
                if (!r.isSuccessful) {
                    Log.w(TAG, "ping http ${r.code()}")
                }
            } finally {
                r.body()?.close()
                r.errorBody()?.close()
            }
        }.onFailure { Log.w(TAG, "ping failed", it) }
        runCatching { fetchAndApplyQuery(api, modelName, repo) }
            .onFailure { Log.w(TAG, "query pass 2 failed", it) }
    }

    private fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun fetchAndApplyQuery(api: ApiService, model: String, repo: CalibrationRepository) {
        val r = api.queryCalibration(model).execute()
        try {
            if (!r.isSuccessful) {
                Log.w(TAG, "query http ${r.code()}")
                return
            }
            val raw = r.body()?.string()?.trim().orEmpty()
            if (raw.isEmpty()) {
                Log.i(TAG, "query empty body")
                return
            }
            applyQueryJson(raw, repo, model)
        } finally {
            r.body()?.close()
            r.errorBody()?.close()
        }
    }

    private fun applyQueryJson(raw: String, repo: CalibrationRepository, defaultModel: String) {
        val root = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (e: Exception) {
            Log.w(TAG, "query JSON root parse failed", e)
            return
        }
        val payload = unwrapPayload(root)
        tryParseLookup(payload, defaultModel)?.let { table ->
            if (table.entries.size >= 3) {
                repo.persistLookupTable(table)
                Log.i(TAG, "cloud lookup applied: ${table.entries.size} entries")
            }
        }
        tryParseDebevec(payload)?.let { (g, factor) ->
            repo.applyCloudDebevecSnapshot(g, factor, SRC_CURVE)
            Log.i(TAG, "cloud debevec applied (hasK=${factor != null})")
        }
    }

    private fun unwrapPayload(root: JsonObject): JsonObject {
        if (root.has("data") && root.get("data").isJsonObject) {
            return root.getAsJsonObject("data")
        }
        if (root.has("result") && root.get("result").isJsonObject) {
            return root.getAsJsonObject("result")
        }
        return root
    }

    private fun tryParseLookup(obj: JsonObject, defaultModel: String): LookupTable? {
        val lookupObj = listOf("lookup_table", "lookupTable", "level1", "table", "lookup")
            .firstNotNullOfOrNull { key ->
                obj.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
            } ?: if (obj.has("entries") && obj.get("entries").isJsonArray) {
            obj
        } else {
            null
        } ?: return null
        val arr = lookupObj.get("entries")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (arr.size() < 3) return null
        val entries = ArrayList<LookupEntry>(arr.size())
        for (el in arr) {
            if (!el.isJsonObject) return null
            val row = el.asJsonObject
            val dn = row.doubleFrom("dn", "DN") ?: return null
            val lum = row.doubleFrom(
                "luminance",
                "L",
                "luminance_cd_m2",
                "cd_m2",
                "cd_m²",
                "brightness_cd_m2",
                "brightness",
            ) ?: return null
            entries.add(LookupEntry(dn, lum))
        }
        val model = lookupObj.stringFrom("modelName", "model_name", "device_model", "model") ?: defaultModel
        val iso = lookupObj.intFrom("isoUsed", "iso_used", "iso") ?: 100
        val at = lookupObj.longFrom("calibratedAt", "calibrated_at", "calibratedAtMillis") ?: System.currentTimeMillis()
        return LookupTable(model, entries, iso, at, SRC_LOOKUP)
    }

    private fun tryParseDebevec(obj: JsonObject): Pair<DoubleArray, CalibrationFactor?>? {
        val node = listOf("debevec", "curve", "debevec_curve", "user_curve")
            .firstNotNullOfOrNull { k ->
                obj.get(k)?.takeIf { it.isJsonObject }?.asJsonObject
            } ?: if (obj.has("g") && obj.get("g").isJsonArray) obj else null
            ?: return null
        val gArr = node.get("g")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (gArr.size() < 256) return null
        val g = DoubleArray(256)
        for (i in 0 until 256) {
            g[i] = gArr.get(i).asDoubleSafe() ?: return null
        }
        val k = node.doubleFrom("k", "K")
        val greyL = node.doubleFrom("grey_l", "greyL", "grey_card_l", "greyCardL", "luminance_L")
        val greyDn = node.doubleFrom("grey_dn", "greyDn", "grey_card_dn", "greyCardDn")
        val ts = node.longFrom("calibrationTimestamp", "calibrated_at", "updated_at")
            ?: System.currentTimeMillis()
        if (k != null && k > 0.0 && greyL != null && greyL > 0.0) {
            val dn = greyDn ?: 128.0
            val factor = CalibrationFactor(
                k,
                dn,
                greyL,
                ts,
                CalibrationFactor.ABS_LEVEL_BRIGHTNESS_METER,
            )
            return if (factor.isValid()) Pair(g, factor) else Pair(g, null)
        }
        return Pair(g, null)
    }

    private fun JsonObject.doubleFrom(vararg keys: String): Double? {
        for (k in keys) {
            val e = get(k) ?: continue
            e.asDoubleSafe()?.let { return it }
        }
        return null
    }

    private fun JsonObject.intFrom(vararg keys: String): Int? {
        for (k in keys) {
            val e = get(k) ?: continue
            if (!e.isJsonPrimitive) continue
            val p = e.asJsonPrimitive
            when {
                p.isNumber -> return p.asInt
                p.isString -> p.asString.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JsonObject.longFrom(vararg keys: String): Long? {
        for (k in keys) {
            val e = get(k) ?: continue
            if (!e.isJsonPrimitive) continue
            val p = e.asJsonPrimitive
            when {
                p.isNumber -> return p.asLong
                p.isString -> p.asString.toLongOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JsonObject.stringFrom(vararg keys: String): String? {
        for (k in keys) {
            val e = get(k) ?: continue
            if (e.isJsonPrimitive && e.asJsonPrimitive.isString) {
                return e.asString
            }
        }
        return null
    }

    private fun JsonElement.asDoubleSafe(): Double? {
        if (!isJsonPrimitive) return null
        val p = asJsonPrimitive
        return when {
            p.isNumber -> p.asDouble
            p.isString -> p.asString.toDoubleOrNull()
            else -> null
        }
    }
}
