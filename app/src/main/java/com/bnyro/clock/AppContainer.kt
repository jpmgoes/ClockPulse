package com.bnyro.clock

import com.bnyro.clock.data.database.AppDatabase
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.domain.repository.AgendaRepository
import com.bnyro.clock.domain.repository.AgendaSourceRepository
import com.bnyro.clock.domain.repository.OAuthAccountsRepository
import com.bnyro.clock.domain.repository.TimezoneRepository

class AppContainer(database: AppDatabase) {
    val alarmRepository: AlarmRepository by lazy {
        AlarmRepository(database.alarmsDao())
    }
    val timezoneRepository: TimezoneRepository by lazy {
        TimezoneRepository(database.timeZonesDao())
    }
    val agendaRepository: AgendaRepository by lazy {
        AgendaRepository(database.agendaEventsDao())
    }
    val oauthAccountsRepository: OAuthAccountsRepository by lazy {
        OAuthAccountsRepository(database.oauthAccountsDao())
    }
    val agendaSourceRepository: AgendaSourceRepository by lazy {
        AgendaSourceRepository(
            database.agendaSourceDao(),
            oauthAccountsRepository,
            agendaRepository,
            alarmRepository
        )
    }
}
