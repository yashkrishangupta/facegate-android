package com.facegate.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.storage.TemplateRepository
import com.facegate.storage.entity.AttendanceEntity
import com.facegate.storage.entity.StudentEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class StudentWithStatus(
    val student      : StudentEntity,
    val markedToday  : Boolean,
)

sealed class ManualAttendanceState {
    object Loading                                       : ManualAttendanceState()
    object Empty                                         : ManualAttendanceState()
    data class Loaded(
        val classes  : List<String>,
        val students : List<StudentWithStatus>,
        val selectedClass: String?,
    ) : ManualAttendanceState()
}

@HiltViewModel
class ManualAttendanceViewModel @Inject constructor(
    private val repository: TemplateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ManualAttendanceState>(ManualAttendanceState.Loading)
    val state: StateFlow<ManualAttendanceState> = _state

    private var selectedClass: String? = null

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = ManualAttendanceState.Loading
            val classes  = repository.getAllClasses()
            if (classes.isEmpty()) {
                _state.value = ManualAttendanceState.Empty
                return@launch
            }
            if (selectedClass == null) selectedClass = classes.firstOrNull()
            loadStudentsForClass(classes)
        }
    }

    fun selectClass(className: String) {
        selectedClass = className
        viewModelScope.launch {
            val classes = repository.getAllClasses()
            loadStudentsForClass(classes)
        }
    }

    private suspend fun loadStudentsForClass(classes: List<String>) {
        val cls      = selectedClass ?: return
        val students = repository.getStudentsByClass(cls)
        val startOfDay = getStartOfDay()
        val withStatus = students.map { student ->
            StudentWithStatus(
                student     = student,
                markedToday = repository.isStudentMarkedToday(student.studentId, startOfDay),
            )
        }
        _state.value = ManualAttendanceState.Loaded(
            classes       = classes,
            students      = withStatus,
            selectedClass = selectedClass,
        )
    }

    /** Toggle: if already present today → mark absent (remove record); otherwise mark present. */
    fun toggleAttendance(studentId: String) {
        viewModelScope.launch {
            val startOfDay = getStartOfDay()
            if (repository.isStudentMarkedToday(studentId, startOfDay)) {
                // Already present — remove today's record to mark absent
                repository.removeAttendanceToday(studentId, startOfDay)
            } else {
                // Not yet marked — insert a present record
                repository.addAttendance(
                    AttendanceEntity(
                        studentId = studentId,
                        timeStamp = System.currentTimeMillis(),
                        synced    = false,
                    )
                )
            }
            val classes = repository.getAllClasses()
            loadStudentsForClass(classes)
        }
    }

    // Keep old name as an alias so any other callers don't break
    fun markStudentPresent(studentId: String) = toggleAttendance(studentId)

    private fun getStartOfDay(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
