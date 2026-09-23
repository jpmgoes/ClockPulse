package com.bnyro.clock.presentation.screens.agenda.model

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bnyro.clock.App
import com.bnyro.clock.domain.model.AgendaEvent
import com.bnyro.clock.util.AgendaSyncer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AgendaModel(application: Application) : AndroidViewModel(application) {
    private val syncer = AgendaSyncer(application.applicationContext)
    private val repository = (application as App).container.agendaRepository

    val events = repository.getEventsStream().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    var isSyncing by mutableStateOf(false)
        private set
    var hasCompletedInitialSync by mutableStateOf(false)
        private set
    var lastSyncMessage by mutableStateOf<String?>(null)
        private set
    fun sync(requestProviderSync: Boolean = false) {
        if (isSyncing) return
        viewModelScope.launch {
            isSyncing = true
            try {
                lastSyncMessage = when (val result = syncer.sync(requestProviderSync)) {
                    is AgendaSyncer.Result.Success -> "${result.eventCount}"
                    AgendaSyncer.Result.PermissionRequired -> null
                }
            } finally {
                hasCompletedInitialSync = true
                isSyncing = false
            }
        }
    }

    fun setEnabled(event: AgendaEvent, enabled: Boolean) {
        viewModelScope.launch { syncer.setEnabled(event, enabled) }
    }

    fun disconnect(onCleared: () -> Unit) {
        viewModelScope.launch {
            syncer.clearSyncedEvents()
            onCleared()
        }
    }
}
