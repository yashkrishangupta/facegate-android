package com.facegate

import android.app.Application
import android.util.Log
import com.facegate.pipeline.AttendancePipeline
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import javax.inject.Inject


@HiltAndroidApp
class FaceGateApp : Application() {

    @Inject
    lateinit var pipeline: AttendancePipeline

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        if (!OpenCVLoader.initDebug()) {
            Log.e("FaceGateApp", "OpenCV failed to load — alignment will crash")
        } else {
            Log.d("FaceGateApp", "OpenCV loaded successfully")
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