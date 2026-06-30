package com.facegate.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.pipeline.AttendancePipeline
import com.facegate.storage.TemplateRepository
import com.facegate.storage.entity.AttendanceEntity
import com.facegate.storage.entity.ConflictEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

sealed class ConflictQueueState {
    object Loading                              : ConflictQueueState()
    object Empty                                : ConflictQueueState()
    data class Loaded(val conflicts: List<ConflictEntity>) : ConflictQueueState()
}

@HiltViewModel
class ConflictQueueViewModel @Inject constructor(
    private val repository: TemplateRepository,
    private val pipeline: AttendancePipeline,
) : ViewModel() {

    private val _state = MutableStateFlow<ConflictQueueState>(ConflictQueueState.Loading)
    val state: StateFlow<ConflictQueueState> = _state

    init {
        loadConflicts()
    }

    fun loadConflicts() {
        viewModelScope.launch {
            _state.value = ConflictQueueState.Loading
            val conflicts = repository.getUnresolvedConflicts()
            _state.value = if (conflicts.isEmpty()) {
                ConflictQueueState.Empty
            } else {
                ConflictQueueState.Loaded(conflicts)
            }
        }
    }

    fun resolveConflict(
        conflict           : ConflictEntity,
        presentStudentId   : String?,
        presentStudentName : String?,
    ) {
        viewModelScope.launch {
            if (presentStudentId != null) {
                val startOfDay = getStartOfDay()
                val endOfDay   = getEndOfDay()
                val timestamp  = System.currentTimeMillis()
                if (!repository.isStudentMarkedOnDate(presentStudentId, startOfDay, endOfDay)) {
                    repository.addAttendance(
                        AttendanceEntity(
                            studentId = presentStudentId,
                            timeStamp = timestamp,
                            synced    = false,
                        )
                    )
                }
                pipeline.markAlreadyMarked(presentStudentId, timestamp)
            }

            // Resolve this conflict row and any duplicates for both candidates
            repository.resolveConflict(conflict.id)
            repository.resolveAllConflictsForStudent(conflict.topStudentId)
            repository.resolveAllConflictsForStudent(conflict.secondStudentId)

            loadConflicts()
        }
    }

    private fun getStartOfDay(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getEndOfDay(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }
}