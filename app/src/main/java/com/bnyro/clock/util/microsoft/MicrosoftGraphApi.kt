package com.bnyro.clock.util.microsoft

import com.bnyro.clock.domain.model.OAuthAccount
import com.bnyro.clock.util.google.RemoteAgendaEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

class MicrosoftGraphException(val statusCode: Int) : IOException("Microsoft Graph request failed ($statusCode)")

class MicrosoftGraphApi {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun profile(accessToken: String, msalProfileId: String): OAuthAccount {
        val body = get("https://graph.microsoft.com/v1.0/me?\$select=id,displayName,mail,userPrincipalName", accessToken)
        val profile = json.parseToJsonElement(body).jsonObject
        val email = profile.string("mail").ifBlank { profile.string("userPrincipalName") }
        if (msalProfileId.isBlank() || email.isBlank()) throw IOException("Invalid Microsoft response")
        return OAuthAccount(
            id = "microsoft:$msalProfileId", provider = "MICROSOFT_GRAPH", profileId = msalProfileId,
            displayName = profile.string("displayName").ifBlank { email }, email = email,
            state = "CONNECTED", lastSyncedAt = null, providerDisplayName = "Microsoft Outlook"
        )
    }

    suspend fun events(account: OAuthAccount, accessToken: String, timeMin: Long, timeMax: Long): List<RemoteAgendaEvent> {
        val calendars = page("https://graph.microsoft.com/v1.0/me/calendars?\$select=id,name", accessToken)
        val output = mutableListOf<RemoteAgendaEvent>()
        for (calendar in calendars) {
            val calendarId = calendar.string("id")
            if (calendarId.isBlank()) throw IOException("Invalid Microsoft response")
            val url = "https://graph.microsoft.com/v1.0/me/calendars/${encode(calendarId)}/calendarView?" +
                "startDateTime=${encode(Instant.ofEpochMilli(timeMin).toString())}&" +
                "endDateTime=${encode(Instant.ofEpochMilli(timeMax).toString())}&" +
                "\$select=id,subject,start,end,isAllDay,isReminderOn,reminderMinutesBeforeStart,originalStart"
            page(url, accessToken).forEach { event -> output += mapEvent(account.id, calendarId, event) }
        }
        return output.distinctBy { it.eventKey }.sortedBy { it.beginAt }
    }

    private suspend fun page(initialUrl: String, accessToken: String): List<JsonObject> {
        var url: String? = initialUrl
        val visited = mutableSetOf<String>()
        val items = mutableListOf<JsonObject>()
        while (url != null) {
            if (!visited.add(url)) throw IOException("Invalid Microsoft response")
            val root = json.parseToJsonElement(get(url, accessToken)).jsonObject
            items += root["value"]?.jsonArray.orEmpty().map { it.jsonObject }
            url = root.string("@odata.nextLink").takeIf { it.startsWith("https://graph.microsoft.com/") }
        }
        return items
    }

    private fun mapEvent(accountId: String, calendarId: String, event: JsonObject): RemoteAgendaEvent {
        val eventId = event.string("id")
        val start = event["start"]?.jsonObject ?: throw IOException("Invalid Microsoft response")
        val end = event["end"]?.jsonObject ?: throw IOException("Invalid Microsoft response")
        val beginAt = parseDateTime(start.string("dateTime"))
        val endAt = parseDateTime(end.string("dateTime"))
        if (eventId.isBlank() || endAt < beginAt) throw IOException("Invalid Microsoft response")
        val original = event.string("originalStart").takeIf { it.isNotBlank() }?.let(::parseDateTime) ?: beginAt
        val reminder = if (event["isReminderOn"]?.jsonPrimitive?.content == "true")
            event["reminderMinutesBeforeStart"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceAtLeast(0) ?: 0 else 0
        return RemoteAgendaEvent(
            eventKey = "microsoft:${encode(accountId)}:${encode(calendarId)}:${encode(eventId)}:$original",
            connectionId = accountId, calendarId = calendarId, eventId = eventId,
            title = event.string("subject"), beginAt = beginAt, endAt = endAt,
            reminderMinutes = reminder, allDay = event["isAllDay"]?.jsonPrimitive?.content == "true"
        )
    }

    private fun parseDateTime(value: String): Long = try {
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrElse {
            LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
        }
    } catch (_: Exception) { throw IOException("Invalid Microsoft response") }

    private suspend fun get(url: String, accessToken: String): String = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "graph.microsoft.com")
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Prefer", "outlook.timezone=\"UTC\"")
            val status = connection.responseCode
            if (status !in 200..299) throw MicrosoftGraphException(status)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
