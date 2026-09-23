package com.bnyro.clock.util.google

import kotlinx.serialization.Serializable

@Serializable
internal data class GoogleCalendarList(
    val items: List<GoogleCalendarDto> = emptyList(),
    val nextPageToken: String? = null
)

@Serializable
internal data class GoogleCalendarDto(
    val id: String,
    val timeZone: String = "UTC",
    val deleted: Boolean = false,
    val defaultReminders: List<GoogleReminderDto> = emptyList()
)

@Serializable
internal data class GoogleEventsList(
    val items: List<GoogleEventDto> = emptyList(),
    val nextPageToken: String? = null,
    val timeZone: String? = null
)

@Serializable
internal data class GoogleEventDto(
    val id: String,
    val status: String = "confirmed",
    val summary: String = "",
    val start: GoogleDateTimeDto? = null,
    val end: GoogleDateTimeDto? = null,
    val originalStartTime: GoogleDateTimeDto? = null,
    val reminders: GoogleEventRemindersDto? = null
)

@Serializable
internal data class GoogleDateTimeDto(
    val dateTime: String? = null,
    val date: String? = null,
    val timeZone: String? = null
)

@Serializable
internal data class GoogleEventRemindersDto(
    val useDefault: Boolean = false,
    val overrides: List<GoogleReminderDto> = emptyList()
)

@Serializable
internal data class GoogleReminderDto(val method: String, val minutes: Int)

@Serializable
internal data class GoogleProfileDto(val sub: String, val email: String, val name: String = "")

data class RemoteAgendaEvent(
    val eventKey: String,
    val connectionId: String,
    val calendarId: String,
    val eventId: String,
    val title: String,
    val beginAt: Long,
    val endAt: Long,
    val reminderMinutes: Int,
    val allDay: Boolean
)
