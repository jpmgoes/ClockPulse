package com.bnyro.clock.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AgendaSource {
    LOCAL,
    OAUTH
}

/** No row exists until the person explicitly chooses an Agenda source. */
@Entity(tableName = "agenda_source")
data class AgendaSourceSelection(
    @PrimaryKey val id: Int = 1,
    val source: AgendaSource
)
