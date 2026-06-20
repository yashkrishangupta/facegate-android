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
    val totalStudents    : Int = 0,
    val presentToday     : Int = 0,
    val absentToday      : Int = 0,
    val pendingConflicts : Int = 0,
    /** 0–100 integer percentage of students present today */
    val attendancePct    : Int = 0,
    /** 0–100 integer percentage of students absent today */
    val absentPct        : Int = 0,
)

@HiltViewModel
class AdminDashboardViewModel @Inject constructor(
    private val repository: TemplateRepository,
) : ViewModel() {

    private val _stats = MutableStateFlow(DashboardStats())
    val stats: StateFlow<DashboardStats> = _stats

    init {
        loadStats()
    }

    fun loadStats() {
        viewModelScope.launch {
            val totalStudents    = repository.getStudents().size
            val todayStart       = getStartOfDayMillis()
            val presentToday     = repository.getTodayAttendance(todayStart).size
            val absentToday      = (totalStudents - presentToday).coerceAtLeast(0)
            val pendingConflicts = repository.getUnresolvedConflictCount()

            val attendancePct = if (totalStudents > 0)
                ((presentToday.toFloat() / totalStudents) * 100).toInt()
            else 0

            val absentPct = if (totalStudents > 0)
                ((absentToday.toFloat() / totalStudents) * 100).toInt()
            else 0

            _stats.value = DashboardStats(
                totalStudents    = totalStudents,
                presentToday     = presentToday,
                absentToday      = absentToday,
                pendingConflicts = pendingConflicts,
                attendancePct    = attendancePct,
                absentPct        = absentPct,
            )
        }
    }

    private fun getStartOfDayMillis(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}