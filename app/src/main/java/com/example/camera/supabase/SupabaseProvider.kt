package com.example.camera.supabase

import android.content.Context
import com.example.camera.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.serialization.json.Json

/**
 * 单例 [SupabaseClient]：使用项目 anon key，请求在 Postgres 中以 `anon` 角色执行（受 RLS 约束）。
 */
object SupabaseProvider {

    @Volatile
    private var client: SupabaseClient? = null

    fun isConfigured(): Boolean {
        val url = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_ANON_KEY
        if (url.isBlank() || key.isBlank()) return false
        if (url.contains("YOUR_PROJECT_REF")) return false
        if (key.contains("YOUR_SUPABASE")) return false
        return true
    }

    @Synchronized
    fun getOrCreate(context: Context): SupabaseClient? {
        if (!isConfigured()) return null
        var c = client
        if (c == null) {
            c = createSupabaseClient(
                supabaseUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
                supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
            ) {
                defaultSerializer = io.github.jan.supabase.serializer.KotlinXSerializer(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    },
                )
                install(Postgrest)
                install(Auth)
            }
            client = c
        }
        return c
    }
}
