package com.example.camera

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.camera.databinding.ActivityPrivacyCalibrationShareBinding

class PrivacyCalibrationShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacyCalibrationShareBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyCalibrationShareBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.privacyToolbar)
        binding.privacyToolbar.setNavigationOnClickListener { finish() }
    }
}
