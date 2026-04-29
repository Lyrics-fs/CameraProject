package com.example.camera.sync

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.example.camera.R
import com.example.camera.data.CalibrationRepository
import com.example.camera.model.calibration.CurveParams
import com.example.camera.supabase.SupabaseCalibrationRemote
import com.example.camera.supabase.SupabaseProvider
import kotlin.jvm.JvmField

/**
 * 拉取 Supabase [device_profiles] 并写入本地曲线（不覆盖用户已标定曲线）。
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
        val current = repo.loadCurveParams()
        if (current.source == CurveParams.Source.CALIBRATED) {
            repo.setAppliedCloudProfileVersion(
                maxOf(repo.getAppliedCloudProfileVersion(), profile.version),
            )
            repo.markLastCurveSyncNow()
            sendUpdateBroadcast(app)
            return
        }
        repo.tryApplyCloudDeviceProfile(profile)
        repo.markLastCurveSyncNow()
        sendUpdateBroadcast(app)
    }

    private fun maybeShowNoCloudHint(app: Context, repo: CalibrationRepository) {
        val cur = repo.loadCurveParams()
        if (cur.source != CurveParams.Source.PRIOR) {
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
