package com.facegate.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.facegate.storage.TemplateRepository
import com.facegate.storage.entity.ConflictEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ConflictQueueState {
    object Loading                              : ConflictQueueState()
    object Empty                                : ConflictQueueState()
    data class Loaded(val conflicts: List<ConflictEntity>) : ConflictQueueState()
}

@HiltViewModel
class ConflictQueueViewModel @Inject constructor(
    private val repository: TemplateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ConflictQueueState>(ConflictQueueState.Loading)
    val state: StateFlow<ConflictQueueState> = _state

    init {
        loadConflicts()
    }

    fun loadConflicts() {
        viewModelScope.launch {
            _state.value = ConflictQueueState.Loading
            val conflicts = repository.getUnresolvedConflicts()
            _state.value = if (conflicts.isEmpty()) {
                ConflictQueueState.Empty
            } else {
                ConflictQueueState.Loaded(conflicts)
            }
        }
    }

    fun resolveConflict(id: Int) {
        viewModelScope.launch {
            repository.resolveConflict(id)
            loadConflicts() // reload after resolving
        }
    }
}