package com.bnyro.clock.util.google

import com.bnyro.clock.domain.model.OAuthAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

data class GoogleHttpResponse(val statusCode: Int, val body: String)

/** Injectable transport: tokens travel in Authorization headers only. */
fun interface GoogleHttpTransport {
    suspend fun get(url: String, accessToken: String): GoogleHttpResponse
}

class UrlConnectionGoogleTransport : GoogleHttpTransport {
    override suspend fun get(url: String, accessToken: String): GoogleHttpResponse =
        withContext(Dispatchers.IO) {
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host in setOf("www.googleapis.com", "openidconnect.googleapis.com"))
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                // Never forward bearer tokens to a redirect destination.
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                val body = if (status in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else "" // Do not retain remote error bodies, which can contain private data.
                GoogleHttpResponse(status, body)
            } finally {
                connection.disconnect()
            }
        }
}

class GoogleApiException(val statusCode: Int) : IOException("Google request failed ($statusCode)")

class GoogleCalendarApi(private val transport: GoogleHttpTransport = UrlConnectionGoogleTransport()) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun profile(accessToken: String): OAuthAccount {
        val profile = decode<GoogleProfileDto>(get("https://openidconnect.googleapis.com/v1/userinfo", accessToken))
        if (profile.sub.isBlank() || profile.email.isBlank()) throw IOException("Invalid Google response")
        return OAuthAccount(
            id = profile.sub, provider = "GOOGLE_CALENDAR", profileId = profile.sub,
            displayName = profile.name.ifBlank { profile.email }, email = profile.email,
            state = "CONNECTED", lastSyncedAt = null, providerDisplayName = "Google Calendar"
        )
    }

    suspend fun events(accountId: String, accessToken: String, timeMin: Long, timeMax: Long): List<RemoteAgendaEvent> {
        require(accountId.isNotBlank() && timeMin < timeMax)
        val calendars = mutableListOf<GoogleCalendarDto>()
        var page: String? = null
        val calendarPages = mutableSetOf<String>()
        do {
            val response = decode<GoogleCalendarList>(get(url("users/me/calendarList", mapOf(
                "maxResults" to "250", "minAccessRole" to "reader", "showHidden" to "true", "pageToken" to page
            )), accessToken))
            calendars += response.items.filterNot { it.deleted }.also { items ->
                if (items.any { it.id.isBlank() }) throw IOException("Invalid Google response")
            }
            page = response.nextPageToken
            if (page != null && !calendarPages.add(page)) throw IOException("Invalid Google response")
        } while (page != null)

        val events = mutableListOf<RemoteAgendaEvent>()
        for (calendar in calendars.distinctBy { it.id }) {
            page = null
            val eventPages = mutableSetOf<String>()
            do {
                val response = decode<GoogleEventsList>(get(url("calendars/${encode(calendar.id)}/events", mapOf(
                    "singleEvents" to "true", "showDeleted" to "false", "maxResults" to "2500",
                    "timeMin" to Instant.ofEpochMilli(timeMin).toString(),
                    "timeMax" to Instant.ofEpochMilli(timeMax).toString(), "pageToken" to page
                )), accessToken))
                events += response.items.filterNot { it.status == "cancelled" }.map { event ->
                    mapEvent(accountId, calendar, event, response.timeZone ?: calendar.timeZone)
                }
                page = response.nextPageToken
                if (page != null && !eventPages.add(page)) throw IOException("Invalid Google response")
            } while (page != null)
        }
        return events.distinctBy { it.eventKey }.sortedBy { it.beginAt }
    }

    private fun mapEvent(accountId: String, calendar: GoogleCalendarDto, event: GoogleEventDto, zone: String): RemoteAgendaEvent = try {
        require(event.id.isNotBlank())
        val start = requireNotNull(event.start) { "Google event has no start" }
        val beginAt = start.epochMillis(zone)
        val endAt = requireNotNull(event.end) { "Google event has no end" }.epochMillis(zone)
        require(endAt >= beginAt)
        val instance = (event.originalStartTime ?: start).epochMillis(zone)
        val reminders = when {
            event.reminders == null || event.reminders.useDefault -> calendar.defaultReminders
            else -> event.reminders.overrides
        }
        // A larger minutes-before value rings earlier. Email reminders are not device notifications.
        val minutes = reminders.filter { it.method == "popup" && it.minutes >= 0 }.maxOfOrNull { it.minutes } ?: 0
        RemoteAgendaEvent(
            eventKey = "google:${encode(accountId)}:${encode(calendar.id)}:${encode(event.id)}:$instance",
            connectionId = accountId, calendarId = calendar.id, eventId = event.id,
            title = event.summary, beginAt = beginAt, endAt = endAt, reminderMinutes = minutes,
            allDay = start.dateTime == null
        )
    } catch (_: DateTimeException) {
        throw IOException("Invalid Google response")
    } catch (_: IllegalArgumentException) {
        throw IOException("Invalid Google response")
    } catch (_: ArithmeticException) {
        throw IOException("Invalid Google response")
    }

    private fun GoogleDateTimeDto.epochMillis(defaultZone: String): Long {
        val zone = ZoneId.of(timeZone ?: defaultZone)
        return dateTime?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrElse { _ ->
                LocalDateTime.parse(it).atZone(zone).toInstant().toEpochMilli()
            }
        } ?: LocalDate.parse(requireNotNull(date)).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private suspend fun get(url: String, accessToken: String): String {
        val response = transport.get(url, accessToken)
        if (response.statusCode !in 200..299) throw GoogleApiException(response.statusCode)
        return response.body
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (_: SerializationException) {
        // Serialization errors can include the JSON payload in their message/cause.
        throw IOException("Invalid Google response")
    }

    private fun url(path: String, parameters: Map<String, String?>): String =
        "https://www.googleapis.com/calendar/v3/$path?" + parameters.filterValues { it != null }
            .entries.joinToString("&") { "${encode(it.key)}=${encode(requireNotNull(it.value))}" }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
