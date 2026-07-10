package com.facegate

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.facegate.pipeline.AttendancePipeline
import com.facegate.sync.AttendanceSyncWorker
import com.facegate.sync.DeviceIdManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject


@HiltAndroidApp
class FaceGateApp : Application(), Configuration.Provider {

    @Inject
    lateinit var pipeline: AttendancePipeline

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var deviceIdManager: DeviceIdManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        try {
            System.loadLibrary("opencv_java4")
            Log.d("FaceGateApp", "OpenCV loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("FaceGateApp", "OpenCV failed to load — alignment will crash: ${e.message}")
        }

        appScope.launch {
            try {
                pipeline.init()
            } catch (e: Exception) {
                Log.e("FaceGateApp", "Failed to initialize AttendancePipeline", e)
            }
        }

        // Covers the "already paired" case — e.g. app process restarted after
        // being killed. The freshly-paired case is handled by PairingViewModel
        // calling this same scheduler right after a successful pairing.
        if (deviceIdManager.isPaired()) {
            AttendanceSyncWorker.Scheduler.schedulePeriodicSync(
                context = this,
                roomId = deviceIdManager.getRoomId() ?: "",
            )
        }
    }
}