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

    /**
     * Resolve a conflict with the admin's explicit decision, instead of
     * silently marking it resolved with no record of what happened.
     *
     * @param markPresent true → mark [conflict.topStudentId] present today.
     *                    false → leave them absent (no attendance record);
     *                    just clear the conflict from the queue.
     */
    fun resolveConflict(conflict: ConflictEntity, markPresent: Boolean) {
        viewModelScope.launch {
            if (markPresent) {
                val startOfDay = getStartOfDay()
                val timestamp = System.currentTimeMillis()
                if (!repository.isStudentMarkedToday(conflict.topStudentId, startOfDay)) {
                    repository.addAttendance(
                        AttendanceEntity(
                            studentId = conflict.topStudentId,
                            timeStamp = timestamp,
                            synced    = false,
                        )
                    )
                }

                pipeline.markAlreadyMarked(conflict.topStudentId, timestamp)
            }

            repository.resolveAllConflictsForStudent(conflict.topStudentId)
            repository.resolveConflict(conflict.id)

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
}