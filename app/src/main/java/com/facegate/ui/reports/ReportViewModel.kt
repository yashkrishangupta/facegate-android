package com.facegate.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.storage.TemplateRepository
import com.facegate.storage.entity.AttendanceEntity
import com.facegate.storage.entity.StudentEntity
import com.facegate.storage.entity.TimetableEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** One period scheduled on the selected date (e.g. "Period 3"). */
data class PeriodOption(
    val periodNumber: Int,
    val label       : String,
)

/** One selectable batch under the selected period. `batch == null` means "All batches". */
data class BatchOption(
    val batch: String?,
    val label: String,
)

data class RosterEntry(
    val student: StudentEntity,
    val marked : Boolean,
)

sealed class ExplorerState {
    object Loading : ExplorerState()

    data class Holiday(
        val dateLabel  : String,
        val holidayName: String,
    ) : ExplorerState()

    data class NoSchedule(
        val dateLabel: String,
    ) : ExplorerState()

    data class Loaded(
        val dateLabel     : String,
        val canGoForward  : Boolean,
        val periods       : List<PeriodOption>,
        val selectedPeriod: Int?,
        val batches       : List<BatchOption>,
        val selectedBatch : String?,
        val students      : List<RosterEntry>,
    ) : ExplorerState()
}

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val repository: TemplateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ExplorerState>(ExplorerState.Loading)
    val state: StateFlow<ExplorerState> = _state

    private var selectedDate    : Long   = startOfDay(System.currentTimeMillis())
    private var selectedPeriod  : Int?   = null
    private var selectedBatch   : String? = null   // null = "All batches"

    // Cached for the currently-loaded date, so selectPeriod()/selectBatch() don't
    // need to re-hit the DB for the timetable every time.
    private var timetableForDate: List<TimetableEntity> = emptyList()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = ExplorerState.Loading

            val dateLabel = dateLabelFmt.format(Date(selectedDate))

            if (repository.isHoliday(dayString(selectedDate))) {
                val holidayName = repository.getAllHolidays()
                    .firstOrNull { it.date == dayString(selectedDate) }?.name ?: "Holiday"
                _state.value = ExplorerState.Holiday(dateLabel, holidayName)
                return@launch
            }

            val dow = appDayOfWeek(selectedDate)
            timetableForDate = if (dow > 0) repository.getTimetableForDay(dow) else emptyList()

            if (timetableForDate.isEmpty()) {
                _state.value = ExplorerState.NoSchedule(dateLabel)
                return@launch
            }

            val periods = timetableForDate
                .map { it.periodNumber }
                .distinct()
                .sorted()
                .map { PeriodOption(it, "Period $it") }

            if (selectedPeriod == null || periods.none { it.periodNumber == selectedPeriod }) {
                selectedPeriod = periods.first().periodNumber
                selectedBatch  = null
            }

            renderForPeriodAndBatch(dateLabel, periods)
        }
    }

    /** Jump to an arbitrary date (from the calendar picker). */
    fun selectDate(newDate: Long) {
        val newStart = startOfDay(newDate)
        if (newStart == selectedDate) return
        selectedDate   = newStart
        selectedPeriod = null
        selectedBatch  = null
        load()
    }

    /** Step one day forward/backward. Forward is capped at today. */
    fun stepDate(delta: Int) {
        val candidate = selectedDate + delta * DAY_MS
        if (candidate > startOfDay(System.currentTimeMillis())) return
        selectDate(candidate)
    }

    fun selectPeriod(periodNumber: Int) {
        if (periodNumber == selectedPeriod) return
        selectedPeriod = periodNumber
        selectedBatch  = null // reset to "All batches" whenever the period changes
        viewModelScope.launch {
            val dateLabel = dateLabelFmt.format(Date(selectedDate))
            val periods = timetableForDate
                .map { it.periodNumber }.distinct().sorted().map { PeriodOption(it, "Period $it") }
            renderForPeriodAndBatch(dateLabel, periods)
        }
    }

    fun selectBatch(batch: String?) {
        if (batch == selectedBatch) return
        selectedBatch = batch
        viewModelScope.launch {
            val dateLabel = dateLabelFmt.format(Date(selectedDate))
            val periods = timetableForDate
                .map { it.periodNumber }.distinct().sorted().map { PeriodOption(it, "Period $it") }
            renderForPeriodAndBatch(dateLabel, periods)
        }
    }

    private suspend fun renderForPeriodAndBatch(dateLabel: String, periods: List<PeriodOption>) {
        val period = selectedPeriod ?: return
        val entriesForPeriod = timetableForDate.filter { it.periodNumber == period }

        val batchOptions = listOf(BatchOption(null, "All batches")) +
            entriesForPeriod.map { it.batch }.distinct().sorted().map { BatchOption(it, it) }

        val relevantEntries = if (selectedBatch == null) entriesForPeriod
                              else entriesForPeriod.filter { it.batch == selectedBatch }

        val startOfDayMs = selectedDate
        val endOfDayMs    = selectedDate + DAY_MS - 1

        // One roster entry per student, deduped (a student could theoretically show up
        // under more than one timetable entry for the period if data is messy).
        val roster = linkedMapOf<String, RosterEntry>()
        for (entry in relevantEntries) {
            val session = repository.findSessionForTimetableOnDate(entry.id, startOfDayMs, endOfDayMs)
            val students = repository.getStudentsByClass(entry.batch)
            students.forEach { student ->
                val marked = if (session != null) {
                    repository.isStudentMarkedForSession(student.studentId, session.sessionId)
                } else {
                    repository.isStudentMarkedOnDate(student.studentId, startOfDayMs, endOfDayMs)
                }
                roster[student.studentId] = RosterEntry(student, marked)
            }
        }

        _state.value = ExplorerState.Loaded(
            dateLabel      = dateLabel,
            canGoForward   = selectedDate < startOfDay(System.currentTimeMillis()),
            periods        = periods,
            selectedPeriod = selectedPeriod,
            batches        = batchOptions,
            selectedBatch  = selectedBatch,
            students       = roster.values.toList(),
        )
    }

    /**
     * Mark/unmark a student for the currently selected date + period.
     * Resolves the student's own batch (not necessarily the filter selection —
     * relevant when "All batches" is active) to find their specific session.
     */
    fun toggleAttendance(student: StudentEntity) {
        val period = selectedPeriod ?: return
        viewModelScope.launch {
            val entry = timetableForDate.firstOrNull {
                it.periodNumber == period && it.batch == student.studentClass
            }
            val startOfDayMs = selectedDate
            val endOfDayMs    = selectedDate + DAY_MS - 1
            val session = entry?.let {
                repository.findSessionForTimetableOnDate(it.id, startOfDayMs, endOfDayMs)
            }

            if (session != null) {
                if (repository.isStudentMarkedForSession(student.studentId, session.sessionId)) {
                    repository.deleteAttendanceForSession(student.studentId, session.sessionId)
                } else {
                    repository.addAttendance(
                        AttendanceEntity(
                            studentId = student.studentId,
                            sessionId = session.sessionId,
                            timeStamp = backdatedStamp(),
                            synced    = false,
                        )
                    )
                }
            } else {
                // No session was ever started for this slot on this date — fall back
                // to a day-wide mark, same as Manual Attendance does for this case.
                if (repository.isStudentMarkedOnDate(student.studentId, startOfDayMs, endOfDayMs)) {
                    repository.removeAttendanceOnDate(student.studentId, startOfDayMs, endOfDayMs)
                } else {
                    repository.addAttendance(
                        AttendanceEntity(
                            studentId = student.studentId,
                            timeStamp = backdatedStamp(),
                            synced    = false,
                        )
                    )
                }
            }
            load()
        }
    }

    private fun backdatedStamp(): Long =
        if (selectedDate == startOfDay(System.currentTimeMillis())) System.currentTimeMillis()
        else selectedDate + (12 * 60 * 60 * 1000)

    // ── Date helpers ──────────────────────────────────────────────────────────

    private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dayString(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))

    private fun appDayOfWeek(ms: Long): Int =
        when (Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY    -> 1
            Calendar.TUESDAY   -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY  -> 4
            Calendar.FRIDAY    -> 5
            else               -> 0
        }

    private val dateLabelFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}