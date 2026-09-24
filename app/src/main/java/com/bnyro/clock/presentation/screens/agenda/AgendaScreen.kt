package com.bnyro.clock.presentation.screens.agenda

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.AgendaEvent
import com.bnyro.clock.domain.model.AgendaSource
import com.bnyro.clock.domain.model.OAuthAccount
import com.bnyro.clock.navigation.TopBarScaffold
import com.bnyro.clock.presentation.components.ClickableIcon
import com.bnyro.clock.presentation.screens.agenda.model.AgendaAuthorizationLaunch
import com.bnyro.clock.presentation.screens.agenda.model.AgendaModel
import com.bnyro.clock.presentation.screens.agenda.model.AgendaUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class OAuthProvider { GOOGLE, MICROSOFT }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgendaScreen(onClickSettings: () -> Unit, agendaModel: AgendaModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by agendaModel.uiState.collectAsState()
    fun permissionGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(permissionGranted()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
        if (it) agendaModel.sync(true)
    }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        agendaModel.onConsentResult(it.data.takeIf { _ -> it.resultCode == Activity.RESULT_OK })
    }
    LaunchedEffect(agendaModel) {
        agendaModel.authorizationLaunches.collect { launch ->
            if (!agendaModel.isCurrentLaunch(launch)) return@collect
            try {
                when (launch) {
                    is AgendaAuthorizationLaunch.Consent -> consentLauncher.launch(IntentSenderRequest.Builder(launch.intent).build())
                }
            } catch (_: Exception) { agendaModel.authorizationLaunchFailed() }
        }
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasPermission = permissionGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.source, hasPermission, agendaModel.isChangingSource) {
        if (state.source == AgendaSource.OAUTH || state.source == AgendaSource.LOCAL && hasPermission) {
            agendaModel.sync(state.source == AgendaSource.LOCAL)
        }
    }
    DisposableEffect(context, state.source, hasPermission) {
        if (state.source != AgendaSource.LOCAL || !hasPermission) return@DisposableEffect onDispose {}
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { agendaModel.sync() }
        }
        context.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(CalendarContract.Reminders.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    TopBarScaffold(title = stringResource(R.string.agenda), onClickSettings = onClickSettings, actions = {
        if (agendaModel.isSyncing || agendaModel.isChangingSource || agendaModel.isConnecting) {
            // Keep the same 48dp action slot as IconButton so refresh and loading share a center.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                LoadingIndicator(Modifier.size(32.dp))
            }
        } else if (state.source != null) {
            ClickableIcon(imageVector = Icons.Default.Refresh) { agendaModel.sync(state.source == AgendaSource.LOCAL) }
        }
    }) { padding ->
        AgendaSourceContent(
            state = state, hasPermission = hasPermission,
            busy = agendaModel.isChangingSource || agendaModel.isConnecting,
            errorMessage = agendaModel.errorMessage,
            onDismissError = agendaModel::clearError,
            onSelect = { source -> agendaModel.selectSource(source) {
                if (source == AgendaSource.LOCAL && !hasPermission) permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            } },
            onChooseOAuth = { provider ->
                agendaModel.selectSource(AgendaSource.OAUTH) {
                    when (provider) {
                        OAuthProvider.GOOGLE -> (context as? Activity)?.let { activity -> agendaModel.addAccount(activity) }
                        OAuthProvider.MICROSOFT -> (context as? Activity)?.let { activity ->
                            agendaModel.addMicrosoftAccount(activity)
                        }
                    }
                }
            },
            onGrantPermission = { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) },
            onAddGoogleAccount = { (context as? Activity)?.let { activity -> agendaModel.addAccount(activity) } },
            onAddMicrosoftAccount = { (context as? Activity)?.let { activity -> agendaModel.addMicrosoftAccount(activity) } },
            onReconnect = { account ->
                (context as? Activity)?.let { activity ->
                    if (account.provider == "MICROSOFT_GRAPH") agendaModel.addMicrosoftAccount(activity, account)
                    else agendaModel.addAccount(activity, account)
                }
            },
            onRemove = agendaModel::disconnectOAuth,
            onDisconnectAll = agendaModel::disconnectAllOAuth,
            onDisconnectLocal = { agendaModel.disconnectLocal {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                })
            } },
            onEnabledChanged = agendaModel::setEnabled,
            modifier = Modifier.padding(padding)
        )
    }
}

/** Pure UI boundary also used by source-exclusivity instrumented tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaSourceContent(
    state: AgendaUiState,
    hasPermission: Boolean,
    busy: Boolean,
    errorMessage: Int?,
    onDismissError: () -> Unit,
    onSelect: (AgendaSource) -> Unit,
    onChooseOAuth: (OAuthProvider) -> Unit,
    onGrantPermission: () -> Unit,
    onAddGoogleAccount: () -> Unit,
    onAddMicrosoftAccount: () -> Unit,
    onReconnect: (OAuthAccount) -> Unit,
    onRemove: (OAuthAccount) -> Unit,
    onDisconnectAll: () -> Unit,
    onDisconnectLocal: () -> Unit,
    onEnabledChanged: (AgendaEvent, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAccounts by remember { mutableStateOf(false) }
    var disconnectAll by remember { mutableStateOf(false) }
    var disconnectLocal by remember { mutableStateOf(false) }
    var removeAccount by remember { mutableStateOf<OAuthAccount?>(null) }
    var chooseOAuthProvider by remember { mutableStateOf(false) }
    if (!state.loaded) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val showSourceSelector = state.source == null ||
        state.source == AgendaSource.OAUTH && state.accounts.isEmpty()
    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.agenda_error_dialog_title)) },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(onClick = onDismissError, modifier = Modifier.testTag("dismiss-agenda-error")) {
                    Text(stringResource(R.string.agenda_error_dialog_confirm))
                }
            },
            modifier = Modifier.testTag("agenda-error-popup")
        )
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            showSourceSelector -> {
                item { Text(stringResource(R.string.agenda_choose_source), style = MaterialTheme.typography.headlineSmall) }
                item { Text(stringResource(R.string.agenda_choose_description)) }
                item {
                    SourceCard(R.string.agenda_local_source, R.string.agenda_local_description, "choose-local", busy) { onSelect(AgendaSource.LOCAL) }
                }
                item {
                    SourceCard(R.string.agenda_oauth_source, R.string.agenda_oauth_description, "choose-oauth", busy) { chooseOAuthProvider = true }
                }
            }
            state.source == AgendaSource.LOCAL -> {
                item { Text(stringResource(R.string.agenda_local_source), style = MaterialTheme.typography.titleLarge) }
                if (!hasPermission) item {
                    Text(stringResource(R.string.agenda_local_permission))
                    Button(onClick = onGrantPermission, enabled = !busy, modifier = Modifier.testTag("local-permission")) {
                        Text(stringResource(R.string.agenda_connect))
                    }
                }
                item {
                    OutlinedButton(onClick = { disconnectLocal = true }, enabled = !busy, modifier = Modifier.testTag("disconnect-local")) {
                        Text(stringResource(R.string.agenda_disconnect_local))
                    }
                }
            }
            state.source == AgendaSource.OAUTH -> {
                item { Text(stringResource(R.string.agenda_oauth_source), style = MaterialTheme.typography.titleLarge) }
                item {
                    Button(onClick = { showAccounts = true }, enabled = !busy, modifier = Modifier.testTag("manage-oauth-accounts")) {
                        Text(stringResource(R.string.agenda_manage_oauth_accounts, state.accounts.size))
                    }
                }
                item {
                    OutlinedButton(
                        onClick = {
                            if (state.accounts.isEmpty()) onDisconnectAll() else disconnectAll = true
                        },
                        enabled = !busy,
                        modifier = Modifier.testTag("choose-agenda-source")
                    ) { Text(stringResource(R.string.agenda_change_source)) }
                }
            }
        }
        if (!showSourceSelector) {
            item { Text(stringResource(R.string.agenda_window), style = MaterialTheme.typography.bodyMedium) }
            // Defend at the rendering boundary as well as in the repository/model.
            val visible = state.events.filter { event ->
                if (state.source == AgendaSource.LOCAL) hasPermission && event.connectionId == null
                else event.connectionId != null && state.accounts.any { it.id == event.connectionId }
            }
            if (visible.isEmpty()) item { Text(stringResource(R.string.agenda_empty)) }
            items(visible, key = { "event:${it.eventKey}" }) { event ->
                val providerAndAccount = if (state.source == AgendaSource.LOCAL) {
                    stringResource(R.string.agenda_event_provider_local)
                } else {
                    state.accounts.find { it.id == event.connectionId }?.let { account ->
                        stringResource(R.string.agenda_event_provider, account.providerDisplayName, account.displayName, account.email)
                    } ?: stringResource(R.string.agenda_event_provider_oauth_unknown)
                }
                AgendaEventItem(event, providerAndAccount, onEnabledChanged, !busy)
            }
        }
    }
    if (showAccounts && state.source == AgendaSource.OAUTH) {
        ModalBottomSheet(onDismissRequest = { showAccounts = false }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.agenda_oauth_accounts_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.agenda_oauth_accounts_description), style = MaterialTheme.typography.bodyMedium)
                state.accounts.forEach { account ->
                    OAuthAccountCard(account, busy, onReconnect, { removeAccount = account })
                }
                Button(onClick = onAddGoogleAccount, enabled = !busy, modifier = Modifier.testTag("add-google-account")) {
                    Text(stringResource(R.string.agenda_add_google_account))
                }
                OutlinedButton(onClick = onAddMicrosoftAccount, enabled = !busy, modifier = Modifier.testTag("add-microsoft-account")) {
                    Text(stringResource(R.string.agenda_add_microsoft_account))
                }
                OutlinedButton(
                    onClick = { disconnectAll = true },
                    enabled = !busy,
                    modifier = Modifier.testTag("disconnect-all")
                ) { Text(stringResource(R.string.agenda_disconnect_all)) }
                Text(stringResource(R.string.agenda_oauth_source_lock), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (chooseOAuthProvider) {
        AlertDialog(
            onDismissRequest = { chooseOAuthProvider = false },
            title = { Text(stringResource(R.string.agenda_choose_oauth_provider)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.agenda_choose_oauth_provider_description))
                    Button(
                        onClick = { chooseOAuthProvider = false; onChooseOAuth(OAuthProvider.GOOGLE) },
                        modifier = Modifier.fillMaxWidth().testTag("choose-google-oauth")
                    ) { Text(stringResource(R.string.agenda_google_provider)) }
                    OutlinedButton(
                        onClick = { chooseOAuthProvider = false; onChooseOAuth(OAuthProvider.MICROSOFT) },
                        modifier = Modifier.fillMaxWidth().testTag("choose-microsoft-oauth")
                    ) { Text(stringResource(R.string.agenda_microsoft_provider)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chooseOAuthProvider = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (disconnectAll) DisconnectDialog(R.string.agenda_disconnect_all, R.string.agenda_disconnect_all_description,
        onDismiss = { disconnectAll = false }, onConfirm = { disconnectAll = false; showAccounts = false; onDisconnectAll() })
    if (disconnectLocal) DisconnectDialog(R.string.agenda_disconnect_local, R.string.agenda_disconnect_description,
        onDismiss = { disconnectLocal = false }, onConfirm = { disconnectLocal = false; onDisconnectLocal() })
    removeAccount?.let { account ->
        AlertDialog(onDismissRequest = { removeAccount = null },
            title = { Text(stringResource(R.string.agenda_remove_account)) },
            text = { Text(stringResource(R.string.agenda_remove_description, account.email)) },
            confirmButton = { Button(onClick = { removeAccount = null; onRemove(account) }) { Text(stringResource(R.string.agenda_remove_account)) } },
            dismissButton = { TextButton(onClick = { removeAccount = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun OAuthAccountCard(
    account: OAuthAccount,
    busy: Boolean,
    onReconnect: (OAuthAccount) -> Unit,
    onRemove: () -> Unit
) {
    Card(Modifier.fillMaxWidth().testTag("oauth-account")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(account.providerDisplayName, style = MaterialTheme.typography.labelLarge)
            Text(account.displayName, style = MaterialTheme.typography.titleMedium)
            Text(account.email, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onReconnect(account) }, enabled = !busy) { Text(stringResource(R.string.agenda_reconnect)) }
                TextButton(onClick = onRemove, enabled = !busy) { Text(stringResource(R.string.agenda_remove_account)) }
            }
        }
    }
}

@Composable
private fun SourceCard(title: Int, description: Int, tag: String, busy: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(description), modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun DisconnectDialog(title: Int, description: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(title)) }, text = { Text(stringResource(description)) },
        confirmButton = { Button(onClick = onConfirm, modifier = Modifier.testTag("confirm-disconnect")) { Text(stringResource(R.string.agenda_disconnect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun AgendaEventItem(event: AgendaEvent, providerAndAccount: String, onEnabledChanged: (AgendaEvent, Boolean) -> Unit, enabled: Boolean) {
    val dateTime = Instant.ofEpochMilli(event.beginAt).atZone(ZoneId.systemDefault())
    val alarmTime = dateTime.minusMinutes(event.reminderMinutes.toLong())
    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.titleMedium)
                Text("${dateFormatter.format(dateTime)} · ${timeFormatter.format(dateTime)}", style = MaterialTheme.typography.bodyMedium)
                Text(providerAndAccount, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.agenda_alarm_at, timeFormatter.format(alarmTime)), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = event.enabled, enabled = enabled, onCheckedChange = { onEnabledChanged(event, it) })
        }
    }
}
