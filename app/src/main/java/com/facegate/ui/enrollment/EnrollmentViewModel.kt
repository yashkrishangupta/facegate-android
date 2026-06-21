package com.facegate.ui.enrollment

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.pipeline.AttendancePipeline
import com.facegate.pipeline.CaptureQualityResult
import com.facegate.pipeline.CaptureRejectReason
import com.facegate.pipeline.EnrollmentResult
import com.facegate.pipeline.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class EnrollmentState {
    object Idle          : EnrollmentState()
    object Processing    : EnrollmentState()
    object Success       : EnrollmentState()
    data class DuplicateFace(val existingName: String) : EnrollmentState()
    data class Failed(val reason: String = "Please try again") : EnrollmentState()
}

sealed class EnrollmentEvent {
    /** Shot passed quality — dot count increased, button re-enabled */
    data class ShotAccepted(val newCount: Int) : EnrollmentEvent()
    /** Shot failed quality — show reason, re-enable button */
    data class ShotRejected(val reason: String) : EnrollmentEvent()
}

@HiltViewModel
class EnrollmentViewModel @Inject constructor(
    private val pipeline: AttendancePipeline,
) : ViewModel() {

    private val _enrollmentState = MutableStateFlow<EnrollmentState>(EnrollmentState.Idle)
    val enrollmentState: StateFlow<EnrollmentState> = _enrollmentState

    // SharedFlow — replay=0 so each event is delivered exactly once
    private val _events = MutableSharedFlow<EnrollmentEvent>(replay = 0, extraBufferCapacity = 8)
    val events: SharedFlow<EnrollmentEvent> = _events

    private val verifiedBitmaps = mutableListOf<Bitmap>()

    // Stored when a duplicate is detected so forceEnroll() can retry without re-asking for details
    private var pendingName  : String = ""
    private var pendingId    : String = ""
    private var pendingClass : String = ""

    // ── Photo capture ────────────────────────────────────────────────────────

    fun capturePhoto(bitmap: Bitmap, rotationDegrees: Int = 0) {
        viewModelScope.launch {
            val result = pipeline.checkCaptureQuality(bitmap, rotationDegrees, forEnrollment = true)
            when (result) {
                is CaptureQualityResult.Pass -> {
                    verifiedBitmaps.add(result.bitmap)
                    if (verifiedBitmaps.size >= 5) {
                        // All 5 done — move to Processing (shows dialog)
                        _enrollmentState.value = EnrollmentState.Processing
                    } else {
                        // Emit event so fragment re-enables button + updates dots
                        _events.emit(EnrollmentEvent.ShotAccepted(verifiedBitmaps.size))
                    }
                }
                is CaptureQualityResult.Fail -> {
                    val reason = when (result.reason) {
                        CaptureRejectReason.NO_FACE        -> "No face detected — look at the camera"
                        CaptureRejectReason.MULTIPLE_FACES -> "Multiple faces — only one person in frame"
                        CaptureRejectReason.QUALITY        -> result.failDetail.toUserMessage()
                    }
                    // Emit event — fragment re-enables button from this
                    _events.emit(EnrollmentEvent.ShotRejected(reason))
                    bitmap.recycle()
                }
            }
        }
    }

    fun capturedCount(): Int = verifiedBitmaps.size

    // ── Enrollment ───────────────────────────────────────────────────────────

    fun enrollStudent(
        studentName : String,
        studentId   : String,
        studentClass: String,
    ) {
        if (verifiedBitmaps.isEmpty()) {
            _enrollmentState.value = EnrollmentState.Failed("No photos captured.")
            return
        }

        // Store so forceEnroll() can retry if admin dismisses the duplicate dialog
        pendingName  = studentName
        pendingId    = studentId
        pendingClass = studentClass

        viewModelScope.launch {
            _enrollmentState.value = EnrollmentState.Processing

            val result = try {
                pipeline.enrollStudentFromEmbeddings(
                    studentId       = studentId,
                    studentName     = studentName,
                    studentClass    = studentClass,
                    verifiedBitmaps = verifiedBitmaps.toList(),
                )
            } catch (e: Exception) {
                clearBitmaps()
                _enrollmentState.value = EnrollmentState.Failed(
                    e.message ?: "Enrollment failed — please try again"
                )
                return@launch
            }

            clearBitmaps()

            _enrollmentState.value = when (result) {
                is EnrollmentResult.Success               -> EnrollmentState.Success
                is EnrollmentResult.DuplicateRisk         -> EnrollmentState.DuplicateFace(result.existingStudentName)
                is EnrollmentResult.NoFaceDetected        -> EnrollmentState.Failed(
                    "No face detected in photos. Retake with good lighting."
                )
                is EnrollmentResult.MultipleFacesDetected -> EnrollmentState.Failed(
                    "Multiple faces detected. Only one person should be in frame."
                )
                is EnrollmentResult.QualityFailed         -> EnrollmentState.Failed(
                    result.reasons.toUserMessage()
                )
            }
        }
    }

    fun forceEnroll() {
        if (verifiedBitmaps.isEmpty() || pendingId.isEmpty()) {
            _enrollmentState.value = EnrollmentState.Failed("Session expired — please retake photos.")
            return
        }
        viewModelScope.launch {
            _enrollmentState.value = EnrollmentState.Processing
            try {
                pipeline.forceEnrollStudent(
                    studentId       = pendingId,
                    studentName     = pendingName,
                    studentClass    = pendingClass,
                    verifiedBitmaps = verifiedBitmaps.toList(),
                )
                clearBitmaps()
                _enrollmentState.value = EnrollmentState.Success
            } catch (e: Exception) {
                clearBitmaps()
                _enrollmentState.value = EnrollmentState.Failed(
                    e.message ?: "Enrollment failed — please try again"
                )
            }
        }
    }

        // ── Reset ────────────────────────────────────────────────────────────────

    fun reset() {
        clearBitmaps()
        _enrollmentState.value = EnrollmentState.Idle
    }

    private fun clearBitmaps() {
        verifiedBitmaps.forEach { if (!it.isRecycled) it.recycle() }
        verifiedBitmaps.clear()
    }

    override fun onCleared() {
        super.onCleared()
        clearBitmaps()
    }
}