package com.bnyro.clock.util

import com.bnyro.clock.domain.model.AgendaEvent
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.OAuthAccount
import com.bnyro.clock.domain.model.RepeatUnit
import com.bnyro.clock.domain.repository.AgendaRepository
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.domain.repository.OAuthAccountsRepository
import com.bnyro.clock.util.google.RemoteAgendaEvent
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Applies complete per-account snapshots while AgendaSourceRepository holds the source lock. */
class OAuthAgendaSync(
    private val accounts: OAuthAccountsRepository,
    private val events: AgendaRepository,
    private val alarms: AlarmRepository,
    private val fetchEvents: suspend (OAuthAccount, Long, Long) -> List<RemoteAgendaEvent>,
    private val cancelAlarm: (Alarm) -> Unit,
    private val enqueueAlarm: (Alarm) -> Unit,
    private val untitledEvent: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {
    data class Outcome(
        val eventCount: Int,
        val failedAccountIds: Set<String>,
        val addedEventsByAccount: Map<String, Int>
    )

    /** Must only be invoked by the OAuth branch of AgendaSourceRepository.sync. */
    suspend fun syncAccounts(): Outcome {
        val now = clock()
        val zoneId = zone()
        val start = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate().atStartOfDay(zoneId)
        val end = start.plusDays(7)
        val profiles = accounts.getAccounts()
        val connectedIds = profiles.mapTo(mutableSetOf()) { it.id }
        val prefixes = profiles.filter { it.id in connectedIds }.map(::accountEventPrefix)
        removeEvents(
            events.getEvents().filter { it.connectionId != null && it.connectionId !in connectedIds },
            ownsAlarm = { key ->
                (key.startsWith("google:") || key.startsWith("microsoft:")) && prefixes.none(key::startsWith)
            }
        )
        val failed = mutableSetOf<String>()
        val addedEventsByAccount = mutableMapOf<String, Int>()
        for (account in profiles.filter { it.state == "CONNECTED" }) {
            val snapshot = try {
                fetchEvents(account, start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            } catch (_: IOException) {
                // A failed/partial request is not a deletion snapshot. Other accounts can sync.
                failed += account.id
                continue
            }
            require(snapshot.all { it.connectionId == account.id && it.eventKey.startsWith(accountEventPrefix(account)) })
            val addedEvents = updateAccount(account.id, snapshot, zoneId)
            if (addedEvents > 0) addedEventsByAccount[account.id] = addedEvents
            accounts.updateLastSyncedAt(account.id, now)
        }
        return Outcome(
            eventCount = events.getEvents().count { it.connectionId in connectedIds },
            failedAccountIds = failed,
            addedEventsByAccount = addedEventsByAccount
        )
    }

    private suspend fun updateAccount(accountId: String, snapshot: List<RemoteAgendaEvent>, zoneId: ZoneId): Int {
        val existing = events.getEvents().filter { it.connectionId == accountId }.associateBy { it.eventKey }
        var addedEvents = 0
        for (remote in snapshot) {
            val previous = existing[remote.eventKey]
            if (previous == null) addedEvents++
            val enabled = previous?.enabled ?: true
            // Google string IDs live in the namespaced key; the numeric columns belong to Android.
            val event = AgendaEvent(
                eventKey = remote.eventKey, calendarEventId = 0L, calendarId = 0L,
                title = remote.title.ifBlank { untitledEvent }, beginAt = remote.beginAt, endAt = remote.endAt,
                reminderMinutes = remote.reminderMinutes, alarmId = 0L,
                enabled = enabled, connectionId = accountId
            )
            val reminder = Instant.ofEpochMilli(remote.beginAt - TimeUnit.MINUTES.toMillis(remote.reminderMinutes.toLong()))
                .atZone(zoneId)
            val stored = previous?.let { alarms.getAlarmById(it.alarmId) }
                ?.takeIf { it.agendaEventKey == remote.eventKey }
                ?: alarms.getAlarms().firstOrNull { it.agendaEventKey == remote.eventKey }
            val alarm = Alarm(
                id = stored?.id ?: 0L, time = (reminder.hour * 60L + reminder.minute) * 60_000L,
                label = event.title, enabled = enabled, agendaEventKey = event.eventKey,
                startDate = reminder.toLocalDate().toEpochDay(), repeatUnit = RepeatUnit.DAY, endOccurrences = 1
            )
            val id = if (stored == null) alarms.addAlarm(alarm) else {
                cancelAlarm(stored)
                alarms.updateAlarm(alarm)
                stored.id
            }
            events.upsert(event.copy(alarmId = id))
            enqueueAlarm(alarm.copy(id = id))
        }
        val currentKeys = snapshot.mapTo(mutableSetOf()) { it.eventKey }
        val account = accounts.findById(accountId) ?: return addedEvents
        val prefix = accountEventPrefix(account)
        removeEvents(existing.values.filter { it.eventKey !in currentKeys }) { key ->
            key.startsWith(prefix) && key !in currentKeys
        }
        return addedEvents
    }

    private suspend fun removeEvents(stale: List<AgendaEvent>, ownsAlarm: (String) -> Boolean) {
        val keys = stale.mapTo(mutableSetOf()) { it.eventKey }
        val owned = alarms.getAlarms().filter { alarm ->
            alarm.agendaEventKey?.let { it in keys || ownsAlarm(it) } == true
        }
        owned.forEach(cancelAlarm)
        owned.forEach { alarms.deleteAlarm(it) }
        stale.forEach { events.delete(it.eventKey) }
    }
}

internal fun accountEventPrefix(account: OAuthAccount): String =
    "${if (account.provider == "MICROSOFT_GRAPH") "microsoft" else "google"}:${URLEncoder.encode(account.id, "UTF-8").replace("+", "%20")}:"
