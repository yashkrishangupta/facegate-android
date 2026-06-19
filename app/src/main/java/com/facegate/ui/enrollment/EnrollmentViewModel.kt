package com.facegate.ui.enrollment

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.pipeline.AttendancePipeline
import com.facegate.pipeline.EnrollmentResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * All possible states of the enrollment screen.
 * Added reason string to Failed for better UI feedback.
 */
sealed class EnrollmentState {
    object Idle          : EnrollmentState()
    object Capturing     : EnrollmentState()
    object Processing    : EnrollmentState()
    object Success       : EnrollmentState()
    object DuplicateFace : EnrollmentState()
    data class Failed(val reason: String = "Please try again") : EnrollmentState()
}

/**
 * ENROLLMENT VIEWMODEL — fully wired to real pipeline
 *
 * Changed from original:
 *   - capturePhoto(bitmap: Bitmap) stores real camera bitmaps (not Int index)
 *   - enrollStudent(name, id, class) calls real pipeline.enrollStudent()
 *   - Tries all captured photos until one succeeds (multiple angles = more chances)
 *   - EnrollmentResult mapped to EnrollmentState with specific failure reasons
 *   - Math.random() mock removed entirely
 */
@HiltViewModel
class EnrollmentViewModel @Inject constructor(
    private val pipeline: AttendancePipeline,
) : ViewModel() {

    private val _enrollmentState = MutableStateFlow<EnrollmentState>(EnrollmentState.Idle)
    val enrollmentState: StateFlow<EnrollmentState> = _enrollmentState

    // Real captured bitmaps — one per shutter press (up to 5)
    private val capturedBitmaps = mutableListOf<Bitmap>()

    // ── Photo capture ────────────────────────────────────────────────────────

    /**
     * Store a real bitmap captured from the camera.
     * Called by the Fragment on each shutter button press.
     * Returns true when 5 photos have been captured (ready to enroll).
     */
    fun capturePhoto(bitmap: Bitmap): Boolean {
        capturedBitmaps.add(bitmap)
        _enrollmentState.value = EnrollmentState.Capturing
        return capturedBitmaps.size >= 5
    }

    fun capturedCount(): Int = capturedBitmaps.size

    // ── Enrollment ───────────────────────────────────────────────────────────

    /**
     * Enroll the student using captured photos.
     * Tries each bitmap in order until one succeeds through the full pipeline.
     * This gives multiple chances for a clean face detection + quality pass.
     *
     * @param studentName  Display name (e.g. "Arjun Kumar")
     * @param studentId    Unique roll number (e.g. "S001")
     * @param studentClass Class section (e.g. "9-B")
     */
    fun enrollStudent(
        studentName : String,
        studentId   : String,
        studentClass: String,
    ) {
        if (capturedBitmaps.isEmpty()) {
            _enrollmentState.value = EnrollmentState.Failed("No photos captured. Please take photos first.")
            return
        }

        viewModelScope.launch {
            _enrollmentState.value = EnrollmentState.Processing

            var lastResult: EnrollmentResult = EnrollmentResult.NoFaceDetected

            for (bitmap in capturedBitmaps) {
                val result = pipeline.enrollStudent(
                    studentId    = studentId,
                    studentName  = studentName,
                    studentClass = studentClass,
                    photo        = bitmap,
                )
                lastResult = result

                when (result) {
                    is EnrollmentResult.Success -> {
                        clearBitmaps()
                        _enrollmentState.value = EnrollmentState.Success
                        return@launch
                    }
                    is EnrollmentResult.DuplicateRisk -> {
                        clearBitmaps()
                        _enrollmentState.value = EnrollmentState.DuplicateFace
                        return@launch
                    }
                    else -> continue // try next photo
                }
            }

            // All photos failed
            clearBitmaps()
            _enrollmentState.value = when (lastResult) {
                is EnrollmentResult.NoFaceDetected ->
                    EnrollmentState.Failed("No face detected. Ensure good lighting and face the camera directly.")
                is EnrollmentResult.MultipleFacesDetected ->
                    EnrollmentState.Failed("Multiple faces detected. Only one person should be in frame.")
                is EnrollmentResult.QualityFailed ->
                    EnrollmentState.Failed("Image quality too low. Move to better lighting and hold still.")
                else -> EnrollmentState.Failed()
            }
        }
    }

    // ── Reset ────────────────────────────────────────────────────────────────

    fun reset() {
        clearBitmaps()
        _enrollmentState.value = EnrollmentState.Idle
    }

    private fun clearBitmaps() {
        capturedBitmaps.forEach { it.recycle() }
        capturedBitmaps.clear()
    }

    override fun onCleared() {
        super.onCleared()
        clearBitmaps()
    }
}