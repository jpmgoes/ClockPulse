package com.bnyro.clock.data.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.bnyro.clock.domain.model.AgendaSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppDatabaseMigrationTest {
    @Test
    fun migrate16To17PreservesLocalEventAndStoresIndependentOAuthAccounts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-16-17"
        context.deleteDatabase(databaseName)

        val v16Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(16) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE IF NOT EXISTS `timeZones` (`key` TEXT NOT NULL, `zoneId` TEXT NOT NULL, `zoneName` TEXT NOT NULL, `countryName` TEXT NOT NULL, PRIMARY KEY(`key`))")
                            db.execSQL("CREATE TABLE IF NOT EXISTS `alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, `label` TEXT, `enabled` INTEGER NOT NULL, `days` TEXT NOT NULL, `vibrate` INTEGER NOT NULL, `soundName` TEXT, `soundUri` TEXT, `snoozeEnabled` INTEGER NOT NULL DEFAULT 1, `snoozeMinutes` INTEGER NOT NULL DEFAULT 10, `soundEnabled` INTEGER NOT NULL DEFAULT 1, `vibrationPattern` TEXT NOT NULL DEFAULT '0,1000,1000,1000,1000', `vibrationPatternName` TEXT NOT NULL DEFAULT 'Default', `dismissedAt` INTEGER DEFAULT NULL, `startDate` INTEGER NOT NULL DEFAULT 0, `repeatInterval` INTEGER NOT NULL DEFAULT 1, `repeatUnit` TEXT NOT NULL DEFAULT 'WEEK', `repeatAnchor` TEXT NOT NULL DEFAULT 'DAY_OF_MONTH', `repeatDuration` INTEGER DEFAULT NULL, `repeatDurationUnit` TEXT NOT NULL DEFAULT 'DAY', `endDate` INTEGER DEFAULT NULL, `endOccurrences` INTEGER DEFAULT NULL, `advanced` INTEGER NOT NULL DEFAULT 0, `agendaEventKey` TEXT DEFAULT NULL)")
                            db.execSQL("CREATE TABLE IF NOT EXISTS `agenda_events` (`eventKey` TEXT NOT NULL, `calendarEventId` INTEGER NOT NULL, `calendarId` INTEGER NOT NULL, `title` TEXT NOT NULL, `beginAt` INTEGER NOT NULL, `endAt` INTEGER NOT NULL, `reminderMinutes` INTEGER NOT NULL, `alarmId` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`eventKey`))")
                            db.execSQL("INSERT INTO agenda_events (eventKey, calendarEventId, calendarId, title, beginAt, endAt, reminderMinutes, alarmId, enabled) VALUES ('local:7:42:1000', 42, 7, 'Consulta', 1000, 2000, 15, 9, 1)")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int
                        ) = Unit
                    }
                )
                .build()
        )
        v16Helper.writableDatabase
        v16Helper.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17
            )
            .build()

        db.openHelper.writableDatabase.query(
            "SELECT eventKey, title, reminderMinutes, connectionId FROM agenda_events WHERE eventKey = 'local:7:42:1000'"
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            assertEquals("local:7:42:1000", cursor.getString(0))
            assertEquals("Consulta", cursor.getString(1))
            assertEquals(15, cursor.getInt(2))
            org.junit.Assert.assertTrue(cursor.isNull(3))
        }
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO oauth_accounts (id, provider, profileId, displayName, email, state, lastSyncedAt, providerDisplayName) VALUES ('one', 'GOOGLE_CALENDAR', 'profile-1', 'Ana', 'ana@example.com', 'CONNECTED', NULL, 'Google Calendar')"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO oauth_accounts (id, provider, profileId, displayName, email, state, lastSyncedAt, providerDisplayName) VALUES ('two', 'GOOGLE_CALENDAR', 'profile-2', 'Beto', 'beto@example.com', 'CONNECTED', 1234, 'Google Calendar')"
        )
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM oauth_accounts").use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        assertEquals(
            listOf("one" to "ana@example.com", "two" to "beto@example.com"),
            runBlocking { db.oauthAccountsDao().getAll() }
                .sortedBy { it.id }
                .map { it.id to it.email }
        )
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM agenda_source").use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        assertEquals(null, runBlocking { db.agendaSourceDao().current() })
        assertEquals(null, runBlocking { db.agendaEventsDao().findByKey("local:7:42:1000")?.connectionId })
        runBlocking {
            db.agendaSourceDao().select(AgendaSource.OAUTH)
            db.agendaSourceDao().select(AgendaSource.LOCAL)
        }
        assertEquals(AgendaSource.LOCAL, runBlocking { db.agendaSourceDao().current()?.source })
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM agenda_source").use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrate12To13MovesTheOldDefaultVibrationOntoTheNewOne() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val v12Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(12) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS `timeZones` (`key` TEXT NOT NULL, `zoneId` TEXT NOT NULL, `zoneName` TEXT NOT NULL, `countryName` TEXT NOT NULL, PRIMARY KEY(`key`))"
                            )
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS `alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, `label` TEXT, `enabled` INTEGER NOT NULL, `days` TEXT NOT NULL, `vibrate` INTEGER NOT NULL, `soundName` TEXT, `soundUri` TEXT, `snoozeEnabled` INTEGER NOT NULL DEFAULT 1, `snoozeMinutes` INTEGER NOT NULL DEFAULT 10, `soundEnabled` INTEGER NOT NULL DEFAULT 1, `vibrationPattern` TEXT NOT NULL DEFAULT '1000,1000,1000,1000,1000', `vibrationPatternName` TEXT NOT NULL DEFAULT 'Default', `dismissedAt` INTEGER DEFAULT NULL, `startDate` INTEGER NOT NULL DEFAULT 0, `repeatInterval` INTEGER NOT NULL DEFAULT 1, `repeatUnit` TEXT NOT NULL DEFAULT 'WEEK', `repeatAnchor` TEXT NOT NULL DEFAULT 'DAY_OF_MONTH', `repeatDuration` INTEGER DEFAULT NULL, `repeatDurationUnit` TEXT NOT NULL DEFAULT 'DAY', `endDate` INTEGER DEFAULT NULL, `endOccurrences` INTEGER DEFAULT NULL, `advanced` INTEGER NOT NULL DEFAULT 0)"
                            )
                            db.execSQL(
                                "INSERT INTO alarms (id, time, enabled, days, vibrate, vibrationPattern, vibrationPatternName) VALUES " +
                                    "(1, 0, 1, '0', 1, '1000,1000,1000,1000,1000', 'Default'), " +
                                    "(2, 0, 1, '0', 1, '1000,1000,1000,1000,1000', 'Heartbeat'), " +
                                    "(3, 0, 1, '0', 1, '500,500,500', 'Custom')"
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int
                        ) = Unit
                    }
                )
                .build()
        )
        v12Helper.writableDatabase
        v12Helper.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17
            )
            .build()
        val alarms = runBlocking { db.alarmsDao().getAll() }
        db.close()

        assertEquals(
            listOf(
                listOf(0, 1000, 1000, 1000, 1000),
                listOf(1000, 1000, 1000, 1000, 1000),
                listOf(500, 500, 500)
            ),
            alarms.sortedBy { it.id }.map { it.vibrationPattern }
        )
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
