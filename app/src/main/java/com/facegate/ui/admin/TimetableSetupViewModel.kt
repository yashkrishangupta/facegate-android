package com.facegate.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.storage.TemplateRepository
import com.facegate.storage.entity.TimetableEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TimetableSetupViewModel @Inject constructor(
    private val repository: TemplateRepository,
) : ViewModel() {

    private val _allEntries = MutableStateFlow<List<TimetableEntity>>(emptyList())
    val allEntries: StateFlow<List<TimetableEntity>> = _allEntries

    private val _subjects = MutableStateFlow<List<String>>(emptyList())
    val subjects: StateFlow<List<String>> = _subjects

    private val _batches = MutableStateFlow<List<String>>(emptyList())
    val batches: StateFlow<List<String>> = _batches

    private val _weeklyOffDays = MutableStateFlow<Set<Int>>(emptySet())
    val weeklyOffDays: StateFlow<Set<Int>> = _weeklyOffDays

    init {
        loadAll()
        loadWeeklyOffDays()
    }

    fun loadAll() {
        viewModelScope.launch {
            _allEntries.value = repository.getAllTimetable()
        }
    }

    fun addOrUpdatePeriod(entry: TimetableEntity) {
        viewModelScope.launch {
            repository.insertTimetable(entry)
            loadAll()
        }
    }

    fun deletePeriod(id: Int) {
        viewModelScope.launch {
            repository.deleteTimetable(id)
            loadAll()
        }
    }

    fun getForDay(day: Int): List<TimetableEntity> {
        return _allEntries.value.filter { it.dayOfWeek == day }
    }

    fun loadSubjectsAndBatches() {
        viewModelScope.launch {
            _subjects.value = repository.getAllSubjects()
            _batches.value  = repository.getAllBatches()
        }
    }

    // ── Weekly Off ────────────────────────────────────────────────────────────

    fun loadWeeklyOffDays() {
        viewModelScope.launch {
            _weeklyOffDays.value = repository.getWeeklyOffDays().toSet()
        }
    }

    fun isWeeklyOff(day: Int): Boolean = _weeklyOffDays.value.contains(day)

    fun toggleWeeklyOff(day: Int) {
        viewModelScope.launch {
            repository.setWeeklyOff(day, !isWeeklyOff(day))
            loadWeeklyOffDays()
        }
    }
}