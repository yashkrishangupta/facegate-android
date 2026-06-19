package com.facegate.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.facegate.pipeline.AttendancePipeline

/**
 * Scan state sealed class
 * Represents all possible states of the face scan
 */
sealed class ScanState {
    object Idle       : ScanState()
    object Scanning   : ScanState()
    object Processing : ScanState()
    data class Success(
        val studentName  : String,
        val studentClass : String,
        val initials     : String,
        val markedTime   : String
    ) : ScanState()
    object Failed : ScanState()
}

/**
 * ATTENDANCE VIEWMODEL
 * Connects pipeline.processFrame() to UI via StateFlow
 * Matches: triggerStudentScan() logic in JS
 */
@HiltViewModel
class AttendanceViewModel @Inject constructor(
    private val pipeline: AttendancePipeline
) : ViewModel() {

    // StateFlow replaces JS event system
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState

    /**
     * Process a scan attempt
     * Simulates ML pipeline result
     * Matches: const succeed = Math.random() > 0.35 in JS
     */
    fun processScan() {
        viewModelScope.launch {

            // Show processing state
            _scanState.value = ScanState.Processing

            // Simulate ML pipeline processing time
            // Matches: setTimeout(..., 2200) in JS
            delay(2200)

            // Random outcome — 65% success 35% fail
            val succeed = Math.random() > 0.35

            if (succeed) {
                // Get current time
                // Matches: now.toLocaleTimeString in JS
                val time = SimpleDateFormat(
                    "hh:mm a",
                    Locale.getDefault()
                ).format(Date())

                _scanState.value = ScanState.Success(
                    studentName  = "Arjun Kumar",
                    studentClass = "Class 9-B · Roll No. 14",
                    initials     = "AK",
                    markedTime   = time
                )
            } else {
                _scanState.value = ScanState.Failed
            }
        }
    }

    /**
     * Reset scan state back to idle
     */
    fun resetScan() {
        _scanState.value = ScanState.Idle
    }
}