package com.facegate.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.storage.TemplateRepository
import com.facegate.storage.entity.SessionEntity
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

/**
 * One period scheduled on the selected date (e.g. "Period 3"), OR one of the
 * distinct extra-period options ([ReportViewModel.EXTRA_PERIOD_BASE] and below)
 * representing an extra period started that day that doesn't belong to any
 * timetable slot.
 */
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
        val canGoBack     : Boolean,
        val periods       : List<PeriodOption>,
        val selectedPeriod: Int?,
        val batches       : List<BatchOption>,
        val selectedBatch : String?,
        val students      : List<RosterEntry>,
    ) : ExplorerState()
}

/**
 * One resolved (batch, session) pairing feeding the roster — regardless of
 * whether it came from a real timetable slot or an extra period. `session`
 * is null when a timetable slot exists but nobody ever started it that day.
 */
private data class SlotSource(
    val batch  : String,
    val session: SessionEntity?,
)

/**
 * Reports is a **read-only** viewer over the last [REPORT_WINDOW_DAYS] days.
 * Marking/unmarking attendance only happens in Manual Attendance, which is
 * intentionally restricted to a 7-day editable window (today + past 6 days).
 * Reports never writes to the attendance table — it only ever reads status.
 *
 * Why 30 days here vs 7 in Manual Attendance vs a later sync limit of 5 days:
 * these are three independent windows for three different concerns —
 *   • 7 days  = how far back a correction can still be made directly
 *   • 30 days = how far back this app will show a report at all (older
 *                history is meant to be pulled from the website instead)
 *   • 5 days  = (future work) how long a record can sit unsynced before the
 *                background sync job is expected to have pushed it
 * They're deliberately not the same number, so the gaps between them are
 * kept as-is rather than collapsed into one shared constant.
 */
@HiltViewModel
class ReportViewModel @Inject constructor(
    private val repository: TemplateRepository,
    private val syncRepository: com.facegate.sync.SyncRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ExplorerState>(ExplorerState.Loading)
    val state: StateFlow<ExplorerState> = _state

    // Set after a successful refreshFromServer() call — lets the Reports UI
    // show "server says 28/30 present" alongside the locally-computed roster,
    // so a website-side manual correction is visible here even before
    // attendance-down sync (plan.md §6.2) exists to merge it into the local
    // roster automatically. Null until refreshFromServer() is called, and
    // cleared again once the selected date/period/batch changes.
    private val _serverSummary = MutableStateFlow<com.facegate.sync.ReportSummaryDto?>(null)
    val serverSummary: StateFlow<com.facegate.sync.ReportSummaryDto?> = _serverSummary

    private var selectedDate   : Long    = startOfDay(System.currentTimeMillis())
    private var selectedPeriod : Int?    = null
    private var selectedBatch  : String? = null   // null = "All batches"

    // Cached for the currently-loaded date.
    private var timetableForDate: List<TimetableEntity> = emptyList()
    private var extraPeriodSessions: List<SessionEntity> = emptyList()

    init { load() }

    /**
     * Pulls this room's report summaries from the server (GET
     * /api/v1/sync/reports — not built on the backend yet, see
     * API_CONTRACT.md Part 3) and matches the one for the currently-selected
     * period/batch, so a manual correction made on the website (Reports
     * page) is visible here even though it hasn't flowed down into the
     * local attendance table yet. Best-effort — a failure just leaves
     * serverSummary as it was.
     */
    fun refreshFromServer() {
        viewModelScope.launch {
            val period = selectedTimetableEntry() ?: return@launch
            val remoteId = period.remoteTimetableId ?: return@launch
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(selectedDate))

            syncRepository.getReports(since = null).onSuccess { response ->
                _serverSummary.value = response.data?.firstOrNull {
                    it.timetableId == remoteId && it.sessionDate == dateStr &&
                        (selectedBatch == null || it.batchCode == selectedBatch)
                }
            }
        }
    }

    private fun selectedTimetableEntry(): TimetableEntity? =
        timetableForDate.firstOrNull { it.periodNumber == selectedPeriod }

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

            val startOfDayMs = selectedDate
            val endOfDayMs    = selectedDate + DAY_MS - 1

            timetableForDate = repository.getTimetableForDay(dow)
            extraPeriodSessions = repository.getSessionsForDate(startOfDayMs, endOfDayMs)
                .filter { it.timetableId == null }

            if (timetableForDate.isEmpty() && extraPeriodSessions.isEmpty()) {
                _state.value = ExplorerState.NoSchedule(dateLabel)
                return@launch
            }

            val periods = buildPeriodOptions()

            if (selectedPeriod == null || periods.none { it.periodNumber == selectedPeriod }) {
                selectedPeriod = periods.first().periodNumber
                selectedBatch  = null
            }

            renderForPeriodAndBatch(dateLabel, periods)
        }
    }

    private fun buildPeriodOptions(): List<PeriodOption> {
        val regular = timetableForDate
            .map { it.periodNumber }
            .distinct()
            .sorted()
            .map { PeriodOption(it, "Period $it") }

        // Each distinct SUBJECT among today's extra-period sessions becomes its own
        // period option (ordered by when it was first started), instead of lumping
        // every extra period of the day into one "Extra Periods" bucket. Otherwise
        // two different extra periods run for the same batch on the same day (e.g.
        // one for Math, one later for Science) would collapse into a single slot
        // and silently lose one of them.
        val extraOptions = extraPeriodGroupsOrdered().mapIndexed { index, (subject, _) ->
            PeriodOption(EXTRA_PERIOD_BASE - index, "Extra: $subject")
        }

        return regular + extraOptions
    }

    /** Distinct extra-period subjects for the selected date, ordered by the
     *  earliest start time within each subject group. */
    private fun extraPeriodGroupsOrdered(): List<Pair<String, List<SessionEntity>>> =
        extraPeriodSessions
            .groupBy { it.subject }
            .toList()
            .sortedBy { (_, sessions) -> sessions.minOf { it.startTime } }

    /** Jump to an arbitrary date (from the calendar picker). Clamped to the report window. */
    fun selectDate(newDate: Long) {
        val clamped = clampToWindow(startOfDay(newDate))
        if (clamped == selectedDate) return
        selectedDate   = clamped
        selectedPeriod = null
        selectedBatch  = null
        _serverSummary.value = null
        load()
    }

    /** Step one day forward/backward, clamped to [today - (REPORT_WINDOW_DAYS-1), today]. */
    fun stepDate(delta: Int) {
        val candidate = clampToWindow(selectedDate + delta * DAY_MS)
        if (candidate == selectedDate) return
        selectDate(candidate)
    }

    private fun clampToWindow(date: Long): Long {
        val today = startOfDay(System.currentTimeMillis())
        val earliest = today - (REPORT_WINDOW_DAYS - 1) * DAY_MS
        return date.coerceIn(earliest, today)
    }

    /** Earliest selectable date — exposed so the Fragment can bound the DatePickerDialog. */
    fun earliestSelectableDate(): Long =
        startOfDay(System.currentTimeMillis()) - (REPORT_WINDOW_DAYS - 1) * DAY_MS

    fun latestSelectableDate(): Long = startOfDay(System.currentTimeMillis())

    fun selectPeriod(periodNumber: Int) {
        if (periodNumber == selectedPeriod) return
        selectedPeriod = periodNumber
        selectedBatch  = null // reset to "All batches" whenever the period changes
        _serverSummary.value = null
        viewModelScope.launch {
            val dateLabel = dateLabelFmt.format(Date(selectedDate))
            renderForPeriodAndBatch(dateLabel, buildPeriodOptions())
        }
    }

    fun selectBatch(batch: String?) {
        if (batch == selectedBatch) return
        selectedBatch = batch
        _serverSummary.value = null
        viewModelScope.launch {
            val dateLabel = dateLabelFmt.format(Date(selectedDate))
            renderForPeriodAndBatch(dateLabel, buildPeriodOptions())
        }
    }

    /** Resolve the (batch, session) pairs for the currently selected period. */
    private suspend fun slotSourcesForSelectedPeriod(): List<SlotSource> {
        val period = selectedPeriod ?: return emptyList()
        val startOfDayMs = selectedDate
        val endOfDayMs    = selectedDate + DAY_MS - 1

        return if (period <= EXTRA_PERIOD_BASE) {
            // Multiple sessions can exist for the same batch within the SAME extra
            // period/subject on the same day (e.g. restarted) — keep only the most
            // recent per batch, same rule used for de-duping timetabled sessions
            // elsewhere in the app. Different subjects are separate period options
            // (see buildPeriodOptions), so this no longer merges unrelated extra
            // periods together.
            val groups = extraPeriodGroupsOrdered()
            val index  = EXTRA_PERIOD_BASE - period
            val sessionsForThisExtraPeriod = groups.getOrNull(index)?.second ?: emptyList()

            sessionsForThisExtraPeriod
                .groupBy { it.batch }
                .map { (batch, sessions) -> SlotSource(batch, sessions.maxByOrNull { it.startTime }) }
        } else {
            timetableForDate
                .filter { it.periodNumber == period }
                .map { entry ->
                    SlotSource(entry.batch, repository.findSessionForTimetableOnDate(entry.id, startOfDayMs, endOfDayMs))
                }
        }
    }

    private suspend fun renderForPeriodAndBatch(dateLabel: String, periods: List<PeriodOption>) {
        if (selectedPeriod == null) return
        val slotSources = slotSourcesForSelectedPeriod()

        val batchOptions = listOf(BatchOption(null, "All batches")) +
                slotSources.map { it.batch }.distinct().sorted().map { BatchOption(it, it) }

        val relevant = if (selectedBatch == null) slotSources
        else slotSources.filter { it.batch == selectedBatch }

        val startOfDayMs = selectedDate
        val endOfDayMs    = selectedDate + DAY_MS - 1

        val roster = linkedMapOf<String, RosterEntry>()
        for (slot in relevant) {
            val students = repository.getStudentsByClass(slot.batch)
            students.forEach { student ->
                val marked = if (slot.session != null) {
                    repository.isStudentMarkedForSession(student.studentId, slot.session.sessionId)
                } else {
                    repository.isStudentMarkedOnDate(student.studentId, startOfDayMs, endOfDayMs)
                }
                roster[student.studentId] = RosterEntry(student, marked)
            }
        }

        val today = startOfDay(System.currentTimeMillis())
        _state.value = ExplorerState.Loaded(
            dateLabel      = dateLabel,
            canGoForward   = selectedDate < today,
            canGoBack      = selectedDate > today - (REPORT_WINDOW_DAYS - 1) * DAY_MS,
            periods        = periods,
            selectedPeriod = selectedPeriod,
            batches        = batchOptions,
            selectedBatch  = selectedBatch,
            students       = roster.values.toList(),
        )
    }

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
            Calendar.SATURDAY  -> 6
            else               -> 7 // Calendar.SUNDAY
        }

    private val dateLabelFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** How far back Reports will show data at all. Older history is meant
         *  to be viewed on the website, not in-app. */
        private const val REPORT_WINDOW_DAYS = 30

        /** Base sentinel for extra-period options. Each distinct extra-period
         *  subject on the selected date gets its own option at
         *  EXTRA_PERIOD_BASE - index (index 0, 1, 2, …), since extra periods
         *  have no real timetable period number and there can be more than one
         *  of them on the same day. */
        const val EXTRA_PERIOD_BASE = -1000
    }
}