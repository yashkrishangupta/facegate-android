package com.facegate.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.storage.TemplateRepository
import com.facegate.storage.entity.OverrideEntity
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

data class ScheduleItem(
    val entry: TimetableEntity,
    val status: Status,
    val sessionId: String? = null,
) {
    enum class Status { UPCOMING, ACTIVE, DONE }
}

sealed class ScheduleState {
    object Loading : ScheduleState()
    data class Holiday(val name: String) : ScheduleState()
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

            val isHoliday = try { repository.isHoliday(todayStr) } catch (e: Exception) { false }
            if (isHoliday) {
                val holidayName = try {
                    repository.getAllHolidays().find { it.date == todayStr }?.name ?: "Holiday"
                } catch (e: Exception) {
                    "Holiday"
                }
                _uiState.value = ScheduleState.Holiday(holidayName)
                return@launch
            }

            // Map Calendar day-of-week constants to 1=Mon…5=Fri as stored in TimetableEntity.
            // Calendar.MONDAY = 2, TUESDAY = 3, …, FRIDAY = 6 (Java constant values).
            val calendar = Calendar.getInstance()
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            val mappedDay = when (dayOfWeek) {
                Calendar.MONDAY    -> 1
                Calendar.TUESDAY   -> 2
                Calendar.WEDNESDAY -> 3
                Calendar.THURSDAY  -> 4
                Calendar.FRIDAY    -> 5
                else -> {
                    // Saturday / Sunday → no schedule
                    _uiState.value = ScheduleState.NoSchedule
                    return@launch
                }
            }

            val timetable = try {
                repository.getTimetableForDay(mappedDay)
            } catch (e: Exception) {
                emptyList()
            }

            if (timetable.isEmpty()) {
                _uiState.value = ScheduleState.NoSchedule
                return@launch
            }

            val currentHour   = calendar.get(Calendar.HOUR_OF_DAY)
            val currentMinute = calendar.get(Calendar.MINUTE)
            val currentTotalMinutes = currentHour * 60 + currentMinute

            val items = timetable.map { entry ->
                val entryTotalMinutes = entry.scheduledHour * 60 + entry.scheduledMinute
                val endTotalMinutes   = entryTotalMinutes + entry.windowMinutes

                val status = when {
                    currentTotalMinutes > endTotalMinutes                          -> ScheduleItem.Status.DONE
                    currentTotalMinutes in entryTotalMinutes..endTotalMinutes      -> ScheduleItem.Status.ACTIVE
                    else                                                           -> ScheduleItem.Status.UPCOMING
                }
                ScheduleItem(entry, status)
            }

            _uiState.value = ScheduleState.Loaded(items)
        }
    }

    /**
     * startSession(entry, onStarted)
     * Creates a DB session record, then calls onStarted(sessionId) on the
     * MAIN thread so the Fragment can safely call findNavController().navigate().
     */
    fun startSession(entry: TimetableEntity, onStarted: (sessionId: String) -> Unit) {
        viewModelScope.launch {
            val sessionId = UUID.randomUUID().toString()
            val session = SessionEntity(
                sessionId     = sessionId,
                timetableId   = entry.id,
                subject       = entry.subject,
                batch         = entry.batch,
                startTime     = System.currentTimeMillis(),
                windowMinutes = entry.windowMinutes,
                endedAt       = null,
            )
            try { repository.insertSession(session) } catch (e: Exception) { /* logged by caller */ }
            onStarted(sessionId) // runs on Main — Fragment can navigate safely
        }
    }

    /**
     * startUnplannedSession(...)
     * Creates a session with timetableId = null and writes an override record.
     * onStarted is invoked on the MAIN thread (same reasoning as startSession).
     */
    fun startUnplannedSession(
        subject: String,
        batch: String,
        windowMinutes: Int,
        reason: String,
        onStarted: (sessionId: String) -> Unit,
    ) {
        viewModelScope.launch {
            val sessionId = UUID.randomUUID().toString()
            val session = SessionEntity(
                sessionId     = sessionId,
                timetableId   = null,          // unplanned — no timetable row
                subject       = subject,
                batch         = batch,
                startTime     = System.currentTimeMillis(),
                windowMinutes = windowMinutes,
                endedAt       = null,
            )
            try { repository.insertSession(session) } catch (e: Exception) {}

            val override = OverrideEntity(
                sessionId    = sessionId,
                fieldChanged = "unplanned",
                oldValue     = "none",
                newValue     = "$subject — $batch",
                changedAt    = System.currentTimeMillis(),
                reason       = reason,
            )
            try { repository.insertOverride(override) } catch (e: Exception) {}

            onStarted(sessionId) // runs on Main — Fragment can navigate safely
        }
    }
}
