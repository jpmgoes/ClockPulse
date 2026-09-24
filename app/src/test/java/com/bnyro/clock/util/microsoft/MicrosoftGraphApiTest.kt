package com.bnyro.clock.util.microsoft

import com.bnyro.clock.util.google.RemoteAgendaEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrosoftGraphApiTest {
    @Test
    fun `single Outlook event with null original start uses its own start for identity`() {
        val event = Json.parseToJsonElement(
            """{
                "id":"event-123",
                "subject":"Teste de notificação da agenda",
                "start":{"dateTime":"2026-09-23T23:00:00.0000000","timeZone":"UTC"},
                "end":{"dateTime":"2026-09-23T23:15:00.0000000","timeZone":"UTC"},
                "isAllDay":false,
                "isReminderOn":true,
                "reminderMinutesBeforeStart":15,
                "originalStart":null
            }"""
        ).jsonObject
        val parser = MicrosoftGraphApi::class.java.getDeclaredMethod(
            "mapEvent",
            String::class.java,
            String::class.java,
            event::class.java
        ).apply { isAccessible = true }

        val parsed = parser.invoke(MicrosoftGraphApi(), "microsoft:account", "calendar", event) as RemoteAgendaEvent

        assertEquals("event-123", parsed.eventId)
        assertEquals(1790204400000L, parsed.beginAt)
        assertEquals(1790204400000L, parsed.eventKey.substringAfterLast(':').toLong())
    }
}
