package com.bnyro.clock.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A Google Calendar event mirrored locally with the alarm created for it. */
@Entity(tableName = "agenda_events")
data class AgendaEvent(
    @PrimaryKey val eventKey: String,
    val calendarEventId: Long,
    val calendarId: Long,
    val title: String,
    val beginAt: Long,
    val endAt: Long,
    val alarmId: Long,
    val enabled: Boolean = true
)
