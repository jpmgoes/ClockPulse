package com.bnyro.clock.presentation.screens.agenda.model

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bnyro.clock.App
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.AgendaEvent
import com.bnyro.clock.domain.model.AgendaSource
import com.bnyro.clock.domain.model.OAuthAccount
import com.bnyro.clock.util.AgendaSyncer
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.google.GoogleAuthorization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AgendaUiState(
    val loaded: Boolean = false,
    val source: AgendaSource? = null,
    val accounts: List<OAuthAccount> = emptyList(),
    val events: List<AgendaEvent> = emptyList()
)

sealed interface AgendaAuthorizationLaunch {
    val epoch: Long
    data class ChooseAccount(override val epoch: Long, val intent: Intent) : AgendaAuthorizationLaunch
    data class Consent(override val epoch: Long, val intent: PendingIntent) : AgendaAuthorizationLaunch
}

class AgendaModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val syncer = AgendaSyncer(application.applicationContext)
    private val sourceRepository = app.container.agendaSourceRepository
    private val authorizer get() = app.googleCalendarAuthorizer
    private val launches = Channel<AgendaAuthorizationLaunch>(Channel.BUFFERED)
    val authorizationLaunches = launches.receiveAsFlow()
    // Metadata only; no token or consent result enters persisted or saved state.
    private data class Connection(val epoch: Long, val expectedId: String?)
    private var connection: Connection? = null
    private var authorizationAttempt = 0L
    private var refreshRequested = false
    private var providerRefreshRequested = false

    fun isCurrentLaunch(launch: AgendaAuthorizationLaunch): Boolean = connection?.epoch == launch.epoch && isConnecting

    val uiState = combine(
        sourceRepository.currentStream(),
        app.container.oauthAccountsRepository.getAccountsStream(),
        app.container.agendaRepository.getEventsStream()
    ) { source, accounts, events ->
        AgendaUiState(true, source,
            accounts.takeIf { source == AgendaSource.OAUTH }.orEmpty(),
            events.filter {
                when (source) {
                    AgendaSource.LOCAL -> it.connectionId == null
                    AgendaSource.OAUTH -> it.connectionId != null && accounts.any { a -> a.id == it.connectionId }
                    null -> false
                }
            })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgendaUiState())

    var isSyncing by mutableStateOf(false)
        private set
    var isConnecting by mutableStateOf(false)
        private set
    var isChangingSource by mutableStateOf(false)
        private set
    var hasCompletedInitialSync by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<Int?>(null)
        private set
    var failedOAuthAccountIds by mutableStateOf<Set<String>>(emptySet())
        private set

    fun clearError() { errorMessage = null }

    fun selectSource(source: AgendaSource, onSelected: () -> Unit = {}) {
        if (isChangingSource || isConnecting) return
        isChangingSource = true
        viewModelScope.launch {
            try {
                syncer.selectSource(source)
                errorMessage = null
                hasCompletedInitialSync = false
                onSelected()
                if (source == AgendaSource.OAUTH) addAccount()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { errorMessage = R.string.agenda_action_failed
            } finally { isChangingSource = false }
        }
    }

    fun sync(requestProviderSync: Boolean = false) {
        if (isChangingSource) return
        if (isSyncing) {
            refreshRequested = true
            providerRefreshRequested = providerRefreshRequested || requestProviderSync
            return
        }
        isSyncing = true
        viewModelScope.launch {
            try {
                val result = syncer.sync(requestProviderSync)
                failedOAuthAccountIds = (result as? AgendaSyncer.Result.Success)?.failedAccountIds.orEmpty()
                errorMessage = if (failedOAuthAccountIds.isNotEmpty()) R.string.agenda_partial_sync else null
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { errorMessage = R.string.agenda_sync_failed
            } finally {
                hasCompletedInitialSync = true
                isSyncing = false
                if (refreshRequested) {
                    val requestProvider = providerRefreshRequested
                    refreshRequested = false
                    providerRefreshRequested = false
                    sync(requestProvider)
                }
            }
        }
    }

    fun addAccount(account: OAuthAccount? = null) {
        if (isConnecting) return
        isConnecting = true
        errorMessage = null
        val attempt = ++authorizationAttempt
        viewModelScope.launch {
            try {
                val pending = Connection(sourceRepository.beginOAuthConnection(), account?.id)
                if (attempt != authorizationAttempt) {
                    sourceRepository.cancelOAuthConnection(pending.epoch)
                    return@launch
                }
                connection = pending
                if (account == null) launches.send(AgendaAuthorizationLaunch.ChooseAccount(pending.epoch, authorizer.accountChooserIntent()))
                else handleAuthorization(pending, authorizer.authorize(account.email))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if (attempt == authorizationAttempt) failAuthorization() }
        }
    }

    fun onAccountChosen(data: Intent?) {
        val pending = connection ?: return
        viewModelScope.launch {
            try {
                val email = authorizer.selectedEmail(data)
                if (email == null) finishConnection(pending)
                else handleAuthorization(pending, authorizer.authorize(email))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { failAuthorization(pending) }
        }
    }

    fun onConsentResult(data: Intent?) {
        val pending = connection ?: return
        viewModelScope.launch {
            try {
                if (data == null) finishConnection(pending)
                else handleAuthorization(pending, authorizer.finishAuthorization(data))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { failAuthorization(pending) }
        }
    }

    fun authorizationLaunchFailed() {
        viewModelScope.launch { failAuthorization() }
    }

    private suspend fun handleAuthorization(pending: Connection, authorization: GoogleAuthorization) {
        if (connection != pending) return
        when (authorization) {
            is GoogleAuthorization.ConsentRequired -> launches.send(AgendaAuthorizationLaunch.Consent(pending.epoch, authorization.pendingIntent))
            is GoogleAuthorization.Authorized -> {
                val profile = app.googleCalendarApi.profile(authorization.accessToken)
                if (connection != pending) return
                check(pending.expectedId == null || pending.expectedId == profile.id)
                val accepted = sourceRepository.connectOAuth(pending.epoch, profile) {
                    authorizer.rememberToken(profile, authorization)
                }
                finishConnection(pending)
                if (accepted) sync()
            }
        }
    }

    private suspend fun finishConnection(pending: Connection) {
        sourceRepository.cancelOAuthConnection(pending.epoch)
        if (connection == pending) {
            connection = null
            isConnecting = false
        }
    }

    private suspend fun failAuthorization(pending: Connection? = connection) {
        if (pending != null && pending != connection) return
        if (pending != null) finishConnection(pending) else isConnecting = false
        errorMessage = R.string.agenda_authorization_failed
    }

    fun setEnabled(event: AgendaEvent, enabled: Boolean) {
        viewModelScope.launch {
            try { syncer.setEnabled(event, enabled)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { errorMessage = R.string.agenda_action_failed }
        }
    }

    fun disconnectLocal(onCleared: () -> Unit) = disconnect {
        syncer.clearSyncedEvents()
        onCleared()
    }

    fun disconnectAllOAuth() = disconnect {
        syncer.disconnectAllOAuth(authorizer::revoke)
    }

    fun disconnectOAuth(account: OAuthAccount) = disconnect {
        sourceRepository.disconnectOAuth(account.id,
            cancelAlarm = { AlarmHelper.cancel(app, it) }, revokeAccount = authorizer::revoke)
    }

    private fun disconnect(action: suspend () -> Unit) {
        if (isChangingSource) return
        isChangingSource = true
        ++authorizationAttempt
        val oldConnection = connection
        connection = null
        isConnecting = false
        viewModelScope.launch {
            try {
                oldConnection?.let { sourceRepository.cancelOAuthConnection(it.epoch) }
                action()
                errorMessage = null
                failedOAuthAccountIds = emptySet()
                hasCompletedInitialSync = false
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { errorMessage = R.string.agenda_disconnect_failed
            } finally { isChangingSource = false }
        }
    }
}
