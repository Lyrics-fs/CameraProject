package com.example.camera.sync

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.example.camera.R
import com.example.camera.data.CalibrationRepository
import com.example.camera.supabase.SupabaseCalibrationRemote
import com.example.camera.supabase.SupabaseProvider
import kotlin.jvm.JvmField
import kotlin.math.max

/**
 * 拉取 Supabase [device_profiles] 以更新同步时间戳；应用亮度已改为 Debevec+g+K，不再写入本地 A/B 曲线。
 */
object StandardCurveSync {

    @JvmField
    val ACTION_CURVE_PROFILE_UPDATED: String = "com.example.camera.action.CURVE_PROFILE_UPDATED"

    @JvmStatic
    suspend fun run(context: Context) {
        val app = context.applicationContext
        val repo = CalibrationRepository(app)
        val client = SupabaseProvider.getOrCreate(app)
        if (client == null) {
            repo.markLastCurveSyncNow()
            sendUpdateBroadcast(app)
            return
        }
        val modelName = Build.MODEL?.trim().orEmpty()
        if (modelName.isEmpty()) {
            repo.markLastCurveSyncNow()
            sendUpdateBroadcast(app)
            return
        }
        val profile = try {
            SupabaseCalibrationRemote.fetchDeviceProfile(client, modelName)
        } catch (_: Exception) {
            null
        }
        if (profile == null) {
            repo.markLastCurveSyncNow()
            if (SupabaseProvider.isConfigured()) {
                maybeShowNoCloudHint(app, repo)
            }
            sendUpdateBroadcast(app)
            return
        }
        repo.setAppliedCloudProfileVersion(max(repo.getAppliedCloudProfileVersion(), profile.version))
        repo.tryApplyCloudDeviceProfile(profile)
        repo.markLastCurveSyncNow()
        sendUpdateBroadcast(app)
    }

    private fun maybeShowNoCloudHint(app: Context, repo: CalibrationRepository) {
        if (repo.hasDebevecG()) {
            return
        }
        if (repo.hasFirstLaunchCloudHintShown()) {
            return
        }
        repo.setFirstLaunchCloudHintShown()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                app,
                R.string.curve_sync_no_cloud_use_prior,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun sendUpdateBroadcast(app: Context) {
        val intent = Intent(ACTION_CURVE_PROFILE_UPDATED).setPackage(app.packageName)
        app.sendBroadcast(intent)
    }
}
