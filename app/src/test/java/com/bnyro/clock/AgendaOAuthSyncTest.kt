package com.bnyro.clock

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.model.*
import com.bnyro.clock.domain.repository.*
import com.bnyro.clock.util.OAuthAgendaSync
import com.bnyro.clock.util.google.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgendaOAuthSyncTest {
    private lateinit var db: AppDatabase
    private lateinit var accounts: OAuthAccountsRepository
    private lateinit var events: AgendaRepository
    private lateinit var alarms: AlarmRepository
    private lateinit var sources: AgendaSourceRepository
    private val cancelled = mutableListOf<String?>()
    private val enqueued = mutableListOf<Alarm>()
    private val now = Instant.parse("2026-09-23T18:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("America/Fortaleza")
    private lateinit var oldZone: TimeZone

    @Before fun setUp() {
        oldZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        accounts = OAuthAccountsRepository(db.oauthAccountsDao())
        events = AgendaRepository(db.agendaEventsDao())
        alarms = AlarmRepository(db.alarmsDao())
        sources = AgendaSourceRepository(db.agendaSourceDao(), accounts, events, alarms)
    }

    @After fun tearDown() { db.close(); TimeZone.setDefault(oldZone) }

    private fun syncer(fetch: suspend (OAuthAccount, Long, Long) -> List<RemoteAgendaEvent>) = OAuthAgendaSync(
        accounts, events, alarms, fetch,
        cancelAlarm = { cancelled += it.agendaEventKey }, enqueueAlarm = { enqueued += it },
        untitledEvent = "Sem título", clock = { now }, zone = { zone }
    )

    private fun account(id: String) = OAuthAccount(id, "GOOGLE_CALENDAR", id, id, "$id@example.com", "CONNECTED", null)
    private fun remote(accountId: String, id: String = "event", minutes: Int = 60, begin: String = "2026-09-23T22:00:00Z") = RemoteAgendaEvent(
        "google:$accountId:calendar:$id", accountId, "calendar", id, "Terço", Instant.parse(begin).toEpochMilli(),
        Instant.parse(begin).toEpochMilli() + 3_600_000, minutes, false
    )
    private suspend fun route(syncer: OAuthAgendaSync) = sources.sync(local = { null }, oauth = { syncer.syncAccounts() })

    @Test fun onlyOAuthSelectionImportsAndTwoAccountsDoNotCollide() = runBlocking {
        sources.select(AgendaSource.LOCAL)
        accounts.upsert(account("one")); accounts.upsert(account("two"))
        val syncer = syncer { account, _, _ -> listOf(remote(account.id)) }
        assertNull(route(syncer))
        assertTrue(events.getEvents().isEmpty())
        sources.disconnectLocal {}
        sources.select(AgendaSource.OAUTH)
        assertEquals(2, route(syncer)!!.eventCount)
        assertEquals(setOf("one", "two"), events.getEvents().map { it.connectionId }.toSet())
        assertEquals(2, events.getEvents().map { it.alarmId }.distinct().size)
        assertEquals(2, alarms.getAlarms().size)
    }

    @Test fun midnightWindowRetainsPastEventsTodayAndUsesCalendarDays() = runBlocking {
        sources.select(AgendaSource.OAUTH)
        accounts.upsert(account("one"))
        val syncer = syncer { _, start, end ->
            assertEquals(Instant.parse("2026-09-23T03:00:00Z").toEpochMilli(), start)
            assertEquals(Instant.parse("2026-09-30T03:00:00Z").toEpochMilli(), end)
            listOf(remote("one", begin = "2026-09-23T16:00:00Z"))
        }
        route(syncer)
        assertEquals(Instant.parse("2026-09-23T16:00:00Z").toEpochMilli(), events.getEvents().single().beginAt)
        assertEquals(now, accounts.findById("one")!!.lastSyncedAt)
    }

    @Test fun earliestGoogleReminderIsConvertedToAnAlarmAtEighteenHours() = runBlocking {
        sources.select(AgendaSource.OAUTH)
        accounts.upsert(account("one"))
        val api = GoogleCalendarApi(GoogleHttpTransport { url, _ ->
            GoogleHttpResponse(200, if (url.contains("calendarList")) """{"items":[{"id":"calendar"}]}"""
            else """{"items":[{"id":"event","summary":"Terço","start":{"dateTime":"2026-09-23T19:00:00-03:00"},"end":{"dateTime":"2026-09-23T20:00:00-03:00"},"reminders":{"useDefault":false,"overrides":[{"method":"popup","minutes":15},{"method":"popup","minutes":60}]}}]}""")
        })
        route(syncer { account, start, end -> api.events(account.id, "ephemeral", start, end) })
        assertEquals(60, events.getEvents().single().reminderMinutes)
        assertEquals(18 * 3_600_000L, alarms.getAlarms().single().time)
        assertEquals(20719L, alarms.getAlarms().single().startDate)
        assertEquals(1, alarms.getAlarms().single().endOccurrences)
    }

    @Test fun refreshPreservesDisabledStateAndCleansOnlySuccessfulAccountNamespace() = runBlocking {
        sources.select(AgendaSource.OAUTH)
        accounts.upsert(account("one")); accounts.upsert(account("two"))
        route(syncer { account, _, _ -> listOf(remote(account.id)) })
        sources.setEnabled("google:two:calendar:event", false) {}
        val twoAlarmId = events.findByKey("google:two:calendar:event")!!.alarmId
        val localAlarm = alarms.addAlarm(Alarm(time = 0, agendaEventKey = "local:untouched"))
        events.upsert(AgendaEvent("local:untouched", 1, 1, "local", now, now, alarmId = localAlarm))
        val result = route(syncer { account, _, _ ->
            if (account.id == "one") throw IOException("offline")
            listOf(remote("two", minutes = 90))
        })!!
        assertEquals(setOf("one"), result.failedAccountIds)
        assertEquals(setOf("one", "two", null), events.getEvents().map { it.connectionId }.toSet())
        assertEquals(twoAlarmId, events.findByKey("google:two:calendar:event")!!.alarmId)
        assertFalse(alarms.getAlarmById(twoAlarmId)!!.enabled)
        assertEquals(17 * 3_600_000L + 30 * 60_000L, alarms.getAlarmById(twoAlarmId)!!.time)
        route(syncer { account, _, _ -> if (account.id == "one") emptyList() else listOf(remote("two")) })
        assertNull(events.findByKey("google:one:calendar:event"))
        assertNotNull(events.findByKey("local:untouched"))
    }

    @Test fun individualDisconnectCancelsOrphansAndLeavesOtherAccountUntilLastDisconnect() = runBlocking {
        sources.select(AgendaSource.OAUTH)
        accounts.upsert(account("one")); accounts.upsert(account("two"))
        route(syncer { account, _, _ -> listOf(remote(account.id)) })
        alarms.addAlarm(Alarm(time = 0, agendaEventKey = "google:one:orphan"))
        alarms.addAlarm(Alarm(time = 0, agendaEventKey = "local:other"))
        cancelled.clear()
        sources.disconnectOAuth("one", { cancelled += it.agendaEventKey }, {})
        assertEquals(setOf("google:one:calendar:event", "google:one:orphan"), cancelled.toSet())
        assertEquals(listOf("two"), accounts.getAccounts().map { it.id })
        assertEquals(listOf("two"), events.getEvents().map { it.connectionId })
        assertEquals(AgendaSource.OAUTH, sources.current())
        sources.disconnectOAuth("two", { cancelled += it.agendaEventKey }, {})
        assertNull(sources.current())
        assertEquals(listOf("local:other"), alarms.getAlarms().map { it.agendaEventKey })
    }

    @Test fun removedAccountAndItsOrphanAlarmsArePrunedWithoutDroppingReconnectAccount() = runBlocking {
        sources.select(AgendaSource.OAUTH)
        accounts.upsert(account("one")); accounts.upsert(account("two"))
        val syncer = syncer { account, _, _ -> listOf(remote(account.id)) }
        route(syncer)
        accounts.delete("one")
        accounts.updateState("two", "RECONNECT_REQUIRED")
        alarms.addAlarm(Alarm(time = 0, agendaEventKey = "google:one:orphan"))
        route(syncer)
        assertEquals(listOf("two"), events.getEvents().map { it.connectionId })
        assertEquals(listOf("google:two:calendar:event"), alarms.getAlarms().map { it.agendaEventKey })
        assertEquals("RECONNECT_REQUIRED", accounts.findById("two")!!.state)
    }
}
