package com.facegate.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.pipeline.AttendancePipeline
import com.facegate.storage.TemplateRepository
import com.facegate.storage.entity.StudentEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class StudentsState {
    object Loading                                  : StudentsState()
    object Empty                                    : StudentsState()
    data class Loaded(val students: List<StudentEntity>) : StudentsState()
}

@HiltViewModel
class StudentsViewModel @Inject constructor(
    private val repository: TemplateRepository,
    private val pipeline  : AttendancePipeline,
) : ViewModel() {

    private val _state = MutableStateFlow<StudentsState>(StudentsState.Loading)
    val state: StateFlow<StudentsState> = _state

    // One-shot error events (e.g. duplicate roll number) for the Fragment to toast.
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvents: SharedFlow<String> = _errorEvents

    init { loadStudents() }

    fun loadStudents() {
        viewModelScope.launch {
            _state.value = StudentsState.Loading
            val students = repository.getStudents()
            _state.value = if (students.isEmpty()) StudentsState.Empty
                           else StudentsState.Loaded(students)
        }
    }

    fun deleteStudent(studentId: String) {
        viewModelScope.launch {
            repository.deleteStudent(studentId)   // cascades attendance + conflict rows
            pipeline.removeStudentFromSession(studentId)  // sync live session memory
            loadStudents()
        }
    }

    /**
     * Updates the editable student-record fields. Deliberately does NOT
     * touch studentId — that's the local sync identifier (a locally-typed
     * value until the student's first successful enrollment upload, then
     * the server's UUID — see StudentEntity.isLocalOnly), not something an
     * admin should hand-edit from here. rollNumber is the actual
     * human-facing "Roll No." field now.
     */
    fun updateStudentInfo(
        studentId: String,
        name: String,
        studentClass: String,
        rollNumber: String,
        registrationNumber: String,
        gender: String,
        email: String?,
        phone: String?,
    ) {
        viewModelScope.launch {
            val trimmedRoll = rollNumber.trim()
            if (trimmedRoll.isEmpty()) {
                _errorEvents.emit("Roll number can't be empty")
                return@launch
            }
            repository.updateStudentInfo(
                studentId = studentId,
                name = name.trim(),
                studentClass = studentClass.trim(),
                rollNumber = trimmedRoll,
                registrationNumber = registrationNumber.trim().ifEmpty { trimmedRoll },
                gender = gender,
                email = email,
                phone = phone,
            )
            loadStudents()
        }
    }
}