package com.bnyro.clock.presentation.screens.agenda

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.AgendaEvent
import com.bnyro.clock.navigation.TopBarScaffold
import com.bnyro.clock.presentation.components.ClickableIcon
import com.bnyro.clock.presentation.screens.agenda.model.AgendaModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgendaScreen(onClickSettings: () -> Unit, agendaModel: AgendaModel) {
    val context = LocalContext.current
    var hasCalendarPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCalendarPermission = granted
        if (granted) agendaModel.sync()
    }
    val events by agendaModel.events.collectAsState()
    var showDisconnectDialog by remember { mutableStateOf(false) }

    TopBarScaffold(
        title = stringResource(R.string.agenda),
        onClickSettings = onClickSettings,
        actions = {
            if (agendaModel.isSyncing) {
                LoadingIndicator(modifier = Modifier.size(32.dp))
            } else {
                ClickableIcon(imageVector = Icons.Default.Refresh) {
                    if (hasCalendarPermission) agendaModel.sync()
                    else permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                if (!hasCalendarPermission) {
                    AgendaPermissionContent { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) }
                } else {
                    ReminderOptions(
                        reminderMinutes = agendaModel.reminderMinutes,
                        onReminderSelected = agendaModel::updateReminderMinutes
                    )
                    DisconnectCalendarButton { showDisconnectDialog = true }
                    if (events.isEmpty()) {
                        EmptyAgendaContent()
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(events, key = { it.eventKey }) { event ->
                                AgendaEventItem(event, agendaModel::setEnabled)
                            }
                        }
                    }
                }
            }
            if (agendaModel.isSyncing) {
                AgendaSyncLoadingOverlay(
                    isInitialSync = !agendaModel.hasCompletedInitialSync
                )
            }
        }
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text(stringResource(R.string.agenda_disconnect_title)) },
            text = { Text(stringResource(R.string.agenda_disconnect_description)) },
            confirmButton = {
                Button(onClick = {
                    showDisconnectDialog = false
                    agendaModel.disconnect {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    }
                }) {
                    Text(stringResource(R.string.agenda_disconnect))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDisconnectDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AgendaSyncLoadingOverlay(isInitialSync: Boolean) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Card {
                Column(
                    modifier = Modifier.padding(horizontal = 40.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LoadingIndicator(modifier = Modifier.size(96.dp))
                    Text(
                        text = stringResource(
                            if (isInitialSync) R.string.agenda_initial_sync_title
                            else R.string.agenda_syncing_title
                        ),
                        modifier = Modifier.padding(top = 24.dp),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(R.string.agenda_syncing_description),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun AgendaPermissionContent(onConnect: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.padding(12.dp))
        Text(stringResource(R.string.agenda_connect_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.agenda_connect_description),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onConnect, modifier = Modifier.padding(top = 20.dp)) {
            Text(stringResource(R.string.agenda_connect))
        }
    }
}

@Composable
private fun ReminderOptions(reminderMinutes: Int, onReminderSelected: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.agenda_window), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            listOf(15, 30, 60).forEach { minutes ->
                FilterChip(
                    selected = reminderMinutes == minutes,
                    onClick = { onReminderSelected(minutes) },
                    label = { Text(stringResource(R.string.agenda_reminder_minutes, minutes)) }
                )
            }
        }
    }
}

@Composable
private fun DisconnectCalendarButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(stringResource(R.string.agenda_disconnect))
    }
}

@Composable
private fun EmptyAgendaContent() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CalendarMonth, null)
        Text(
            stringResource(R.string.agenda_empty),
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun AgendaEventItem(event: AgendaEvent, onEnabledChanged: (AgendaEvent, Boolean) -> Unit) {
    val dateTime = Instant.ofEpochMilli(event.beginAt).atZone(ZoneId.systemDefault())
    val alarmTime = dateTime.minusMinutes(
        com.bnyro.clock.util.Preferences.instance.getInt(
            com.bnyro.clock.util.Preferences.agendaReminderMinutesKey, 60
        ).toLong()
    )
    val locale = Locale.getDefault()
    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${dateFormatter.format(dateTime)} · ${timeFormatter.format(dateTime)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.agenda_alarm_at, timeFormatter.format(alarmTime)),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = event.enabled, onCheckedChange = { onEnabledChanged(event, it) })
        }
    }
}
