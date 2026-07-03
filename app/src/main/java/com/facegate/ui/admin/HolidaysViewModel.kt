package com.facegate.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.storage.TemplateRepository
import com.facegate.storage.entity.HolidayEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HolidaysViewModel @Inject constructor(
    private val repository: TemplateRepository,
) : ViewModel() {

    private val _holidays = MutableStateFlow<List<HolidayEntity>>(emptyList())
    val holidays: StateFlow<List<HolidayEntity>> = _holidays

    init { loadAll() }

    fun loadAll() {
        viewModelScope.launch {
            _holidays.value = repository.getAllHolidays()
        }
    }

    fun addHoliday(holiday: HolidayEntity) {
        viewModelScope.launch {
            repository.insertHoliday(holiday)
            loadAll()
        }
    }

    fun deleteHoliday(date: String) {
        viewModelScope.launch {
            repository.deleteHoliday(date)
            loadAll()
        }
    }
}