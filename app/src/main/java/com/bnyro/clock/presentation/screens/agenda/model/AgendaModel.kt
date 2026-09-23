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
    private val sourceRepository = (application as App).container.agendaSourceRepository

    val events = sourceRepository.visibleEventsStream().stateIn(
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
    var failedOAuthAccountIds by mutableStateOf<Set<String>>(emptySet())
        private set
    fun sync(requestProviderSync: Boolean = false) {
        if (isSyncing) return
        viewModelScope.launch {
            isSyncing = true
            try {
                val result = syncer.sync(requestProviderSync)
                failedOAuthAccountIds = (result as? AgendaSyncer.Result.Success)?.failedAccountIds.orEmpty()
                lastSyncMessage = when (result) {
                    is AgendaSyncer.Result.Success -> if (result.failedAccountIds.isEmpty()) "${result.eventCount}" else null
                    AgendaSyncer.Result.PermissionRequired,
                    AgendaSyncer.Result.SourceSelectionRequired,
                    AgendaSyncer.Result.OAuthUnavailable -> null
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
