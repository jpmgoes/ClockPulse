package com.bnyro.clock.util

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.bnyro.clock.App
import com.bnyro.clock.domain.model.AgendaEvent
import com.bnyro.clock.domain.model.AgendaSource
import com.bnyro.clock.domain.model.OAuthAccount
import com.bnyro.clock.util.google.AuthorizedGoogleCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Routes Agenda synchronization to exactly the selected source. */
class AgendaSyncer(
    private val context: Context,
    private val oauthSync: (suspend () -> Result)? = null
) {
    private val appContext = context.applicationContext
    private val container get() = (appContext as App).container

    suspend fun sync(requestProviderSync: Boolean = false): Result = withContext(Dispatchers.IO) {
        container.agendaSourceRepository.sync(
            local = { syncLocal(requestProviderSync) },
            oauth = { oauthSync?.invoke() ?: syncOAuth() }
        ) ?: Result.SourceSelectionRequired
    }

    private suspend fun syncOAuth(): Result {
        val app = appContext as App
        val calendar = AuthorizedGoogleCalendar(app.googleCalendarAuthorizer, app.googleCalendarApi) {
            container.oauthAccountsRepository.updateState(it, "RECONNECT_REQUIRED")
        }
        val result = OAuthAgendaSync(
            container.oauthAccountsRepository, container.agendaRepository, container.alarmRepository,
            fetchEvents = calendar::events,
            cancelAlarm = { AlarmHelper.cancel(appContext, it) },
            enqueueAlarm = { AlarmHelper.enqueue(appContext, it) },
            untitledEvent = appContext.getString(com.bnyro.clock.R.string.untitled_event)
        ).syncAccounts()
        return Result.Success(result.eventCount, result.failedAccountIds)
    }

    suspend fun selectSource(source: AgendaSource) = withContext(Dispatchers.IO) {
        container.agendaSourceRepository.select(source) { AlarmHelper.cancel(appContext, it) }
    }

    /** Mirrors Android Calendar Provider events only while LOCAL is selected. */
    private suspend fun syncLocal(requestProviderSync: Boolean): Result {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.PermissionRequired
        }

        if (requestProviderSync) requestGoogleCalendarSync()
        val now = System.currentTimeMillis()
        val windowStart = AgendaSyncWindow.startOfCurrentDay(now, ZoneId.systemDefault())
        val windowEnd = now + TimeUnit.DAYS.toMillis(7)
        val googleCalendarIds = queryGoogleCalendarIds()
        val sourceEvents = queryEvents(windowStart, windowEnd, googleCalendarIds)
        LocalAgendaMirror(
            container.agendaRepository,
            container.alarmRepository,
            cancelAlarm = { AlarmHelper.cancel(appContext, it) },
            enqueueAlarm = { AlarmHelper.enqueue(appContext, it) }
        ).update(sourceEvents)

        return Result.Success(sourceEvents.size)
    }

    suspend fun setEnabled(event: AgendaEvent, enabled: Boolean) = withContext(Dispatchers.IO) {
        container.agendaSourceRepository.setEnabled(event.eventKey, enabled) { alarm ->
            AlarmHelper.enqueue(appContext, alarm)
        }
    }

    /** Removes only locally mirrored events and cancels their alarms. */
    suspend fun clearSyncedEvents() = withContext(Dispatchers.IO) {
        container.agendaSourceRepository.disconnectLocal { AlarmHelper.cancel(appContext, it) }
    }

    /** Revocation and cleanup share the same lock as local and OAuth imports. */
    suspend fun disconnectAllOAuth(revokeAccount: suspend (OAuthAccount) -> Unit) = withContext(Dispatchers.IO) {
        container.agendaSourceRepository.disconnectAllOAuth(
            cancelAlarm = { AlarmHelper.cancel(appContext, it) },
            revokeAccount = revokeAccount
        )
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

    /** Asks Android's Google Calendar sync adapter to fetch recent remote changes. */
    private fun requestGoogleCalendarSync() {
        val projection = arrayOf(
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE
        )
        appContext.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf("com.google"),
            null
        )?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    val accountName = cursor.getString(0) ?: continue
                    add(Account(accountName, cursor.getString(1)))
                }
            }.forEach { account ->
                // This is asynchronous. Calendar provider updates are observed by AgendaScreen.
                runCatching {
                    ContentResolver.requestSync(
                        account,
                        CalendarContract.AUTHORITY,
                        Bundle().apply {
                            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                        }
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Unable to request Google Calendar sync", error)
                }
            }
        }
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
        val events = appContext.contentResolver.query(
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
                            eventKey = "local:$calendarId:$eventId$instanceKey",
                            calendarEventId = eventId,
                            calendarId = calendarId,
                            title = title,
                            beginAt = beginAt,
                            endAt = endAt,
                            reminderMinutes = 0,
                            alarmId = 0L
                        )
                    )
                }
            }
        }.orEmpty()
        val reminderMinutesByEventId = queryReminderMinutes(
            events.mapTo(mutableSetOf()) { it.calendarEventId }
        )
        return events.map { event ->
            event.copy(reminderMinutes = reminderMinutesByEventId[event.calendarEventId] ?: 0)
        }
    }

    /**
     * Returns the earliest configured Calendar reminder for every event.
     * Calendar stores this value as the number of minutes before the event.
     */
    private fun queryReminderMinutes(eventIds: Set<Long>): Map<Long, Int> {
        if (eventIds.isEmpty()) return emptyMap()
        val placeholders = eventIds.joinToString(",") { "?" }
        val projection = arrayOf(
            CalendarContract.Reminders.EVENT_ID,
            CalendarContract.Reminders.MINUTES
        )
        return appContext.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            projection,
            "${CalendarContract.Reminders.EVENT_ID} IN ($placeholders) " +
                "AND ${CalendarContract.Reminders.METHOD} = ?",
            (eventIds.map(Long::toString) + CalendarContract.Reminders.METHOD_ALERT.toString())
                .toTypedArray(),
            null
        )?.use { cursor ->
            buildMap<Long, Int> {
                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(0)
                    val minutes = cursor.getInt(1)
                    val current = get(eventId)
                    // If Calendar has more than one reminder, mirror the earliest one.
                    if (current == null || minutes > current) put(eventId, minutes)
                }
            }
        }.orEmpty()
    }

    sealed interface Result {
        data class Success(val eventCount: Int, val failedAccountIds: Set<String> = emptySet()) : Result
        data object PermissionRequired : Result
        data object SourceSelectionRequired : Result
        data object OAuthUnavailable : Result
    }

    private companion object {
        const val TAG = "AgendaSyncer"
    }
}
