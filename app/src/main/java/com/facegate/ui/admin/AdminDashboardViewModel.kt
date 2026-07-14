package com.facegate.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.storage.TemplateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class DashboardStats(
    val totalStudents      : Int = 0,
    /** Unique timetable periods conducted today (multiple sessions for the same
     *  slot still count as ONE period; extra periods each count as one). */
    val periodsConducted   : Int = 0,
    /** Timetable slots for today that have not been started at all. */
    val periodsRemaining   : Int = 0,
    val pendingConflicts   : Int = 0,
    val uniquePresentToday : Int = 0,
    val attendancePctToday : Int = 0,
    val totalPeriodsToday  : Int = 0,
    /** Holidays on/after today (used for the Holidays quick-action subtitle). */
    val upcomingHolidays   : Int = 0,
    /** Total periods configured across the whole week's timetable. */
    val timetablePeriods   : Int = 0,
    /** Total session overrides ever logged (used for the Changes Log subtitle). */
    val changesLogged      : Int = 0,
)

@HiltViewModel
class AdminDashboardViewModel @Inject constructor(
    private val repository: TemplateRepository,
) : ViewModel() {

    private val _stats = MutableStateFlow(DashboardStats())
    val stats: StateFlow<DashboardStats> = _stats

    init { loadStats() }

    fun loadStats() {
        viewModelScope.launch {
            val totalStudents = repository.getStudents().size
            val todayStart    = getStartOfDayMillis()
            val todayEnd      = getEndOfDayMillis()

            val sessions      = repository.getSessionsForDate(todayStart, todayEnd)

            // ── Period count fix ──────────────────────────────────────────
            // Multiple sessions sharing the same timetableId are ONE period
            // (e.g. teacher runs attendance twice for Math 9-A → still 1 period).
            // Extra periods (timetableId == null) each count as their own period.
            val conductedTimetableIds = sessions.mapNotNull { it.timetableId }.distinct().toSet()
            val extraPeriodCount      = sessions.count { it.timetableId == null }
            val periodsConducted      = conductedTimetableIds.size + extraPeriodCount

            // ── Periods remaining ─────────────────────────────────────────
            val dayOfWeek       = appDayOfWeek(Calendar.getInstance())
            val timetableToday  = repository.getTimetableForDay(dayOfWeek)
            val periodsRemaining = timetableToday.count { it.id !in conductedTimetableIds }
            // Total = scheduled timetable periods OR conducted (if more extra periods than scheduled)
            val totalPeriods    = timetableToday.size.coerceAtLeast(periodsConducted)

            // ── Attendance ────────────────────────────────────────────────
            val attendance    = repository.getAttendanceForRange(todayStart, todayEnd)
            val uniquePresent = attendance.map { it.studentId }.distinct().size
            val attendancePct = if (totalStudents > 0)
                ((uniquePresent.toFloat() / totalStudents) * 100).toInt() else 0

            val pendingConflicts = repository.getUnresolvedConflictCount()

            // ── Quick-action tile counts ────────────────────────────────────
            val upcomingHolidays = repository.getUpcomingHolidays().size
            val timetablePeriods = repository.getAllTimetable().size
            val changesLogged    = repository.getAllOverrides().size

            _stats.value = DashboardStats(
                totalStudents      = totalStudents,
                periodsConducted   = periodsConducted,
                periodsRemaining   = periodsRemaining,
                pendingConflicts   = pendingConflicts,
                uniquePresentToday = uniquePresent,
                attendancePctToday = attendancePct,
                totalPeriodsToday  = totalPeriods,
                upcomingHolidays   = upcomingHolidays,
                timetablePeriods   = timetablePeriods,
                changesLogged      = changesLogged,
            )
        }
    }

    private fun appDayOfWeek(cal: Calendar): Int = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY    -> 1
        Calendar.TUESDAY   -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY  -> 4
        Calendar.FRIDAY    -> 5
        Calendar.SATURDAY  -> 6
        else               -> 7 // Calendar.SUNDAY
    }

    private fun getStartOfDayMillis(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun getEndOfDayMillis(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59);      set(Calendar.MILLISECOND, 999)
    }.timeInMillis
}