package com.facegate.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
            repository.deleteStudent(studentId)
            loadStudents()
        }
    }

    fun updateStudentInfo(studentId: String, name: String, studentClass: String) {
        viewModelScope.launch {
            repository.updateStudentInfo(studentId, name.trim(), studentClass.trim())
            loadStudents()
        }
    }

    fun updateStudentRollNo(oldId: String, newId: String, name: String, studentClass: String) {
        viewModelScope.launch {
            val trimmedNewId = newId.trim()
            if (trimmedNewId.isEmpty()) {
                _errorEvents.emit("Roll number can't be empty")
                return@launch
            }
            if (trimmedNewId != oldId && repository.getStudentById(trimmedNewId) != null) {
                _errorEvents.emit("Roll number \"$trimmedNewId\" is already used by another student")
                return@launch
            }

            if (trimmedNewId == oldId) {
                repository.updateStudentInfo(oldId, name.trim(), studentClass.trim())
            } else {
                repository.updateStudentRollNo(oldId, trimmedNewId, name.trim(), studentClass.trim())
            }
            loadStudents()
        }
    }
}