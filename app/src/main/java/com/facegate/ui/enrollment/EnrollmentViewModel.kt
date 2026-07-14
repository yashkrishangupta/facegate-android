package com.facegate.ui.enrollment

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.pipeline.AttendancePipeline
import com.facegate.pipeline.CaptureQualityResult
import com.facegate.pipeline.CaptureRejectReason
import com.facegate.pipeline.EnrollmentResult
import com.facegate.pipeline.StudentEnrollmentInfo
import com.facegate.pipeline.toUserMessage
import com.facegate.storage.TemplateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.facegate.pipeline.PipelineConfig   

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
    private val repository: TemplateRepository,
) : ViewModel() {

    private val _enrollmentState = MutableStateFlow<EnrollmentState>(EnrollmentState.Idle)
    val enrollmentState: StateFlow<EnrollmentState> = _enrollmentState

    // SharedFlow — replay=0 so each event is delivered exactly once
    private val _events = MutableSharedFlow<EnrollmentEvent>(replay = 0, extraBufferCapacity = 8)
    val events: SharedFlow<EnrollmentEvent> = _events

    private val verifiedBitmaps = mutableListOf<Bitmap>()

    // Set via setStudentInfo()/loadExistingStudentInfo() right after the
    // dialog is confirmed (or the pending student's row is loaded) — BEFORE
    // any photo is taken. Used both for the initial submission and forceEnroll().
    private var pendingInfo: StudentEnrollmentInfo? = null

    /** Called once the student-details dialog is confirmed (brand-new student), before the camera opens. */
    fun setStudentInfo(info: StudentEnrollmentInfo) {
        pendingInfo = info
    }

    /**
     * For a student already synced down from the website (PENDING enrollment
     * status — roll number, registration number, gender etc. all already
     * known server-side): loads that existing info instead of re-prompting
     * for details the admin already entered on the website.
     * Returns false if the student can't be found locally (shouldn't happen
     * in practice — the caller navigated here from that exact row).
     */
    suspend fun loadExistingStudentInfo(studentId: String): Boolean {
        val student = repository.getStudentById(studentId) ?: return false
        pendingInfo = StudentEnrollmentInfo(
            name = student.name,
            rollNumber = student.rollNumber.ifBlank { student.studentId },
            registrationNumber = student.registrationNumber.ifBlank { student.rollNumber.ifBlank { student.studentId } },
            studentClass = student.studentClass,
            gender = student.gender?.takeIf { it.isNotBlank() } ?: "Other",
            admissionYear = student.admissionYear ?: java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
            email = student.email,
            phone = student.phone,
        )
        return true
    }

    // ── Photo capture ────────────────────────────────────────────────────────

    fun capturePhoto(bitmap: Bitmap, rotationDegrees: Int = 0) {
        viewModelScope.launch {
            val result = pipeline.checkCaptureQuality(
                bitmap,
                rotationDegrees,
                forEnrollment = true,
                shotIndex = verifiedBitmaps.size,
            )
            when (result) {
                is CaptureQualityResult.Pass -> {
                    verifiedBitmaps.add(result.bitmap)
                    if (verifiedBitmaps.size >= PipelineConfig.ENROLLMENT_SHOT_COUNT) {
                        // All 5 done — submit using the details collected up front.
                        submitEnrollment()
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

    /** Submits the 5 verified shots under the details already collected via setStudentInfo(). */
    private fun submitEnrollment() {
        val info = pendingInfo
        if (verifiedBitmaps.isEmpty() || info == null) {
            _enrollmentState.value = EnrollmentState.Failed("No photos captured.")
            return
        }

        viewModelScope.launch {
            _enrollmentState.value = EnrollmentState.Processing

            val result = try {
                pipeline.enrollStudentFromEmbeddings(
                    info            = info,
                    verifiedBitmaps = verifiedBitmaps.toList(),
                )
            } catch (e: Exception) {
                clearBitmaps()
                _enrollmentState.value = EnrollmentState.Failed(
                    e.message ?: "Enrollment failed — please try again"
                )
                return@launch
            }

            // Don't clear bitmaps yet if it's a duplicate-risk — forceEnroll()
            // needs verifiedBitmaps to still be populated to retry without
            // re-asking the user to retake all 5 photos.
            if (result !is EnrollmentResult.DuplicateRisk) {
                clearBitmaps()
            }

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
        val info = pendingInfo
        if (verifiedBitmaps.isEmpty() || info == null) {
            _enrollmentState.value = EnrollmentState.Failed("Session expired — please retake photos.")
            return
        }
        viewModelScope.launch {
            _enrollmentState.value = EnrollmentState.Processing
            try {
                pipeline.forceEnrollStudent(
                    info            = info,
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