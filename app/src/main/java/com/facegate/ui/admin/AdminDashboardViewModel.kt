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
    val totalStudents       : Int = 0,
    /** Sessions actually run today */
    val periodsConducted    : Int = 0,
    /** Timetable slots scheduled today that haven't been started yet */
    val periodsRemaining    : Int = 0,
    val pendingConflicts    : Int = 0,
    /** Unique students present across all of today's sessions */
    val uniquePresentToday  : Int = 0,
    /** Avg attendance pct across today's sessions (unique students / total enrolled) */
    val attendancePctToday  : Int = 0,
    /** Total timetable periods for today (conducted + remaining) */
    val totalPeriodsToday   : Int = 0,
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
            val totalStudents    = repository.getStudents().size
            val todayStart       = getStartOfDayMillis()
            val todayEnd         = getEndOfDayMillis()

            // Periods conducted = sessions that started today
            val sessions         = repository.getSessionsForDate(todayStart, todayEnd)
            val periodsConducted = sessions.size

            // Periods remaining = timetable slots today with no session yet
            val dayOfWeek        = appDayOfWeek(Calendar.getInstance())
            val timetableToday   = if (dayOfWeek > 0) repository.getTimetableForDay(dayOfWeek)
                                   else emptyList()
            val conductedIds     = sessions.mapNotNull { it.timetableId }.toSet()
            val periodsRemaining = timetableToday.count { it.id !in conductedIds }
            val totalPeriods     = timetableToday.size.coerceAtLeast(periodsConducted)

            // Attendance: unique students marked across all today's sessions
            val attendance       = repository.getAttendanceForRange(todayStart, todayEnd)
            val uniquePresent    = attendance.map { it.studentId }.distinct().size
            val attendancePct    = if (totalStudents > 0)
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

    /** Map Calendar.DAY_OF_WEEK to app's 1=Mon…5=Fri (0 = weekend). */
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