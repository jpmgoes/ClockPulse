package com.bnyro.clock.util

import com.bnyro.clock.domain.model.AgendaEvent
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.RepeatUnit
import com.bnyro.clock.domain.repository.AgendaRepository
import com.bnyro.clock.domain.repository.AlarmRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Applies a Calendar Provider snapshot to locally mirrored events and alarms. */
class LocalAgendaMirror(
    private val events: AgendaRepository,
    private val alarms: AlarmRepository,
    private val cancelAlarm: (Alarm) -> Unit,
    private val enqueueAlarm: (Alarm) -> Unit
) {
    /** Returns source events that were not present before this snapshot. */
    suspend fun update(sourceEvents: List<AgendaEvent>): List<AgendaEvent> {
        val localEvents = events.getEvents()
            .filter { it.connectionId == null }
            .associateBy { it.eventKey }
        val migratedLegacyKeys = mutableSetOf<String>()
        val addedEvents = mutableListOf<AgendaEvent>()

        sourceEvents.forEach { source ->
            val legacyKey = source.eventKey.removePrefix("local:${source.calendarId}:")
            val existing = localEvents[source.eventKey]
                ?: localEvents[legacyKey]?.takeUnless { it.eventKey in migratedLegacyKeys }
            if (existing == null) addedEvents += source
            val enabled = existing?.enabled ?: true
            val alarm = buildAlarm(source, existing?.alarmId ?: 0L, enabled)
            val storedAlarm = existing?.let { alarms.getAlarmById(it.alarmId) }
            val alarmId = if (storedAlarm == null) {
                alarms.addAlarm(alarm)
            } else {
                cancelAlarm(storedAlarm)
                alarms.updateAlarm(alarm.copy(id = storedAlarm.id))
                storedAlarm.id
            }
            enqueueAlarm(alarm.copy(id = alarmId))
            events.upsert(source.copy(alarmId = alarmId, enabled = enabled))
            if (existing != null && existing.eventKey != source.eventKey) {
                migratedLegacyKeys += existing.eventKey
                // The alarm now belongs to the new key; only the obsolete event row is deleted.
                events.delete(existing.eventKey)
            }
        }

        val currentKeys = sourceEvents.mapTo(mutableSetOf()) { it.eventKey }
        val alarmReferences = events.getEvents()
            .groupingBy { it.alarmId }
            .eachCount()
            .toMutableMap()
        localEvents.values.filter {
            it.eventKey !in currentKeys && it.eventKey !in migratedLegacyKeys
        }.forEach { stale ->
            if (alarmReferences.getOrDefault(stale.alarmId, 0) <= 1) {
                alarms.getAlarmById(stale.alarmId)?.let { alarm ->
                    cancelAlarm(alarm)
                    alarms.deleteAlarm(alarm)
                }
            }
            events.delete(stale.eventKey)
            alarmReferences[stale.alarmId] = alarmReferences.getOrDefault(stale.alarmId, 0) - 1
        }
        return addedEvents
    }

    private fun buildAlarm(event: AgendaEvent, id: Long, enabled: Boolean): Alarm {
        val reminderAt = event.beginAt - TimeUnit.MINUTES.toMillis(event.reminderMinutes.toLong())
        val dateTime = Instant.ofEpochMilli(reminderAt).atZone(ZoneId.systemDefault())
        return Alarm(
            id = id,
            time = (dateTime.hour * 60L + dateTime.minute) * 60_000L,
            label = event.title,
            enabled = enabled,
            agendaEventKey = event.eventKey,
            startDate = LocalDate.from(dateTime).toEpochDay(),
            repeatUnit = RepeatUnit.DAY,
            endOccurrences = 1
        )
    }
}
