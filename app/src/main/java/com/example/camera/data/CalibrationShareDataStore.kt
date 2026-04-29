package com.example.camera.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.calibrationShareDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "calibration_share",
)

private val KEY_NEVER_PROMPT_AGAIN = booleanPreferencesKey("never_prompt_again")
private val KEY_SHARE_DATA_ENABLED = booleanPreferencesKey("share_data_enabled")
private val KEY_CONSENT_GRANTED = booleanPreferencesKey("consent_granted")
private val KEY_MEDIUM_HIGH_COMPLETION_SEQ = intPreferencesKey("medium_high_completion_seq")
private val KEY_DENIED_AT_COMPLETION_SEQ = intPreferencesKey("denied_at_completion_seq")
private val KEY_WIFI_ONLY_AUTO_UPLOAD = booleanPreferencesKey("wifi_only_auto_upload")

/**
 * 标定数据分享：用户选择与设置（DataStore，无个人标识字段）。
 */
class CalibrationShareRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dataStore = appContext.calibrationShareDataStore

    data class CompletionOutcome(
        val completionSeq: Int,
        val shouldShowDialog: Boolean,
    )

    /**
     * 在一次 HIGH/MEDIUM 标定完成后调用：递增 seq，并判断是否应弹出授权询问。
     */
    suspend fun onMediumHighCalibrationCompleted(): CompletionOutcome {
        var completionSeq = 0
        var shouldShow = false
        dataStore.edit { prefs ->
            completionSeq = (prefs[KEY_MEDIUM_HIGH_COMPLETION_SEQ] ?: 0) + 1
            prefs[KEY_MEDIUM_HIGH_COMPLETION_SEQ] = completionSeq
            val never = prefs[KEY_NEVER_PROMPT_AGAIN] ?: false
            val deniedAt = prefs[KEY_DENIED_AT_COMPLETION_SEQ] ?: 0
            if (never) {
                shouldShow = false
                return@edit
            }
            shouldShow = when {
                deniedAt == 0 -> completionSeq == 1
                else -> completionSeq >= deniedAt + 2
            }
        }
        return CompletionOutcome(completionSeq, shouldShow)
    }

    suspend fun markDeniedAfterPrompt() {
        dataStore.edit { prefs ->
            val seq = prefs[KEY_MEDIUM_HIGH_COMPLETION_SEQ] ?: 0
            prefs[KEY_DENIED_AT_COMPLETION_SEQ] = seq
        }
    }

    suspend fun markNeverPromptAgain() {
        dataStore.edit { prefs ->
            prefs[KEY_NEVER_PROMPT_AGAIN] = true
        }
    }

    suspend fun applyAllowChoice() {
        dataStore.edit { prefs ->
            prefs[KEY_CONSENT_GRANTED] = true
            prefs[KEY_SHARE_DATA_ENABLED] = true
            prefs[KEY_DENIED_AT_COMPLETION_SEQ] = 0
        }
    }

    suspend fun isShareDataEnabled(): Boolean =
        dataStore.data.map { it[KEY_SHARE_DATA_ENABLED] ?: false }.first()

    suspend fun isConsentGranted(): Boolean =
        dataStore.data.map { it[KEY_CONSENT_GRANTED] ?: false }.first()

    /** 自动同步是否仅在非计费网络（通常为 Wi‑Fi）下执行；默认开启。 */
    suspend fun isWifiOnlyAutoUpload(): Boolean =
        dataStore.data.map { it[KEY_WIFI_ONLY_AUTO_UPLOAD] ?: true }.first()

    suspend fun setWifiOnlyAutoUpload(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_WIFI_ONLY_AUTO_UPLOAD] = enabled
        }
    }

    suspend fun setShareDataEnabledFromSettings(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SHARE_DATA_ENABLED] = enabled
            prefs[KEY_CONSENT_GRANTED] = enabled
            if (!enabled) {
                prefs[KEY_DENIED_AT_COMPLETION_SEQ] = 0
            }
        }
    }

    suspend fun resetAuthorization() {
        dataStore.edit { prefs ->
            prefs[KEY_NEVER_PROMPT_AGAIN] = false
            prefs[KEY_CONSENT_GRANTED] = false
            prefs[KEY_SHARE_DATA_ENABLED] = false
            prefs[KEY_DENIED_AT_COMPLETION_SEQ] = 0
            prefs.remove(KEY_WIFI_ONLY_AUTO_UPLOAD)
        }
    }

    companion object {
        /** 供 Java 在后台线程调用，避免在主线程阻塞 DataStore。 */
        @JvmStatic
        fun onMediumHighCalibrationCompletedBlocking(context: Context): CompletionOutcome {
            return runBlocking {
                CalibrationShareRepository(context).onMediumHighCalibrationCompleted()
            }
        }

        @JvmStatic
        fun markDeniedAfterPromptBlocking(context: Context) {
            runBlocking { CalibrationShareRepository(context).markDeniedAfterPrompt() }
        }

        @JvmStatic
        fun markNeverPromptAgainBlocking(context: Context) {
            runBlocking { CalibrationShareRepository(context).markNeverPromptAgain() }
        }

        @JvmStatic
        fun applyAllowChoiceBlocking(context: Context) {
            runBlocking { CalibrationShareRepository(context).applyAllowChoice() }
        }

        @JvmStatic
        fun isShareDataEnabledBlocking(context: Context): Boolean {
            return runBlocking { CalibrationShareRepository(context).isShareDataEnabled() }
        }

        @JvmStatic
        fun isConsentGrantedBlocking(context: Context): Boolean {
            return runBlocking { CalibrationShareRepository(context).isConsentGranted() }
        }

        @JvmStatic
        fun setShareDataEnabledFromSettingsBlocking(context: Context, enabled: Boolean) {
            runBlocking { CalibrationShareRepository(context).setShareDataEnabledFromSettings(enabled) }
        }

        @JvmStatic
        fun isWifiOnlyAutoUploadBlocking(context: Context): Boolean {
            return runBlocking { CalibrationShareRepository(context).isWifiOnlyAutoUpload() }
        }

        @JvmStatic
        fun setWifiOnlyAutoUploadBlocking(context: Context, enabled: Boolean) {
            runBlocking { CalibrationShareRepository(context).setWifiOnlyAutoUpload(enabled) }
        }

        @JvmStatic
        fun resetAuthorizationBlocking(context: Context) {
            runBlocking { CalibrationShareRepository(context).resetAuthorization() }
        }
    }
}
