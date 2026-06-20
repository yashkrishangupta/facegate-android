package com.facegate.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.storage.TemplateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class ReportStats(
    val totalStudents  : Int   = 0,
    val presentToday   : Int   = 0,
    val absentToday    : Int   = 0,
    val attendancePct  : String = "0%",
    val totalRecordsEver: Int  = 0,
)

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val repository: TemplateRepository,
) : ViewModel() {

    private val _stats = MutableStateFlow(ReportStats())
    val stats: StateFlow<ReportStats> = _stats

    init {
        loadStats()
    }

    fun loadStats() {
        viewModelScope.launch {
            val totalStudents    = repository.getStudents().size
            val todayStart       = getStartOfDayMillis()
            val presentToday     = repository.getTodayAttendance(todayStart).size
            val absentToday      = (totalStudents - presentToday).coerceAtLeast(0)
            val totalRecordsEver = repository.getAllAttendance().size

            val pct = if (totalStudents > 0) {
                "${((presentToday.toFloat() / totalStudents) * 100).toInt()}%"
            } else "0%"

            _stats.value = ReportStats(
                totalStudents    = totalStudents,
                presentToday     = presentToday,
                absentToday      = absentToday,
                attendancePct    = pct,
                totalRecordsEver = totalRecordsEver,
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