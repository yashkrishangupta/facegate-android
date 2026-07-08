package com.facegate

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.facegate.pipeline.AttendancePipeline
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject


@HiltAndroidApp
class FaceGateApp : Application(), Configuration.Provider {

    @Inject
    lateinit var pipeline: AttendancePipeline

    // Lets WorkManager construct @HiltWorker workers (e.g. AttendanceSyncWorker)
    // with their Hilt-injected dependencies instead of a no-arg constructor.
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

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
    }
}