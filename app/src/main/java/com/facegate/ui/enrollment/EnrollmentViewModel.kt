package com.facegate.ui.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.facegate.pipeline.AttendancePipeline

/**
 * Enrollment state sealed class
 */
sealed class EnrollmentState {
    object Idle          : EnrollmentState()
    object Capturing     : EnrollmentState()
    object Processing    : EnrollmentState()
    object Success       : EnrollmentState()
    object DuplicateFace : EnrollmentState()
    object Failed        : EnrollmentState()
}

/**
 * ENROLLMENT VIEWMODEL
 * Calls pipeline.enrollStudent()
 * Shows duplicate warnings if face already exists
 */
@HiltViewModel
class EnrollmentViewModel @Inject constructor(
    private val pipeline: AttendancePipeline
) : ViewModel() {

    private val _enrollmentState =
        MutableStateFlow<EnrollmentState>(EnrollmentState.Idle)
    val enrollmentState: StateFlow<EnrollmentState> = _enrollmentState

    // Store captured photo frames
    private val capturedPhotos = mutableListOf<Int>()

    /**
     * Capture one photo frame
     * Called for each of the 5 photos
     */
    fun capturePhoto(photoIndex: Int) {
        viewModelScope.launch {
            _enrollmentState.value = EnrollmentState.Capturing
            // In real app: capture frame from camera here
            capturedPhotos.add(photoIndex)
        }
    }

    /**
     * Process all 5 photos and enroll the student
     * Checks for duplicate face in database
     */
    fun enrollStudent() {
        viewModelScope.launch {
            _enrollmentState.value = EnrollmentState.Processing

            // Simulate processing 5 photos
            delay(2000)

            // Simulate duplicate check
            // In real app: compare with database embeddings
            val isDuplicate = Math.random() < 0.1  // 10% chance duplicate

            if (isDuplicate) {
                _enrollmentState.value = EnrollmentState.DuplicateFace
            } else {
                // Simulate enrollment success
                delay(1000)
                _enrollmentState.value = EnrollmentState.Success
            }
        }
    }

    /**
     * Reset enrollment state
     */
    fun reset() {
        capturedPhotos.clear()
        _enrollmentState.value = EnrollmentState.Idle
    }
}