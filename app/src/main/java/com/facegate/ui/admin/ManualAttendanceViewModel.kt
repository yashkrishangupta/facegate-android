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
    /** True if the student is marked present for the currently selected session.
     *  Falls back to a day-wide check only when no session has been started yet
     *  for the selected class/day (there's no "All sessions" filter to pick). */
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
    /** No period/class happened on the selected day — nothing to correct. */
    data class NoPeriodToday(
        val days       : List<SelectableDay>,
        val selectedDay: SelectableDay,
    ) : ManualAttendanceState()
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
            refresh()
        }
    }

    fun selectClass(className: String) {
        selectedClass   = className
        selectedSession = null // session list is class-scoped; reset on class change too for clarity
        viewModelScope.launch { refresh() }
    }

    fun selectSession(session: SessionEntity?) {
        selectedSession = session
        viewModelScope.launch { refresh() }
    }

    /** Switch which day's attendance is being viewed/edited (today or any of the past 6 days). */
    fun selectDay(day: SelectableDay) {
        if (day.startOfDay == selectedDay.startOfDay) return
        selectedDay      = day
        selectedClass    = null // classes are day-scoped (only ones with a period that day) — re-derive
        selectedSession  = null
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val allClasses = repository.getAllClasses()
        if (allClasses.isEmpty()) {
            _state.value = ManualAttendanceState.Empty
            return
        }

        val startOfDay = selectedDay.startOfDay
        val endOfDay   = startOfDay + DAY_MS - 1
        val sessionsToday = repository.getSessionsForDate(startOfDay, endOfDay)

        // A class is only offered as a filter option if a period/session actually
        // happened for it that day — no point letting someone mark attendance for
        // a class that had no class that day.
        val classesWithSessions = sessionsToday
            .map { it.batch }
            .distinct()
            .filter { it in allClasses }
            .sorted()

        if (classesWithSessions.isEmpty()) {
            _state.value = ManualAttendanceState.NoPeriodToday(
                days        = selectableDays,
                selectedDay = selectedDay,
            )
            return
        }

        if (selectedClass == null || selectedClass !in classesWithSessions) {
            selectedClass   = classesWithSessions.first()
            selectedSession = null
        }

        val cls      = selectedClass!!
        val students = repository.getStudentsByClass(cls)
        val sessions = sessionsToday.filter { it.batch == cls }

        // With the "All sessions" filter removed, attendance is always tied to a
        // specific session once one exists for the day — auto-select the most
        // recently started one if nothing is picked yet, rather than falling
        // back to a day-wide (session-less) view.
        if (selectedSession == null && sessions.isNotEmpty()) {
            selectedSession = sessions.maxByOrNull { it.startTime }
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
            classes         = classesWithSessions,
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
                val existing = repository.findAttendance(studentId, session.sessionId)
                if (existing != null) {
                    if (existing.synced) {
                        // Already reached the server — a local hard-delete here
                        // would never be reflected on the website. Push an
                        // ABSENT correction instead (see AttendanceDao.markCorrectedAbsent).
                        repository.correctSyncedAttendanceToAbsent(studentId, session.sessionId)
                    } else {
                        repository.deleteAttendanceForSession(studentId, session.sessionId)
                    }
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
                            attendanceMode = "MANUAL",
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
                            attendanceMode = "MANUAL",
                        )
                    )
                }
            }
            refresh()
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