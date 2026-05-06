package com.example.camera.network

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class UploadTableResponseResolverTest {

    @Test
    fun parseFcJsonEnvelope_acceptsStringCode() {
        val env = UploadTableResponseResolver.parseFcJsonEnvelope("""{"code":"200","msg":"ok"}""")
        assertNotNull(env)
        assertEquals(200, env!!.code)
        assertEquals("ok", env.message)
    }

    @Test
    fun resolve_prefersBodyOverError() {
        val body = Gson().fromJson("""{"code":200,"message":"done"}""", BaseResponse::class.java)
        val env = UploadTableResponseResolver.resolveUploadTableEnvelope(
            true,
            200,
            body,
            """{"code":403,"message":"ignored"}""",
        )
        assertEquals(200, env!!.code)
    }

    @Test
    fun resolve_http200_emptyMeansSuccess() {
        val env = UploadTableResponseResolver.resolveUploadTableEnvelope(true, 200, null, null)
        assertNotNull(env)
        assertEquals(200, env!!.code)
    }

    @Test
    fun resolve_reads403FromErrorBody() {
        val err = """{"code":403,"message":"bad secret"}"""
        val env = UploadTableResponseResolver.resolveUploadTableEnvelope(false, 403, null, err)
        assertEquals(403, env!!.code)
        assertEquals("bad secret", env.message)
    }

    @Test
    fun retrofit_errorBody_with403_json() {
        val errBody = """{"code":403,"message":"nope"}"""
            .toResponseBody("application/json; charset=utf-8".toMediaType())
        val resp = Response.error<BaseResponse>(403, errBody)
        val errRaw = resp.errorBody()?.string()
        val env = UploadTableResponseResolver.resolveUploadTableEnvelope(
            resp.isSuccessful,
            resp.code(),
            resp.body(),
            errRaw,
        )
        assertEquals(403, env!!.code)
    }
}
