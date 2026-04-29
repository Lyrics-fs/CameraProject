package com.example.camera

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.camera.data.CalibrationRepository
import com.example.camera.data.CalibrationShareRepository
import com.example.camera.sync.StandardCurveSync
import com.example.camera.databinding.ActivitySettingsBinding
import com.example.camera.upload.UploadCalibrationWorker
import com.example.camera.upload.UploadManager
import com.example.camera.BuildConfig

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.settingsToolbar)
        binding.settingsToolbar.setNavigationOnClickListener { finish() }

        binding.switchShareData.isChecked =
            CalibrationShareRepository.isShareDataEnabledBlocking(this)

        binding.switchShareData.setOnCheckedChangeListener { _, isChecked ->
            CalibrationShareRepository.setShareDataEnabledFromSettingsBlocking(this, isChecked)
            if (isChecked) {
                UploadManager.scheduleAutoUpload(this)
            }
            refreshPendingUploadLabel()
        }

        binding.switchWifiOnlyAutoUpload.isChecked =
            CalibrationShareRepository.isWifiOnlyAutoUploadBlocking(this)
        binding.switchWifiOnlyAutoUpload.setOnCheckedChangeListener { _, isChecked ->
            CalibrationShareRepository.setWifiOnlyAutoUploadBlocking(this, isChecked)
            UploadManager.rescheduleAutoUploadIfPolicyChanged(this)
        }

        binding.btnUploadNow.setOnClickListener {
            if (!CalibrationShareRepository.isShareDataEnabledBlocking(this)) {
                Toast.makeText(this, R.string.settings_upload_need_share, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            UploadManager.scheduleManualUploadNow(this)
            Toast.makeText(this, R.string.settings_upload_enqueued_toast, Toast.LENGTH_SHORT).show()
        }

        if (BuildConfig.DEBUG) {
            binding.btnDebugStats.visibility = View.VISIBLE
            binding.btnDebugStats.setOnClickListener {
                val cn = ComponentName(
                    packageName,
                    "com.example.camera.debug.stats.DebugStatsDashboardActivity",
                )
                startActivity(Intent().setComponent(cn))
            }
        }

        binding.btnRestoreCurve.setOnClickListener {
            CalibrationRepository(this).restoreDefaultCurve()
            sendBroadcast(
                Intent(StandardCurveSync.ACTION_CURVE_PROFILE_UPDATED).setPackage(packageName),
            )
            Toast.makeText(this, R.string.settings_restore_curve_done, Toast.LENGTH_SHORT).show()
        }

        binding.btnResetShareAuth.setOnClickListener {
            CalibrationShareRepository.resetAuthorizationBlocking(this)
            binding.switchShareData.isChecked = false
            binding.switchWifiOnlyAutoUpload.isChecked = true
            Toast.makeText(this, R.string.settings_reset_auth_done, Toast.LENGTH_SHORT).show()
            refreshPendingUploadLabel()
            UploadManager.rescheduleAutoUploadIfPolicyChanged(this)
        }

        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(UploadCalibrationWorker.TAG)
            .observe(this) { infos -> binding.textUploadStatus.text = summarizeWorkStatus(infos) }

        refreshPendingUploadLabel()
    }

    override fun onResume() {
        super.onResume()
        refreshPendingUploadLabel()
    }

    private fun refreshPendingUploadLabel() {
        val n = CalibrationRepository(this).pendingUploadCount
        binding.textPendingUploads.text = if (n == 0) {
            getString(R.string.settings_pending_uploads_zero)
        } else {
            getString(R.string.settings_pending_uploads_count, n)
        }
    }

    private fun summarizeWorkStatus(infos: List<WorkInfo>): String {
        if (infos.isEmpty()) {
            return getString(R.string.settings_upload_status_idle)
        }
        val states = infos.map { it.state }.toSet()
        when {
            states.any { it == WorkInfo.State.RUNNING } -> {
                val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                val p = running?.progress ?: androidx.work.Data.EMPTY
                val phase = p.getString(UploadCalibrationWorker.KEY_PROGRESS_PHASE)
                if (phase == UploadCalibrationWorker.PHASE_UPLOADING) {
                    val pending = p.getInt(UploadCalibrationWorker.KEY_PROGRESS_PENDING, 0)
                    val batch = p.getInt(UploadCalibrationWorker.KEY_PROGRESS_BATCH, 0)
                    val uploaded = p.getInt(UploadCalibrationWorker.KEY_PROGRESS_UPLOADED, 0)
                    return getString(
                        R.string.settings_upload_status_running_detail,
                        pending,
                        batch,
                        uploaded,
                    )
                }
                return getString(R.string.settings_upload_status_running)
            }
            states.any { it == WorkInfo.State.ENQUEUED } ->
                return getString(R.string.settings_upload_status_enqueued)
            states.any { it == WorkInfo.State.BLOCKED } ->
                return getString(R.string.settings_upload_status_blocked)
            states.all { it == WorkInfo.State.SUCCEEDED } ->
                return getString(R.string.settings_upload_status_success)
            states.any { it == WorkInfo.State.FAILED } -> {
                val failed = infos.firstOrNull { it.state == WorkInfo.State.FAILED }
                val msg = failed?.outputData?.getString(UploadCalibrationWorker.KEY_PROGRESS_MESSAGE)
                    ?: getString(R.string.settings_upload_unknown_error)
                return getString(R.string.settings_upload_status_failed, msg)
            }
            else -> return getString(R.string.settings_upload_status_idle)
        }
    }
}
