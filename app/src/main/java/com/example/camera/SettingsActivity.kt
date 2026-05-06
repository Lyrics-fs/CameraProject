package com.example.camera

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.camera.data.CalibrationRepository
import com.example.camera.BuildConfig
import com.example.camera.databinding.ActivitySettingsBinding
import com.example.camera.sync.StandardCurveSync

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.settingsToolbar)
        binding.settingsToolbar.setNavigationOnClickListener { finish() }

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
    }
}
