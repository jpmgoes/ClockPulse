package com.bnyro.clock.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.bnyro.clock.App
import com.bnyro.clock.domain.model.AgendaEvent
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.RepeatUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Mirrors the next seven days from Google calendars exposed by Android's Calendar Provider. */
class AgendaSyncer(private val context: Context) {
    private val appContext = context.applicationContext
    private val container get() = (appContext as App).container

    suspend fun sync(): Result = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Result.PermissionRequired
        }

        val now = System.currentTimeMillis()
        val windowEnd = now + TimeUnit.DAYS.toMillis(7)
        val googleCalendarIds = queryGoogleCalendarIds()
        val sourceEvents = queryEvents(now, windowEnd, googleCalendarIds)
        val localEvents = container.agendaRepository.getEvents().associateBy { it.eventKey }

        sourceEvents.forEach { source ->
            val existing = localEvents[source.eventKey]
            val enabled = existing?.enabled ?: true
            val alarm = buildAlarm(source, existing?.alarmId ?: 0L, enabled)
            val alarmId = if (existing == null) {
                container.alarmRepository.addAlarm(alarm)
            } else {
                val storedAlarm = container.alarmRepository.getAlarmById(existing.alarmId)
                if (storedAlarm == null) {
                    container.alarmRepository.addAlarm(alarm)
                } else {
                    AlarmHelper.cancel(appContext, storedAlarm)
                    container.alarmRepository.updateAlarm(alarm.copy(id = storedAlarm.id))
                    storedAlarm.id
                }
            }
            AlarmHelper.enqueue(appContext, alarm.copy(id = alarmId))
            container.agendaRepository.upsert(source.copy(alarmId = alarmId, enabled = enabled))
        }

        val currentKeys = sourceEvents.mapTo(mutableSetOf()) { it.eventKey }
        localEvents.values.filter { it.eventKey !in currentKeys }.forEach { stale ->
            container.alarmRepository.getAlarmById(stale.alarmId)?.let { alarm ->
                AlarmHelper.cancel(appContext, alarm)
                container.alarmRepository.deleteAlarm(alarm)
            }
            container.agendaRepository.delete(stale.eventKey)
        }

        Result.Success(sourceEvents.size)
    }

    suspend fun setEnabled(event: AgendaEvent, enabled: Boolean) = withContext(Dispatchers.IO) {
        container.agendaRepository.updateEnabled(event.eventKey, enabled)
        container.alarmRepository.getAlarmById(event.alarmId)?.let { alarm ->
            alarm.enabled = enabled
            container.alarmRepository.updateAlarm(alarm)
            AlarmHelper.enqueue(appContext, alarm)
        }
    }

    /** Removes every locally mirrored event and cancels its alarm before calendar access is revoked. */
    suspend fun clearSyncedEvents() = withContext(Dispatchers.IO) {
        container.agendaRepository.getEvents().forEach { event ->
            container.alarmRepository.getAlarmById(event.alarmId)?.let { alarm ->
                AlarmHelper.cancel(appContext, alarm)
                container.alarmRepository.deleteAlarm(alarm)
            }
            container.agendaRepository.delete(event.eventKey)
        }
    }

    private fun queryGoogleCalendarIds(): Set<Long> {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?"
        return appContext.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            arrayOf("com.google"),
            null
        )?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }.orEmpty()
    }

    private fun queryEvents(windowStart: Long, windowEnd: Long, googleCalendarIds: Set<Long>): List<AgendaEvent> {
        if (googleCalendarIds.isEmpty()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(windowStart.toString())
            .appendPath(windowEnd.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.ORIGINAL_INSTANCE_TIME
        )
        val placeholders = googleCalendarIds.joinToString(",") { "?" }
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN ($placeholders) " +
            "AND ${CalendarContract.Instances.ALL_DAY} = 0"
        return appContext.contentResolver.query(
            uri,
            projection,
            selection,
            googleCalendarIds.map(Long::toString).toTypedArray(),
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(0)
                    val calendarId = cursor.getLong(1)
                    val title = cursor.getString(2)?.takeIf(String::isNotBlank)
                        ?: appContext.getString(com.bnyro.clock.R.string.untitled_event)
                    val beginAt = cursor.getLong(3)
                    val endAt = cursor.getLong(4)
                    val originalInstanceTime = cursor.getLong(6)
                    // A regular event keeps its event id when its time changes. Recurring
                    // events need their original instance time as an extra discriminator.
                    val instanceKey = originalInstanceTime.takeIf { it != 0L }?.let { ":$it" }.orEmpty()
                    add(
                        AgendaEvent(
                            eventKey = "$eventId$instanceKey",
                            calendarEventId = eventId,
                            calendarId = calendarId,
                            title = title,
                            beginAt = beginAt,
                            endAt = endAt,
                            alarmId = 0L
                        )
                    )
                }
            }
        }.orEmpty()
    }

    private fun buildAlarm(event: AgendaEvent, id: Long, enabled: Boolean): Alarm {
        val reminderMillis = TimeUnit.MINUTES.toMillis(
            Preferences.instance.getInt(Preferences.agendaReminderMinutesKey, 60).toLong()
        )
        val reminderAt = event.beginAt - reminderMillis
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

    sealed interface Result {
        data class Success(val eventCount: Int) : Result
        data object PermissionRequired : Result
    }
}
