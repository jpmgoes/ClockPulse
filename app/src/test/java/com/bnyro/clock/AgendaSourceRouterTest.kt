package com.bnyro.clock

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.model.AgendaEvent
import com.bnyro.clock.domain.model.AgendaSource
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.OAuthAccount
import com.bnyro.clock.domain.repository.AgendaRepository
import com.bnyro.clock.domain.repository.AgendaSourceRepository
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.domain.repository.OAuthAccountsRepository
import com.bnyro.clock.util.LocalAgendaMirror
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgendaSourceRouterTest {
    private lateinit var database: AppDatabase
    private lateinit var sources: AgendaSourceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sources = AgendaSourceRepository(
            database.agendaSourceDao(),
            OAuthAccountsRepository(database.oauthAccountsDao()),
            AgendaRepository(database.agendaEventsDao()),
            AlarmRepository(database.alarmsDao())
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun onlySelectedSourceRunsAndChoiceSurvivesRepositoryRecreation() = runBlocking {
        val calls = mutableListOf<String>()
        assertNull(sources.current())
        assertNull(sources.sync(
            local = { calls += "local"; "local result" },
            oauth = { calls += "oauth"; "oauth result" }
        ))
        assertTrue(calls.isEmpty())

        sources.select(AgendaSource.LOCAL)
        assertEquals("local result", sources.sync(
            local = { calls += "local"; "local result" },
            oauth = { calls += "oauth"; "oauth result" }
        ))
        assertEquals(listOf("local"), calls)

        sources.disconnectLocal(cancelAlarm = {})
        sources.select(AgendaSource.OAUTH)
        val recreated = AgendaSourceRepository(
            database.agendaSourceDao(),
            OAuthAccountsRepository(database.oauthAccountsDao()),
            AgendaRepository(database.agendaEventsDao()),
            AlarmRepository(database.alarmsDao())
        )
        assertEquals(AgendaSource.OAUTH, recreated.current())
        assertEquals("oauth result", recreated.sync(
            local = { calls += "local"; "local result" },
            oauth = { calls += "oauth"; "oauth result" }
        ))
        assertEquals(listOf("local", "oauth"), calls)
    }

    @Test
    fun changingSourceIsRejectedWhileOAuthAccountsExist() = runBlocking {
        sources.select(AgendaSource.OAUTH)
        database.oauthAccountsDao().upsert(account("account-one"))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { sources.select(AgendaSource.LOCAL) }
        }
        assertEquals(AgendaSource.OAUTH, sources.current())
        assertEquals(listOf("account-one"), database.oauthAccountsDao().getAll().map { it.id })
    }

    @Test
    fun disconnectAllOAuthCancelsEveryOwnedAlarmBeforeRemovingEventsAndAccounts() = runBlocking {
        sources.select(AgendaSource.OAUTH)
        database.oauthAccountsDao().upsert(account("one"))
        database.oauthAccountsDao().upsert(account("two"))
        insertEvent("google:one:event", "one")
        insertEvent("google:two:event", "two")
        // A damaged row can lose its alarmId even though the alarm still owns the event key.
        database.agendaEventsDao().findByKey("google:two:event")!!.let {
            database.agendaEventsDao().upsert(it.copy(alarmId = 0))
        }
        database.alarmsDao().insert(Alarm(time = 0L, agendaEventKey = "google:orphan:event"))
        database.alarmsDao().insert(Alarm(time = 0L, agendaEventKey = null))
        val cancelled = mutableListOf<String>()
        val revoked = mutableListOf<String>()

        sources.disconnectAllOAuth(
            cancelAlarm = { alarm ->
                val key = requireNotNull(alarm.agendaEventKey)
                // A scheduled alarm must still have its event when cancellation happens.
                assertEquals(2, database.openHelper.writableDatabase.query(
                    "SELECT COUNT(*) FROM agenda_events WHERE connectionId IS NOT NULL"
                ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
                cancelled += key
            },
            revokeAccount = { revoked += it.id }
        )

        assertEquals(setOf("google:one:event", "google:two:event", "google:orphan:event"), cancelled.toSet())
        assertEquals(setOf("one", "two"), revoked.toSet())
        assertTrue(database.agendaEventsDao().getAll().isEmpty())
        assertEquals(listOf(null), database.alarmsDao().getAll().map { it.agendaEventKey })
        assertTrue(database.oauthAccountsDao().getAll().isEmpty())
        assertNull(sources.current())
        sources.select(AgendaSource.LOCAL)
        assertEquals(AgendaSource.LOCAL, sources.current())
    }

    @Test
    fun disconnectLocalClearsOnlyLocalEventsAndAlarms() = runBlocking {
        sources.select(AgendaSource.LOCAL)
        insertEvent("local:calendar:event", null)
        insertEvent("google:one:event", "one")
        database.alarmsDao().insert(Alarm(time = 0L, agendaEventKey = "local:orphan:event"))
        database.alarmsDao().insert(Alarm(time = 0L, agendaEventKey = null))
        val cancelled = mutableListOf<String>()

        sources.disconnectLocal(cancelAlarm = { cancelled += requireNotNull(it.agendaEventKey) })

        assertEquals(setOf("local:calendar:event", "local:orphan:event"), cancelled.toSet())
        assertEquals(listOf("google:one:event"), database.agendaEventsDao().getAll().map { it.eventKey })
        assertEquals(setOf("google:one:event", null), database.alarmsDao().getAll().map { it.agendaEventKey }.toSet())
        assertNull(sources.current())
    }

    @Test
    fun selectingOAuthRemovesMigratedLocalEventsBeforeActivatingSource() = runBlocking {
        insertEvent("42", null)
        val cancelled = mutableListOf<String>()

        sources.select(AgendaSource.OAUTH, cancelAlarm = { cancelled += requireNotNull(it.agendaEventKey) })

        assertEquals(listOf("42"), cancelled)
        assertTrue(database.agendaEventsDao().getAll().isEmpty())
        assertTrue(database.alarmsDao().getAll().isEmpty())
        assertEquals(AgendaSource.OAUTH, sources.current())
    }

    @Test
    fun visibleEventsNeverMixSourcesAndStayHiddenBeforeSelection() = runBlocking {
        insertEvent("local:one", null)
        assertTrue(sources.visibleEventsStream().first().isEmpty())

        sources.select(AgendaSource.LOCAL)
        insertEvent("google:one", "one")
        assertEquals(listOf("local:one"), sources.visibleEventsStream().first().map { it.eventKey })

        sources.disconnectLocal(cancelAlarm = {})
        assertTrue(sources.visibleEventsStream().first().isEmpty())
        sources.select(AgendaSource.OAUTH)
        assertEquals(listOf("google:one"), sources.visibleEventsStream().first().map { it.eventKey })
    }

    @Test
    fun localMirrorMigratesDisabledLegacyEventWithoutRecreatingOrRemovingItsAlarm() = runBlocking {
        sources.select(AgendaSource.LOCAL)
        val legacyAlarmId = database.alarmsDao().insert(
            Alarm(time = 0L, agendaEventKey = "42", enabled = false)
        )
        database.agendaEventsDao().upsert(
            AgendaEvent("42", 42, 7, "Consulta", 1_000, 2_000, 15, legacyAlarmId, false)
        )
        val cancelled = mutableListOf<Long>()
        val enqueued = mutableListOf<Alarm>()
        val mirror = LocalAgendaMirror(
            AgendaRepository(database.agendaEventsDao()),
            AlarmRepository(database.alarmsDao()),
            cancelAlarm = { cancelled += it.id },
            enqueueAlarm = { enqueued += it.copy() }
        )

        mirror.update(listOf(
            AgendaEvent("local:7:42", 42, 7, "Consulta", 1_000, 2_000, 15, 0)
        ))

        val stored = database.agendaEventsDao().getAll().single()
        assertEquals("local:7:42", stored.eventKey)
        assertFalse(stored.enabled)
        assertEquals(legacyAlarmId, stored.alarmId)
        assertEquals(listOf(legacyAlarmId), database.alarmsDao().getAll().map { it.id })
        assertEquals("local:7:42", database.alarmsDao().getAll().single().agendaEventKey)
        assertFalse(database.alarmsDao().getAll().single().enabled)
        assertEquals(listOf(legacyAlarmId), cancelled)
        assertEquals(listOf(false), enqueued.map { it.enabled })
    }

    @Test
    fun queuedToggleCannotRescheduleAnAlarmAfterLocalDisconnect() = runBlocking {
        sources.select(AgendaSource.LOCAL)
        insertEvent("local:calendar:event", null)
        val scheduled = mutableListOf<Long>()
        assertTrue(sources.setEnabled("local:calendar:event", false) { scheduled += it.id })
        assertFalse(database.agendaEventsDao().findByKey("local:calendar:event")!!.enabled)
        assertFalse(database.alarmsDao().getAll().single().enabled)
        assertEquals(1, scheduled.size)
        scheduled.clear()
        val syncEntered = CompletableDeferred<Unit>()
        val finishSync = CompletableDeferred<Unit>()
        val synchronizing = async(start = CoroutineStart.UNDISPATCHED) {
            sources.sync(local = {
                syncEntered.complete(Unit)
                finishSync.await()
            }, oauth = {})
        }
        syncEntered.await()
        val disconnected = async(start = CoroutineStart.UNDISPATCHED) {
            sources.disconnectLocal(cancelAlarm = {})
        }
        val toggled = async(start = CoroutineStart.UNDISPATCHED) {
            sources.setEnabled("local:calendar:event", true) { scheduled += it.id }
        }
        finishSync.complete(Unit)
        synchronizing.await()
        disconnected.await()

        assertFalse(toggled.await())
        assertTrue(scheduled.isEmpty())
        assertNull(database.agendaEventsDao().findByKey("local:calendar:event"))
        assertTrue(database.alarmsDao().getAll().isEmpty())
        assertNull(sources.current())
    }

    private suspend fun insertEvent(key: String, connectionId: String?) {
        val alarmId = database.alarmsDao().insert(
            Alarm(time = 0L, agendaEventKey = key, enabled = true)
        )
        database.agendaEventsDao().upsert(
            AgendaEvent(
                eventKey = key,
                calendarEventId = 1,
                calendarId = 1,
                title = key,
                beginAt = 1_000,
                endAt = 2_000,
                alarmId = alarmId,
                connectionId = connectionId
            )
        )
    }

    private fun account(id: String) = OAuthAccount(
        id = id,
        provider = "GOOGLE_CALENDAR",
        profileId = "profile-$id",
        displayName = id,
        email = "$id@example.com",
        state = "CONNECTED",
        lastSyncedAt = null
    )
}
