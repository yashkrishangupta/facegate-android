package com.facegate.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.storage.TemplateRepository
import com.facegate.storage.dao.ClassAttendanceSummary
import com.facegate.storage.dao.DailyAttendanceCount
import com.facegate.storage.entity.AttendanceEntity
import com.facegate.storage.entity.SessionEntity
import com.facegate.storage.entity.TimetableEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

enum class ReportPeriod { TODAY, MONTH }

/**
 * One logical "period" in the session breakdown.
 * Multiple sessions for the same timetable slot are collapsed into a single
 * PeriodSummary so the admin sees one row per teaching period, not one row
 * per time attendance was taken.
 */
data class PeriodSummary(
    val subject      : String,
    val batch        : String,
    /** "Period 3", "Ad-hoc", etc. */
    val periodLabel  : String,
    val startTime    : Long,
    val windowMinutes: Int,
    /** How many separate sessions were started for this slot (>1 = ran twice). */
    val sessionCount : Int,
    /** Unique students present across all sessions for this slot. */
    val uniquePresent: Int,
)

data class ReportStats(
    val period            : ReportPeriod                       = ReportPeriod.TODAY,
    val totalStudents     : Int                                = 0,
    val presentCount      : Int                                = 0,
    val absentCount       : Int                                = 0,
    val attendancePct     : String                             = "0%",
    val totalRecordsEver  : Int                                = 0,
    val classBreakdown    : List<ClassAttendanceSummary>       = emptyList(),
    /** Grouped periods — one entry per unique teaching slot, sessions merged. */
    val periodSummaries   : List<PeriodSummary>                = emptyList(),
    /** keyed by "yyyy-MM-dd" → periods for that day (used by calendar tap). */
    val periodsByDate     : Map<String, List<PeriodSummary>>   = emptyMap(),
    val isHoliday         : Boolean                            = false,
    val holidayName       : String                             = "",
    val batches           : List<String>                       = emptyList(),
    val selectedBatch     : String?                            = null,
    val allPeriodSummaries: List<PeriodSummary>                = emptyList(),
    val allClassBreakdown : List<ClassAttendanceSummary>       = emptyList(),
    val monthLabel        : String                             = "",
    val dailyCounts       : List<DailyAttendanceCount>         = emptyList(),
    val canGoForward      : Boolean                            = false,
)

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val repository: TemplateRepository,
) : ViewModel() {

    private val _stats = MutableStateFlow(ReportStats())
    val stats: StateFlow<ReportStats> = _stats

    private var period: ReportPeriod = ReportPeriod.TODAY

    private var monthOffset: Int = 0

    companion object {
        const val MONTH_HISTORY_LIMIT = 12
    }

    init { loadStats() }

    fun loadStats() {
        viewModelScope.launch {
            when (period) {
                ReportPeriod.TODAY -> loadToday()
                ReportPeriod.MONTH -> loadMonth()
            }
        }
    }

    fun setPeriod(p: ReportPeriod) { period = p; loadStats() }

    fun stepMonth(delta: Int) {
        val newOffset = (monthOffset + delta).coerceIn(-(MONTH_HISTORY_LIMIT - 1), 0)
        if (newOffset == monthOffset) return
        monthOffset = newOffset
        loadStats()
    }

    fun filterByBatch(batch: String?) {
        val current = _stats.value
        val filtered = if (batch == null) current.allPeriodSummaries
        else current.allPeriodSummaries.filter {
            it.batch.equals(batch, ignoreCase = true)
        }
        _stats.value = current.copy(
            selectedBatch    = batch,
            periodSummaries  = filtered,
            classBreakdown   = current.allClassBreakdown,
        )
    }

    // ── Today ─────────────────────────────────────────────────────────────────

    private suspend fun loadToday() {
        val todayStr = getTodayString()
        val startMs  = dayStart(Calendar.getInstance())
        val endMs    = dayEnd(Calendar.getInstance())

        val holiday = repository.isHoliday(todayStr)
        if (holiday) {
            val name = repository.getAllHolidays()
                .firstOrNull { it.date == todayStr }?.name ?: "Holiday"
            _stats.value = ReportStats(isHoliday = true, holidayName = name, period = ReportPeriod.TODAY)
            return
        }

        val totalStudents   = repository.getStudentCount()
        val attendance      = repository.getAttendanceForRange(startMs, endMs)
        // Unique students present across all today's sessions
        val uniquePresent   = attendance.map { it.studentId }.distinct().size
        val absentToday     = (totalStudents - uniquePresent).coerceAtLeast(0)
        val totalRecords    = repository.getAllAttendance().size
        val classBreakdown  = repository.getClassWiseAttendance(startMs, endMs)
        val sessions        = repository.getSessionsForDate(startMs, endMs)
        val timetable       = repository.getAllTimetable()
        val timetableById   = timetable.associateBy { it.id }

        val periodSummaries = buildPeriodSummaries(sessions, attendance, timetableById)
        val batches         = sessions.map { it.batch }.distinct().sorted()
        val currentBatch    = _stats.value.selectedBatch

        val pct = pctString(uniquePresent, totalStudents)

        _stats.value = ReportStats(
            period             = ReportPeriod.TODAY,
            totalStudents      = totalStudents,
            presentCount       = uniquePresent,
            absentCount        = absentToday,
            attendancePct      = pct,
            totalRecordsEver   = totalRecords,
            classBreakdown     = classBreakdown,
            periodSummaries    = applyBatchFilter(periodSummaries, currentBatch),
            allPeriodSummaries = periodSummaries,
            allClassBreakdown  = classBreakdown,
            batches            = batches,
            selectedBatch      = currentBatch,
        )
    }

    // ── Month ─────────────────────────────────────────────────────────────────

    private suspend fun loadMonth() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, monthOffset)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val startMs    = dayStart(cal)
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        val endMs      = dayEnd(cal)

        val labelFmt   = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val monthLabel = labelFmt.format(cal.time)

        val totalStudents  = repository.getStudentCount()
        val attendance     = repository.getAttendanceForRange(startMs, endMs)
        val presentCount   = attendance.map { it.studentId }.distinct().size
        val classBreakdown = repository.getClassWiseAttendance(startMs, endMs)
        val sessions       = repository.getSessionsForDate(startMs, endMs)
        val timetable      = repository.getAllTimetable()
        val timetableById  = timetable.associateBy { it.id }
        val dailyCounts    = repository.getDailyCountsInRange(startMs, endMs)
        val batches        = sessions.map { it.batch }.distinct().sorted()
        val currentBatch   = _stats.value.selectedBatch

        val periodSummaries = buildPeriodSummaries(sessions, attendance, timetableById)

        // Group periods by date for calendar cell taps
        val dateFmt      = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val periodsByDate = periodSummaries.groupBy { p ->
            dateFmt.format(java.util.Date(p.startTime))
        }

        val sessionDays  = dailyCounts.size.coerceAtLeast(1)
        val avgDailyPct  = if (totalStudents > 0)
            ((dailyCounts.sumOf { it.presentCount }.toFloat() / (sessionDays * totalStudents)) * 100).toInt()
        else 0

        _stats.value = ReportStats(
            period             = ReportPeriod.MONTH,
            totalStudents      = totalStudents,
            presentCount       = presentCount,
            absentCount        = 0,
            attendancePct      = "$avgDailyPct%",
            totalRecordsEver   = attendance.size,
            classBreakdown     = classBreakdown,
            periodSummaries    = applyBatchFilter(periodSummaries, currentBatch),
            allPeriodSummaries = periodSummaries,
            allClassBreakdown  = classBreakdown,
            periodsByDate      = periodsByDate,
            batches            = batches,
            selectedBatch      = currentBatch,
            monthLabel         = monthLabel,
            dailyCounts        = dailyCounts,
            canGoForward       = monthOffset < 0,
        )
    }

    // ── Period grouping ────────────────────────────────────────────────────────

    /**
     * Collapse multiple sessions for the same timetable slot into one row.
     * Sessions that share a [timetableId] are grouped together; sessions without
     * one (ad-hoc) are grouped by subject+batch.
     * Only unique students are counted — a student present in two re-runs of the
     * same period is still counted once.
     */
    private fun buildPeriodSummaries(
        sessions     : List<SessionEntity>,
        attendance   : List<AttendanceEntity>,
        timetableById: Map<Int, TimetableEntity>,
    ): List<PeriodSummary> {
        // Key: timetableId (as string) or "adhoc::<subject>::<batch>" for unplanned sessions
        val groups = sessions.groupBy { session ->
            session.timetableId?.toString()
                ?: "adhoc::${session.subject}::${session.batch}"
        }
        return groups.map { (_, sessionList) ->
            val pivot      = sessionList.minByOrNull { it.startTime }!!
            val allPresent = sessionList.flatMap { s ->
                attendance.filter { it.sessionId == s.sessionId }
            }.map { it.studentId }.distinct()

            val ttEntry    = pivot.timetableId?.let { timetableById[it] }
            val periodLabel = when {
                ttEntry != null -> "Period ${ttEntry.periodNumber}"
                else            -> "Ad-hoc"
            }

            PeriodSummary(
                subject       = pivot.subject,
                batch         = pivot.batch,
                periodLabel   = periodLabel,
                startTime     = pivot.startTime,
                windowMinutes = pivot.windowMinutes,
                sessionCount  = sessionList.size,
                uniquePresent = allPresent.size,
            )
        }.sortedBy { it.startTime }
    }

    private fun applyBatchFilter(
        summaries : List<PeriodSummary>,
        batch     : String?,
    ): List<PeriodSummary> =
        if (batch == null) summaries
        else summaries.filter { it.batch.equals(batch, ignoreCase = true) }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getTodayString() =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date())

    private fun dayStart(cal: Calendar): Long = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dayEnd(cal: Calendar): Long = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59);      set(Calendar.MILLISECOND, 999)
    }.timeInMillis

    private fun pctString(present: Int, total: Int): String =
        if (total > 0) "${((present.toFloat() / total) * 100).toInt()}%" else "0%"
}