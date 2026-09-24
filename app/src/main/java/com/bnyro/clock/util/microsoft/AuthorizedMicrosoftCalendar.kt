package com.bnyro.clock.util.microsoft

import com.bnyro.clock.domain.model.OAuthAccount
import com.bnyro.clock.util.google.RemoteAgendaEvent
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Converts a missing/expired cached MSAL account into the same reconnect state as Google. */
class AuthorizedMicrosoftCalendar(
    private val authorizer: MicrosoftCalendarAuthorizer,
    private val api: MicrosoftGraphApi,
    private val markReconnectRequired: suspend (String) -> Unit
) {
    suspend fun events(account: OAuthAccount, timeMin: Long, timeMax: Long): List<RemoteAgendaEvent> = try {
        api.events(account, authorizer.accessToken(account), timeMin, timeMax)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        if (error is MicrosoftReconnectRequiredException ||
            (error is MicrosoftGraphException && error.statusCode in setOf(401, 403))
        ) markReconnectRequired(account.id)
        throw error
    }
}
