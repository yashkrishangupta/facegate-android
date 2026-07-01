package com.facegate.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.storage.TemplateRepository
import com.facegate.storage.entity.AttendanceEntity
import com.facegate.storage.entity.SessionEntity
import com.facegate.storage.entity.StudentEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class StudentWithStatus(
    val student       : StudentEntity,
    /** True if the student is marked present under the current filter
     *  (session-specific when a session is selected, otherwise day-wide). */
    val markedToday   : Boolean,
)

/** One selectable day in the date strip — today plus the past 6 days (1 week total). */
data class SelectableDay(
    val startOfDay : Long,
    val label      : String,   // e.g. "Today", "Yesterday", "Mon 23"
    val isToday    : Boolean,
)

sealed class ManualAttendanceState {
    object Loading                                                : ManualAttendanceState()
    object Empty                                                  : ManualAttendanceState()
    data class Loaded(
        val classes       : List<String>,
        val students      : List<StudentWithStatus>,
        val selectedClass : String?,
        val sessions      : List<SessionEntity> = emptyList(),
        val selectedSession: SessionEntity?     = null,
        val days          : List<SelectableDay> = emptyList(),
        val selectedDay   : SelectableDay,
    ) : ManualAttendanceState()
}

@HiltViewModel
class ManualAttendanceViewModel @Inject constructor(
    private val repository: TemplateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ManualAttendanceState>(ManualAttendanceState.Loading)
    val state: StateFlow<ManualAttendanceState> = _state

    private var selectedClass   : String?        = null
    private var selectedSession : SessionEntity? = null

    // Manual editing is only ever allowed for today or the past 6 days (1 week total) —
    // older records should go through a correction workflow, not silent backdating.
    private val selectableDays: List<SelectableDay> = buildSelectableDays()
    private var selectedDay: SelectableDay = selectableDays.first() // index 0 = today

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = ManualAttendanceState.Loading
            val classes = repository.getAllClasses()
            if (classes.isEmpty()) {
                _state.value = ManualAttendanceState.Empty
                return@launch
            }
            if (selectedClass == null) selectedClass = classes.firstOrNull()
            loadStudentsForClass(classes)
        }
    }

    fun selectClass(className: String) {
        selectedClass   = className
        selectedSession = null // session list is day-scoped; reset on class change too for clarity
        viewModelScope.launch { loadStudentsForClass(repository.getAllClasses()) }
    }

    fun selectSession(session: SessionEntity?) {
        selectedSession = session
        viewModelScope.launch { loadStudentsForClass(repository.getAllClasses()) }
    }

    /** Switch which day's attendance is being viewed/edited (today or any of the past 6 days). */
    fun selectDay(day: SelectableDay) {
        if (day.startOfDay == selectedDay.startOfDay) return
        selectedDay      = day
        selectedSession  = null // sessions are day-specific — clear stale selection
        viewModelScope.launch { loadStudentsForClass(repository.getAllClasses()) }
    }

    private suspend fun loadStudentsForClass(classes: List<String>) {
        val cls      = selectedClass ?: return
        val students = repository.getStudentsByClass(cls)

        val startOfDay = selectedDay.startOfDay
        val endOfDay   = startOfDay + DAY_MS - 1
        val sessions   = repository.getSessionsForDate(startOfDay, endOfDay)

        // When a session is active, also auto-select it if none chosen yet
        if (selectedSession == null && sessions.size == 1) {
            selectedSession = sessions.first()
        }

        val withStatus = students.map { student ->
            val marked = if (selectedSession != null) {
                repository.isStudentMarkedForSession(student.studentId, selectedSession!!.sessionId)
            } else {
                repository.isStudentMarkedOnDate(student.studentId, startOfDay, endOfDay)
            }
            StudentWithStatus(student = student, markedToday = marked)
        }

        _state.value = ManualAttendanceState.Loaded(
            classes         = classes,
            students        = withStatus,
            selectedClass   = selectedClass,
            sessions        = sessions,
            selectedSession = selectedSession,
            days            = selectableDays,
            selectedDay     = selectedDay,
        )
    }

    /**
     * Toggle attendance for a student on the currently selected day.
     * When a session is selected, marks/unmarks for that specific session.
     * Otherwise marks/unmarks for the selected day generally (no session link).
     */
    fun toggleAttendance(studentId: String) {
        viewModelScope.launch {
            val session    = selectedSession
            val startOfDay = selectedDay.startOfDay
            val endOfDay   = startOfDay + DAY_MS - 1

            if (session != null) {
                // Session-scoped toggle
                if (repository.isStudentMarkedForSession(studentId, session.sessionId)) {
                    repository.deleteAttendanceForSession(studentId, session.sessionId)
                } else {
                    repository.addAttendance(
                        AttendanceEntity(
                            studentId = studentId,
                            sessionId = session.sessionId,
                            // Backdated marks are stamped at the selected day's noon so
                            // they sort/display sensibly within that day, but for today
                            // we keep the real current time.
                            timeStamp = if (selectedDay.isToday) System.currentTimeMillis()
                                        else startOfDay + (12 * 60 * 60 * 1000),
                            synced    = false,
                        )
                    )
                }
            } else {
                // Day-wide toggle (no session selected)
                if (repository.isStudentMarkedOnDate(studentId, startOfDay, endOfDay)) {
                    repository.removeAttendanceOnDate(studentId, startOfDay, endOfDay)
                } else {
                    repository.addAttendance(
                        AttendanceEntity(
                            studentId = studentId,
                            timeStamp = if (selectedDay.isToday) System.currentTimeMillis()
                                        else startOfDay + (12 * 60 * 60 * 1000),
                            synced    = false,
                        )
                    )
                }
            }
            loadStudentsForClass(repository.getAllClasses())
        }
    }

    // Keep old alias so other callers don't break
    fun markStudentPresent(studentId: String) = toggleAttendance(studentId)

    // ── Day strip ─────────────────────────────────────────────────────────────

    private fun buildSelectableDays(): List<SelectableDay> {
        val days = mutableListOf<SelectableDay>()
        val dayLabelFmt = SimpleDateFormat("EEE d", Locale.getDefault())
        for (i in 0..6) {
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -i)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
            }
            val label = when (i) {
                0    -> "Today"
                1    -> "Yesterday"
                else -> dayLabelFmt.format(Date(cal.timeInMillis))
            }
            days.add(SelectableDay(startOfDay = cal.timeInMillis, label = label, isToday = i == 0))
        }
        return days
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}