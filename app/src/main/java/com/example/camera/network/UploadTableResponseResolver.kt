package com.example.camera.network

import com.google.gson.JsonParser

/** FC `/upload-table` 等业务返回的 JSON 业务码与文案。 */
data class FcCodeMessage(val code: Int, val message: String?)

/**
 * 解析 HTTP 响应中的业务 `code`（与 [BaseResponse]、裸 JSON 文本一致），供 [CloudRepository] 与单元测试共用。
 */
object UploadTableResponseResolver {

    /** 从任意 JSON 文本解析 `code`（number 或 string）及 `message`/`msg`/`error`/`detail`。 */
    @JvmStatic
    fun parseFcJsonEnvelope(raw: String?): FcCodeMessage? {
        if (raw.isNullOrBlank()) return null
        return try {
            val o = JsonParser.parseString(raw.trim()).asJsonObject
            val cEl = o.get("code") ?: return null
            val code = when {
                cEl.isJsonPrimitive && cEl.asJsonPrimitive.isNumber -> cEl.asInt
                cEl.isJsonPrimitive && cEl.asJsonPrimitive.isString ->
                    cEl.asString.trim().toIntOrNull() ?: return null
                else -> return null
            }
            val msg = sequenceOf("message", "msg", "error", "detail").mapNotNull { k ->
                o.get(k)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let { p ->
                    when {
                        p.isString -> p.asString
                        p.isNumber -> p.asString
                        else -> null
                    }
                }
            }.firstOrNull()
            FcCodeMessage(code, msg)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 合并 Retrofit 解析后的 [BaseResponse] 与 errorBody 文本，得到统一业务码。
     * HTTP 2xx 且 body 为空、也无 errorBody 时，视为 `code=200`（兼容无 body 的成功响应）。
     */
    @JvmStatic
    fun resolveUploadTableEnvelope(
        httpOk: Boolean,
        httpCode: Int,
        body: BaseResponse?,
        errRaw: String?,
    ): FcCodeMessage? {
        if (body != null) {
            return FcCodeMessage(body.code, body.message)
        }
        parseFcJsonEnvelope(errRaw)?.let { return it }
        if (httpOk && httpCode in 200..299 && errRaw.isNullOrBlank()) {
            return FcCodeMessage(200, null)
        }
        return null
    }
}
