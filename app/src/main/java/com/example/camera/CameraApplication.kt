package com.example.camera

import android.app.Application
import android.util.Log
import com.example.camera.data.local.CalibrationRecordDatabase
import com.example.camera.sync.CurveSyncScheduler
import com.example.camera.sync.StandardCurveSync
import com.example.camera.upload.UploadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CameraApplication : Application() {

    /** Room 等磁盘 IO：默认即在 IO 线程上启动协程，避免误用主线程。 */
    private val applicationIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        applicationIoScope.launch {
            try {
                CalibrationRecordDatabase.getInstance(applicationContext).runStartupMaintenance()
            } catch (t: Throwable) {
                Log.e(TAG, "Calibration DB startup maintenance failed", t)
            }
        }
        UploadManager.scheduleAutoUpload(this)
        CurveSyncScheduler.schedulePeriodic(this)
        applicationScope.launch {
            try {
                StandardCurveSync.run(this@CameraApplication)
            } catch (t: Throwable) {
                Log.e(TAG, "StandardCurveSync.run failed", t)
            }
        }
    }

    private companion object {
        private const val TAG = "CameraApplication"
    }
}
