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
     *  slot still count as ONE period; ad-hoc sessions each count as one). */
    val periodsConducted   : Int = 0,
    /** Timetable slots for today that have not been started at all. */
    val periodsRemaining   : Int = 0,
    val pendingConflicts   : Int = 0,
    val uniquePresentToday : Int = 0,
    val attendancePctToday : Int = 0,
    val totalPeriodsToday  : Int = 0,
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
            // Ad-hoc sessions (timetableId == null) each count as their own period.
            val conductedTimetableIds = sessions.mapNotNull { it.timetableId }.distinct().toSet()
            val adHocCount            = sessions.count { it.timetableId == null }
            val periodsConducted      = conductedTimetableIds.size + adHocCount

            // ── Periods remaining ─────────────────────────────────────────
            val dayOfWeek       = appDayOfWeek(Calendar.getInstance())
            val timetableToday  = if (dayOfWeek > 0) repository.getTimetableForDay(dayOfWeek)
                                  else emptyList()
            val periodsRemaining = timetableToday.count { it.id !in conductedTimetableIds }
            // Total = scheduled timetable periods OR conducted (if more ad-hoc than scheduled)
            val totalPeriods    = timetableToday.size.coerceAtLeast(periodsConducted)

            // ── Attendance ────────────────────────────────────────────────
            val attendance    = repository.getAttendanceForRange(todayStart, todayEnd)
            val uniquePresent = attendance.map { it.studentId }.distinct().size
            val attendancePct = if (totalStudents > 0)
                ((uniquePresent.toFloat() / totalStudents) * 100).toInt() else 0

            val pendingConflicts = repository.getUnresolvedConflictCount()

            _stats.value = DashboardStats(
                totalStudents      = totalStudents,
                periodsConducted   = periodsConducted,
                periodsRemaining   = periodsRemaining,
                pendingConflicts   = pendingConflicts,
                uniquePresentToday = uniquePresent,
                attendancePctToday = attendancePct,
                totalPeriodsToday  = totalPeriods,
            )
        }
    }

    private fun appDayOfWeek(cal: Calendar): Int = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY    -> 1
        Calendar.TUESDAY   -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY  -> 4
        Calendar.FRIDAY    -> 5
        else               -> 0
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