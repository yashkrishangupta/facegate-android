package com.facegate.ui.attendance

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.pipeline.AttendanceDecision
import com.facegate.pipeline.AttendancePipeline
import com.facegate.pipeline.PipelineFrameStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * All possible states of the attendance camera screen.
 */
sealed class ScanState {
    object Idle       : ScanState()
    object Scanning   : ScanState()
    object Processing : ScanState()
    data class Success(
        val studentName  : String,
        val studentClass : String,
        val initials     : String,
        val markedTime   : String,
    ) : ScanState()
    object Failed : ScanState()
}

/**
 * ATTENDANCE VIEWMODEL — fully wired to real pipeline
 */
@HiltViewModel
class AttendanceViewModel @Inject constructor(
    private val pipeline: AttendancePipeline,
) : ViewModel() {

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState

    // Prevents queuing up multiple processFrame calls while one ML inference is running.
    // ML inference takes 150-400ms; camera delivers frames faster than that.
    @Volatile
    private var isProcessing = false

    // Paused during Success/Failed so the result stays on screen for the full
    // display duration instead of being overwritten by the next camera frame.
    @Volatile
    private var isPaused = false

    // ── Session lifecycle ────────────────────────────────────────────────────

    /**
     * Start an attendance session. Call from Fragment.onResume().
     * Loads enrolled students from DB into memory for the pipeline.
     */
    fun startSession() {
        val sessionId = "SESSION_${System.currentTimeMillis()}"
        viewModelScope.launch {
            pipeline.startSession(sessionId)
        }
    }

    /**
     * End the session. Call from Fragment.onPause() or "End Attendance" button.
     * Clears biometric data from memory.
     */
    fun stopSession() {
        pipeline.endSession()
        _scanState.value = ScanState.Idle
    }

    // ── Main frame processing ────────────────────────────────────────────────

    fun processFrame(bitmap: Bitmap, rotationDegrees: Int = 0) {
        if (isProcessing || isPaused) return
        isProcessing = true

        viewModelScope.launch {
            try {
                val status = pipeline.processFrame(bitmap, rotationDegrees)
                _scanState.value = mapStatusToScanState(status)
            } catch (e: Exception) {
                e.printStackTrace()
                _scanState.value = ScanState.Idle
            } finally {
                isProcessing = false
            }
        }
    }

    /**
     * Map PipelineFrameStatus (pipeline layer) → ScanState (UI layer).
     */
    private fun mapStatusToScanState(status: PipelineFrameStatus): ScanState {
        return when (status) {
            is PipelineFrameStatus.NoSession      -> ScanState.Idle
            is PipelineFrameStatus.NoFace         -> ScanState.Idle
            is PipelineFrameStatus.MultipleFaces  -> ScanState.Idle
            is PipelineFrameStatus.QualityFailed  -> ScanState.Scanning
            is PipelineFrameStatus.Buffering      -> ScanState.Scanning
            is PipelineFrameStatus.Processing     -> ScanState.Processing
            is PipelineFrameStatus.Decision       -> {
                when (val d = status.result.decision) {

                    is AttendanceDecision.Accept -> {
                        isPaused = true
                        ScanState.Success(
                            studentName  = d.studentName,
                            studentClass = "Confidence: ${(d.confidence * 100).toInt()}%",
                            initials     = d.studentName
                                .split(" ")
                                .mapNotNull { it.firstOrNull()?.toString() }
                                .take(2)
                                .joinToString(""),
                            markedTime   = SimpleDateFormat("hh:mm a", Locale.getDefault())
                                .format(Date()),
                        )
                    }

                    is AttendanceDecision.AlreadyMarked -> {
                        isPaused = true
                        ScanState.Success(
                            studentName  = d.studentId,
                            studentClass = "Already marked",
                            initials     = "--",
                            markedTime   = SimpleDateFormat("hh:mm a", Locale.getDefault())
                                .format(Date(d.markedAt)),
                        )
                    }

                    is AttendanceDecision.Reject    -> { isPaused = true; ScanState.Failed }
                    is AttendanceDecision.Ambiguous -> { isPaused = true; ScanState.Failed }
                }
            }
        }
    }

    // ── UI helpers ───────────────────────────────────────────────────────────

    fun resetScan() {
        isPaused = false
        _scanState.value = ScanState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        pipeline.endSession()
    }
}