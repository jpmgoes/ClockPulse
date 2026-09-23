package com.bnyro.clock.util.google

import com.bnyro.clock.domain.model.OAuthAccount
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GoogleCalendarApiTest {
    private val start = "2026-09-23T19:00:00-03:00"
    private val end = "2026-09-23T20:00:00-03:00"

    private fun api(reminders: String? = null, defaults: String = "[]"): GoogleCalendarApi {
        val reminderField = reminders?.let { ",\"reminders\":$it" }.orEmpty()
        return GoogleCalendarApi(GoogleHttpTransport { url, _ ->
            GoogleHttpResponse(200, if (url.contains("calendarList")) {
                """{"items":[{"id":"shared@example.com","timeZone":"America/Fortaleza","defaultReminders":$defaults}]}"""
            } else {
                """{"items":[{"id":"event","summary":"Terço","start":{"dateTime":"$start"},"end":{"dateTime":"$end"}$reminderField}]}"""
            })
        })
    }

    @Test fun `identical calendar event IDs in two accounts have distinct keys`() = runBlocking {
        val api = api()
        val a = api.events("accountA", "token", 0L, Long.MAX_VALUE).single()
        val b = api.events("accountB", "token", 0L, Long.MAX_VALUE).single()
        assertNotEquals(a.eventKey, b.eventKey)
        assertTrue(a.eventKey.startsWith("google:accountA:"))
        assertEquals("accountA", a.connectionId)
    }

    @Test fun `earliest popup override wins and ignores email reminders`() = runBlocking {
        val event = api("""{"useDefault":false,"overrides":[{"method":"email","minutes":120},{"method":"popup","minutes":15},{"method":"popup","minutes":60}]}""").events("a", "token", 0L, Long.MAX_VALUE).single()
        assertEquals(60, event.reminderMinutes)
        assertEquals(java.time.Instant.parse("2026-09-23T21:00:00Z").toEpochMilli(), event.beginAt - event.reminderMinutes * 60_000L)
    }

    @Test fun `calendar defaults apply when event uses defaults`() = runBlocking {
        val event = api("""{"useDefault":true}""", """[{"method":"popup","minutes":90}]""").events("a", "token", 0L, Long.MAX_VALUE).single()
        assertEquals(90, event.reminderMinutes)
    }

    @Test fun `no popup reminder uses start time even if calendar has defaults`() = runBlocking {
        val event = api("""{"useDefault":false,"overrides":[]}""", """[{"method":"popup","minutes":30}]""").events("a", "token", 0L, Long.MAX_VALUE).single()
        assertEquals(0, event.reminderMinutes)
        assertEquals(0, api().events("a", "token", 0L, Long.MAX_VALUE).single().reminderMinutes)
    }

    @Test fun `calendar and event pagination preserve all instances and skip cancelled events`() = runBlocking {
        val requests = mutableListOf<String>()
        val api = GoogleCalendarApi(GoogleHttpTransport { url, token ->
            requests += url
            assertEquals("token", token)
            val body = when {
                url.contains("calendarList") && !url.contains("pageToken") ->
                    """{"items":[{"id":"first@example.com"}],"nextPageToken":"calendar+page"}"""
                url.contains("calendarList") -> """{"items":[{"id":"second@example.com"}]}"""
                url.contains("second%40") -> """{"items":[{"id":"cancelled","status":"cancelled"}]}"""
                !url.contains("pageToken") ->
                    """{"items":[{"id":"one","start":{"dateTime":"$start"},"end":{"dateTime":"$end"}}],"nextPageToken":"event+page"}"""
                else -> """{"items":[{"id":"two","start":{"dateTime":"$start"},"end":{"dateTime":"$end"}}]}"""
            }
            GoogleHttpResponse(200, body)
        })
        assertEquals(listOf("one", "two"), api.events("a", "token", 0L, Long.MAX_VALUE).map { it.eventId })
        assertEquals(5, requests.size)
        assertTrue(requests[1].contains("pageToken=calendar%2Bpage"))
        assertTrue(requests[3].contains("pageToken=event%2Bpage"))
        assertTrue(requests[2].contains("singleEvents=true"))
    }

    @Test fun `all day times use calendar timezone and moved recurring instance keeps original identity`() = runBlocking {
        val api = GoogleCalendarApi(GoogleHttpTransport { url, _ ->
            GoogleHttpResponse(200, if (url.contains("calendarList")) {
                """{"items":[{"id":"cal","timeZone":"America/Fortaleza"}]}"""
            } else {
                """{"items":[{"id":"day","start":{"date":"2026-09-23"},"end":{"date":"2026-09-24"}},
                {"id":"recurring","start":{"dateTime":"$start"},"end":{"dateTime":"$end"},"originalStartTime":{"dateTime":"2026-09-23T18:00:00-03:00"}}]}"""
            })
        })
        val events = api.events("a", "token", 0L, Long.MAX_VALUE)
        assertTrue(events.first().allDay)
        assertEquals(java.time.Instant.parse("2026-09-23T03:00:00Z").toEpochMilli(), events.first().beginAt)
        assertEquals("google:a:cal:recurring:${java.time.Instant.parse("2026-09-23T21:00:00Z").toEpochMilli()}", events.last().eventKey)
    }

    @Test fun `userinfo supplies stable account id and provider profile metadata`() = runBlocking {
        val api = GoogleCalendarApi(GoogleHttpTransport { url, _ ->
            assertEquals("https://openidconnect.googleapis.com/v1/userinfo", url)
            GoogleHttpResponse(200, """{"sub":"123","email":"person@example.com","name":"Person","picture":"ignored"}""")
        })
        val profile = api.profile("token")
        assertEquals("123", profile.id)
        assertEquals("123", profile.profileId)
        assertEquals("Google Calendar", profile.providerDisplayName)
        assertEquals("Person", profile.displayName)
        assertEquals("person@example.com", profile.email)
    }

    private val account = OAuthAccount("123", "GOOGLE_CALENDAR", "123", "Person", "person@example.com", "CONNECTED", null)

    private class FakeTokens : GoogleTokenProvider {
        var requests = 0
        var invalidations = 0
        var requiresConsent = false
        override suspend fun accessToken(account: OAuthAccount): String {
            requests++
            if (requiresConsent) throw GoogleReconnectRequiredException()
            return "token$requests"
        }
        override suspend fun invalidateToken(account: OAuthAccount) { invalidations++ }
        override suspend fun revoke(account: OAuthAccount) = Unit
    }

    @Test fun `401 clears token then retries once without marking reconnect if recovered`() = runBlocking {
        val tokens = FakeTokens()
        val marked = mutableListOf<String>()
        val api = GoogleCalendarApi(GoogleHttpTransport { _, token ->
            if (token == "token1") GoogleHttpResponse(401, "") else GoogleHttpResponse(200, """{"items":[]}""")
        })
        val events = AuthorizedGoogleCalendar(tokens, api) { marked += it }.events(account, 0L, 1L)
        assertTrue(events.isEmpty())
        assertEquals(2, tokens.requests)
        assertEquals(1, tokens.invalidations)
        assertTrue(marked.isEmpty())
    }

    @Test fun `repeated 401 marks only failing account reconnect required after second attempt`() = runBlocking {
        val tokens = FakeTokens()
        val marked = mutableListOf<String>()
        val gateway = AuthorizedGoogleCalendar(tokens, GoogleCalendarApi(GoogleHttpTransport { _, _ -> GoogleHttpResponse(401, "") })) {
            assertEquals(2, tokens.requests)
            marked += it
        }
        try {
            gateway.events(account, 0L, 1L)
            fail("Expected reconnection")
        } catch (_: GoogleReconnectRequiredException) { }
        assertEquals(2, tokens.invalidations)
        assertEquals(listOf("123"), marked)
    }

    @Test fun `consent required is retried once then surfaced without opening background UI`() = runBlocking {
        val tokens = FakeTokens().apply { requiresConsent = true }
        val marked = mutableListOf<String>()
        val gateway = AuthorizedGoogleCalendar(tokens, api()) { marked += it }
        try {
            gateway.events(account, 0L, 1L)
            fail("Expected reconnection")
        } catch (_: GoogleReconnectRequiredException) { }
        assertEquals(2, tokens.requests)
        assertEquals(listOf("123"), marked)
    }

    @Test fun `server failure preserves connection and does not retry authorization`() = runBlocking {
        val tokens = FakeTokens()
        val marked = mutableListOf<String>()
        val gateway = AuthorizedGoogleCalendar(tokens, GoogleCalendarApi(GoogleHttpTransport { _, _ -> GoogleHttpResponse(503, "") })) { marked += it }
        try {
            gateway.events(account, 0L, 1L)
            fail("Expected API failure")
        } catch (error: GoogleApiException) { assertEquals(503, error.statusCode) }
        assertEquals(1, tokens.requests)
        assertEquals(0, tokens.invalidations)
        assertTrue(marked.isEmpty())
    }
}
