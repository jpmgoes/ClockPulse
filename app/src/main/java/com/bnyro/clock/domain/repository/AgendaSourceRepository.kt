package com.bnyro.clock.domain.repository

import com.bnyro.clock.data.database.dao.AgendaSourceDao
import com.bnyro.clock.domain.model.AgendaEvent
import com.bnyro.clock.domain.model.AgendaSource
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.OAuthAccount
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AgendaSourceRepository(
    private val sourceDao: AgendaSourceDao,
    private val accounts: OAuthAccountsRepository,
    private val events: AgendaRepository,
    private val alarms: AlarmRepository
) {
    private val mutex = Mutex()

    suspend fun current(): AgendaSource? = sourceDao.current()?.source

    fun currentStream() = sourceDao.currentStream().map { it?.source }

    fun visibleEventsStream() = combine(currentStream(), events.getEventsStream()) { source, all ->
        when (source) {
            AgendaSource.LOCAL -> all.filter { it.connectionId == null }
            AgendaSource.OAUTH -> all.filter { it.connectionId != null }
            null -> emptyList()
        }
    }

    /** A source can only be changed after its explicit disconnect action. */
    suspend fun select(source: AgendaSource, cancelAlarm: ((Alarm) -> Unit)? = null) = mutex.withLock {
        val selected = current()
        if (selected == source) return@withLock
        check(selected == null) { "Disconnect the selected agenda source before choosing another" }
        check(source != AgendaSource.LOCAL || accounts.getAccounts().isEmpty()) {
            "Disconnect all OAuth accounts before choosing the local calendar"
        }

        // Older versions may have mirrored local events without a source-selection row.
        if (source == AgendaSource.OAUTH) removeEvents(
            belongsToSource = { it.connectionId == null },
            belongsToAlarmSource = { it.agendaEventKey?.startsWith("google:") == false },
            cancelAlarm = cancelAlarm
        ) else removeEvents(
            belongsToSource = { it.connectionId != null },
            belongsToAlarmSource = { it.agendaEventKey?.startsWith("google:") == true },
            cancelAlarm = cancelAlarm
        )
        sourceDao.select(source)
    }

    /** Holds the choice lock for the sync, so disconnect cannot race with a running import. */
    suspend fun <T> sync(local: suspend () -> T, oauth: suspend () -> T): T? = mutex.withLock {
        when (current()) {
            AgendaSource.LOCAL -> local()
            AgendaSource.OAUTH -> oauth()
            null -> null
        }
    }

    /** Re-reads the event under the source mutex before changing or scheduling its alarm. */
    suspend fun setEnabled(eventKey: String, enabled: Boolean, enqueueAlarm: (Alarm) -> Unit): Boolean = mutex.withLock {
        val event = events.findByKey(eventKey) ?: return@withLock false
        when (current()) {
            AgendaSource.LOCAL -> if (event.connectionId != null) return@withLock false
            AgendaSource.OAUTH -> if (event.connectionId == null) return@withLock false
            null -> return@withLock false
        }
        events.updateEnabled(eventKey, enabled)
        alarms.getAlarmById(event.alarmId)?.let { alarm ->
            alarm.enabled = enabled
            alarms.updateAlarm(alarm)
            enqueueAlarm(alarm)
        }
        true
    }

    suspend fun disconnectLocal(cancelAlarm: (Alarm) -> Unit) = mutex.withLock {
        check(current() != AgendaSource.OAUTH) { "OAuth is the selected agenda source" }
        removeEvents(
            belongsToSource = { it.connectionId == null },
            belongsToAlarmSource = { it.agendaEventKey?.startsWith("google:") == false },
            cancelAlarm = cancelAlarm
        )
        sourceDao.clear()
    }

    suspend fun disconnectAllOAuth(
        cancelAlarm: (Alarm) -> Unit,
        revokeAccount: suspend (OAuthAccount) -> Unit
    ) = mutex.withLock {
        check(current() != AgendaSource.LOCAL) { "Local is the selected agenda source" }
        accounts.getAccounts().forEach { revokeAccount(it) }
        removeEvents(
            belongsToSource = { it.connectionId != null },
            belongsToAlarmSource = { it.agendaEventKey?.startsWith("google:") == true },
            cancelAlarm = cancelAlarm
        )
        accounts.deleteAll()
        sourceDao.clear()
    }

    /** Cancel every scheduled alarm before deleting any matching event or account row. */
    private suspend fun removeEvents(
        belongsToSource: (AgendaEvent) -> Boolean,
        belongsToAlarmSource: (Alarm) -> Boolean,
        cancelAlarm: ((Alarm) -> Unit)?
    ) {
        val matching = events.getEvents().filter(belongsToSource)
        val matchingKeys = matching.mapTo(mutableSetOf()) { it.eventKey }
        val linkedAlarmIds = matching.mapTo(mutableSetOf()) { it.alarmId }
        val ownedAlarms = alarms.getAlarms().filter {
            belongsToAlarmSource(it) || it.agendaEventKey in matchingKeys || it.id in linkedAlarmIds
        }
        check(cancelAlarm != null || ownedAlarms.isEmpty()) {
            "Alarm cancellation is required when changing agenda source"
        }
        ownedAlarms.forEach { cancelAlarm?.invoke(it) }
        ownedAlarms.forEach { alarms.deleteAlarm(it) }
        matching.forEach { events.delete(it.eventKey) }
    }
}
