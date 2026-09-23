package com.bnyro.clock.util.google

import android.accounts.Account
import android.accounts.AccountManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.bnyro.clock.domain.model.OAuthAccount
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Tokens and consent intents are ephemeral; callers must never put these into saved state. */
sealed interface GoogleAuthorization {
    class Authorized(val accessToken: String) : GoogleAuthorization
    class ConsentRequired(val pendingIntent: PendingIntent) : GoogleAuthorization
}

class GoogleReconnectRequiredException : IOException("Google Calendar requires reconnection")
class GoogleAuthorizationException(val statusCode: Int) : IOException("Google authorization failed ($statusCode)")

interface GoogleTokenProvider {
    suspend fun accessToken(account: OAuthAccount): String
    suspend fun invalidateToken(account: OAuthAccount)
    suspend fun revoke(account: OAuthAccount)
}

/** Direct on-device authorization; no Web client, server auth code, secret or refresh token. */
class GoogleCalendarAuthorizer(
    context: Context,
    private val client: AuthorizationClient = Identity.getAuthorizationClient(context.applicationContext)
) : GoogleTokenProvider {
    private val tokens = ConcurrentHashMap<String, String>()

    /** Launch for every Add account action, including when only one device account exists. */
    fun accountChooserIntent(): Intent = AccountPicker.newChooseAccountIntent(
        AccountPicker.AccountChooserOptions.Builder()
            .setAllowableAccountsTypes(listOf("com.google"))
            .setAlwaysShowAccountPicker(true)
            .build()
    )

    fun selectedEmail(data: Intent?): String? = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        ?.takeIf { it.isNotBlank() }

    suspend fun authorize(email: String): GoogleAuthorization {
        require(email.isNotBlank())
        return client.authorize(AuthorizationRequest.builder()
            .setAccount(Account(email, "com.google"))
            .setRequestedScopes(scopes)
            .build()).awaitResult().asAuthorization()
    }

    fun finishAuthorization(data: Intent): GoogleAuthorization = try {
        client.getAuthorizationResultFromIntent(data).asAuthorization()
    } catch (error: ApiException) {
        throw error.sanitized()
    }

    /** Always ask Play services for a current token; the app retains only the last token to invalidate. */
    override suspend fun accessToken(account: OAuthAccount): String = when (val result = authorize(account.email)) {
        is GoogleAuthorization.Authorized -> result.accessToken.also { tokens[account.id] = it }
        is GoogleAuthorization.ConsentRequired -> throw GoogleReconnectRequiredException()
    }

    fun rememberToken(account: OAuthAccount, authorization: GoogleAuthorization.Authorized) {
        tokens[account.id] = authorization.accessToken
    }

    override suspend fun invalidateToken(account: OAuthAccount) {
        val token = tokens.remove(account.id) ?: return
        client.clearToken(ClearTokenRequest.builder().setToken(token).build()).awaitResult()
    }

    override suspend fun revoke(account: OAuthAccount) {
        try {
            client.revokeAccess(RevokeAccessRequest.builder()
                .setAccount(Account(account.email, "com.google"))
                .setScopes(scopes)
                .build()).awaitResult()
        } finally {
            tokens.remove(account.id)
        }
    }

    private fun AuthorizationResult.asAuthorization(): GoogleAuthorization {
        if (hasResolution()) return GoogleAuthorization.ConsentRequired(requireNotNull(pendingIntent))
        if (CALENDAR_SCOPE !in grantedScopes) throw GoogleReconnectRequiredException()
        val token = accessToken?.takeIf { it.isNotBlank() } ?: throw GoogleReconnectRequiredException()
        return GoogleAuthorization.Authorized(token)
    }

    companion object {
        private const val CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.readonly"
        val scopeNames = listOf(CALENDAR_SCOPE, "openid", "email", "profile")
        private val scopes get() = scopeNames.map(::Scope)
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(
            if (error is ApiException) error.sanitized() else IOException("Google authorization unavailable")
        )
    }
    addOnCanceledListener { continuation.cancel() }
}

private fun ApiException.sanitized(): IOException = if (statusCode == CommonStatusCodes.SIGN_IN_REQUIRED) {
    GoogleReconnectRequiredException()
} else GoogleAuthorizationException(statusCode)

/** Keeps the retry/state policy testable without a device or Google Play services. */
class AuthorizedGoogleCalendar(
    private val tokens: GoogleTokenProvider,
    private val api: GoogleCalendarApi,
    private val markReconnectRequired: suspend (String) -> Unit
) {
    suspend fun events(account: OAuthAccount, timeMin: Long, timeMax: Long): List<RemoteAgendaEvent> {
        repeat(2) { attempt ->
            try {
                return api.events(account.id, tokens.accessToken(account), timeMin, timeMax)
            } catch (error: IOException) {
                val unauthorized = error is GoogleReconnectRequiredException ||
                    (error is GoogleApiException && error.statusCode == 401)
                // A timeout, quota error or server error is not lost consent.
                if (!unauthorized) throw error
                try {
                    tokens.invalidateToken(account)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The in-memory token is already forgotten. Cache cleanup must not suppress
                    // the one fresh authorization attempt or the final reconnect state.
                }
                if (attempt == 1) {
                    markReconnectRequired(account.id)
                    throw GoogleReconnectRequiredException()
                }
            }
        }
        error("Unreachable authorization attempt")
    }
}
