package com.facegate.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.storage.TemplateRepository
import com.facegate.storage.entity.SessionEntity
import com.facegate.storage.entity.TimetableEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * One row in Today's Schedule — either a regular timetabled period
 * ([timetableEntry] set) or an Extra Period ([existingSessionId] set, since an
 * extra period's session is created the moment it's added, not when "Start"
 * is tapped). Extra periods carry their own [scheduledStartTimeMs] up front
 * for the same reason.
 */
data class ScheduleItem(
    val label               : String,
    val subject             : String,
    val batch               : String,
    val scheduledHour       : Int,
    val scheduledMinute     : Int,
    val windowMinutes       : Int,
    val status              : Status,
    val timetableEntry      : TimetableEntity? = null,
    val existingSessionId   : String? = null,
    val scheduledStartTimeMs: Long? = null,
) {
    enum class Status { UPCOMING, ACTIVE, DONE }
}

sealed class ScheduleState {
    object Loading : ScheduleState()
    /** Extra periods can still be added and re-opened on a holiday, so they're
     *  carried alongside the banner rather than being hidden by it. */
    data class Holiday(val name: String, val extraItems: List<ScheduleItem> = emptyList()) : ScheduleState()
    object NoSchedule : ScheduleState()
    data class Loaded(val items: List<ScheduleItem>) : ScheduleState()
}

@HiltViewModel
class TodayScheduleViewModel @Inject constructor(
    private val repository: TemplateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScheduleState>(ScheduleState.Loading)
    val uiState: StateFlow<ScheduleState> = _uiState

    fun loadToday() {
        viewModelScope.launch {
            _uiState.value = ScheduleState.Loading

            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val todayStr = sdf.format(Date())

            val startOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val endOfDay = startOfDay + (24 * 60 * 60 * 1000) - 1

            val calendar            = Calendar.getInstance()
            val currentTotalMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

            // Extra periods can be started on ANY day — holiday, or a normal
            // day with no timetable at all — so they're computed up front
            // and folded into whichever state below applies, instead of only
            // existing on a "normal" scheduled day. This is also what makes an
            // extra period re-openable: once created it has a real SessionEntity,
            // so it shows up here on every subsequent load until its window closes.
            val extraSessions = try {
                repository.getSessionsForDate(startOfDay, endOfDay)
                    .filter { it.timetableId == null }
                    .sortedBy { it.startTime }
            } catch (e: Exception) { emptyList() }

            val extraItems = extraSessions.mapIndexed { index, session ->
                buildExtraScheduleItem(session, index, currentTotalMinutes)
            }

            val isHoliday = try { repository.isHoliday(todayStr) } catch (e: Exception) { false }
            if (isHoliday) {
                val holidayName = try {
                    repository.getAllHolidays().find { it.date == todayStr }?.name ?: "Holiday"
                } catch (e: Exception) {
                    "Holiday"
                }
                _uiState.value = ScheduleState.Holiday(holidayName, extraItems)
                return@launch
            }

            // Map Calendar day-of-week constants to 1=Mon…7=Sun as stored in TimetableEntity.
            // Calendar.MONDAY = 2, TUESDAY = 3, …, SUNDAY = 1 (Java constant values).
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            val mappedDay = when (dayOfWeek) {
                Calendar.MONDAY    -> 1
                Calendar.TUESDAY   -> 2
                Calendar.WEDNESDAY -> 3
                Calendar.THURSDAY  -> 4
                Calendar.FRIDAY    -> 5
                Calendar.SATURDAY  -> 6
                else               -> 7 // Calendar.SUNDAY
            }

            val timetable = try {
                repository.getTimetableForDay(mappedDay)
            } catch (e: Exception) {
                emptyList()
            }

            if (timetable.isEmpty() && extraItems.isEmpty()) {
                _uiState.value = ScheduleState.NoSchedule
                return@launch
            }

            val regularItems = timetable.map { entry ->
                val entryTotalMinutes = entry.scheduledHour * 60 + entry.scheduledMinute
                val endTotalMinutes   = entryTotalMinutes + entry.windowMinutes

                val status = when {
                    currentTotalMinutes > endTotalMinutes                          -> ScheduleItem.Status.DONE
                    currentTotalMinutes in entryTotalMinutes..endTotalMinutes      -> ScheduleItem.Status.ACTIVE
                    else                                                           -> ScheduleItem.Status.UPCOMING
                }
                ScheduleItem(
                    label           = "P${entry.periodNumber}",
                    subject         = entry.subject,
                    batch           = entry.batch,
                    scheduledHour   = entry.scheduledHour,
                    scheduledMinute = entry.scheduledMinute,
                    windowMinutes   = entry.windowMinutes,
                    status          = status,
                    timetableEntry  = entry,
                )
            }

            _uiState.value = ScheduleState.Loaded(regularItems + extraItems)
        }
    }

    /** index is this extra period's position among today's extra sessions
     *  (sorted by start time) — used purely for the "Extra Period N" label. */
    private fun buildExtraScheduleItem(
        session: SessionEntity,
        index: Int,
        currentTotalMinutes: Int,
    ): ScheduleItem {
        val sessionCal = Calendar.getInstance().apply { timeInMillis = session.startTime }
        val hour   = sessionCal.get(Calendar.HOUR_OF_DAY)
        val minute = sessionCal.get(Calendar.MINUTE)
        val entryTotalMinutes = hour * 60 + minute
        val endTotalMinutes    = entryTotalMinutes + session.windowMinutes

        val status = when {
            currentTotalMinutes > endTotalMinutes                     -> ScheduleItem.Status.DONE
            currentTotalMinutes in entryTotalMinutes..endTotalMinutes  -> ScheduleItem.Status.ACTIVE
            else                                                       -> ScheduleItem.Status.UPCOMING
        }

        return ScheduleItem(
            label                = "Extra Period ${index + 1}",
            subject              = session.subject,
            batch                = session.batch,
            scheduledHour        = hour,
            scheduledMinute      = minute,
            windowMinutes        = session.windowMinutes,
            status               = status,
            existingSessionId    = session.sessionId,
            scheduledStartTimeMs = session.startTime,
        )
    }

    /**
     * startSession(entry, onStarted)
     * Creates a DB session record using the SCHEDULED start time (not "now"),
     * then calls onStarted(sessionId, scheduledStartTimeMs) on the MAIN thread
     * so the Fragment can safely call findNavController().navigate().
     */
    fun startSession(entry: TimetableEntity, onStarted: (sessionId: String, scheduledStartTimeMs: Long) -> Unit) {
        viewModelScope.launch {
            // Build the scheduled wall-clock time from today's date + timetable H:M.
            // This is what the pipeline will use as the window-countdown origin so
            // latecomers don't get a full fresh window just because Start was pressed late.
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, entry.scheduledHour)
            cal.set(Calendar.MINUTE,      entry.scheduledMinute)
            cal.set(Calendar.SECOND,      0)
            cal.set(Calendar.MILLISECOND, 0)
            val scheduledStartTimeMs = cal.timeInMillis

            val startOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val endOfDay = startOfDay + (24 * 60 * 60 * 1000) - 1

            // If this period was already started today (teacher backed out and
            // tapped Start again, or rotated through the screen twice), reuse that
            // session instead of inserting a second row for the same period — that
            // duplication is what was showing up as repeated entries for the same
            // period in Manual Attendance's session filter.
            val existing = try {
                repository.findSessionForTimetableOnDate(entry.id, startOfDay, endOfDay)
            } catch (e: Exception) { null }

            if (existing != null) {
                onStarted(existing.sessionId, existing.startTime)
                return@launch
            }

            val sessionId = UUID.randomUUID().toString()
            val sessionDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val session = SessionEntity(
                sessionId     = sessionId,
                timetableId   = entry.id,
                subject       = entry.subject,
                batch         = entry.batch,
                startTime     = scheduledStartTimeMs,   // DB stores scheduled time, not "now"
                windowMinutes = entry.windowMinutes,
                endedAt       = null,
                // entry.remoteTimetableId is null for periods that were only
                // ever created on-device (never matched to a synced server
                // row) — attendance from those sessions is skipped at sync
                // time rather than sent with a missing timetable reference.
                remoteTimetableId = entry.remoteTimetableId,
                sessionDate       = sessionDate,
            )
            try { repository.insertSession(session) } catch (e: Exception) { /* logged by caller */ }
            onStarted(sessionId, scheduledStartTimeMs) // runs on Main — Fragment can navigate safely
        }
    }
}