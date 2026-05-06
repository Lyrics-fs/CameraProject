package com.example.camera.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class LookupTableUploadMockWebServerTest {

    private lateinit var server: MockWebServer
    private lateinit var api: CalibrationApiService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        api = retrofit.create(CalibrationApiService::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun minimalBody(): MutableMap<String, Any?> {
        val entries = listOf(
            mapOf("dn" to 10.0, "luminance" to 1.0),
            mapOf("dn" to 20.0, "luminance" to 2.0),
            mapOf("dn" to 30.0, "luminance" to 3.0),
            mapOf("dn" to 40.0, "luminance" to 4.0),
            mapOf("dn" to 50.0, "luminance" to 5.0),
        )
        return mutableMapOf(
            "secret" to "x",
            "model" to "test-model",
            "iso" to 100,
            "app_version" to "1.0-test",
            "calibrated_at" to 1_700_000_000_000L,
            "upload_id" to "upload-uuid-test",
            "entries" to entries,
        )
    }

    @Test
    fun uploadTable_http200_code200() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":200,"message":"stored"}"""),
        )
        val resp = api.uploadTable(minimalBody()).execute()
        try {
            assertEquals(200, resp.code())
            val errRaw = try {
                resp.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            val env = UploadTableResponseResolver.resolveUploadTableEnvelope(
                resp.isSuccessful,
                resp.code(),
                resp.body(),
                errRaw,
            )
            assertNotNull(env)
            assertEquals(200, env!!.code)
        } finally {
            RetrofitResponses.closeRawQuietly(resp)
        }
    }

    @Test
    fun uploadTable_http403_jsonInErrorBody() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":403,"message":"forbidden"}"""),
        )
        val resp = api.uploadTable(minimalBody()).execute()
        try {
            assertEquals(403, resp.code())
            val errRaw = resp.errorBody()?.string()
            val env = UploadTableResponseResolver.resolveUploadTableEnvelope(
                resp.isSuccessful,
                resp.code(),
                resp.body(),
                errRaw,
            )
            assertEquals(403, env!!.code)
            assertEquals("forbidden", env.message)
        } finally {
            RetrofitResponses.closeRawQuietly(resp)
        }
    }
}
