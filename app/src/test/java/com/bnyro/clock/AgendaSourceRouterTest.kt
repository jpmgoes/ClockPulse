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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
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
