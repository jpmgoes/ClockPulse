package com.bnyro.clock.util.microsoft

import android.app.Activity
import android.content.Context
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.OAuthAccount
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MicrosoftReconnectRequiredException : IOException("Microsoft Calendar requires reconnection")
/** User intentionally dismissed the Microsoft account UI; this is not an authentication failure. */
class MicrosoftAuthorizationCancelledException : IOException("Microsoft authorization cancelled")

data class MicrosoftAuthorization(val accessToken: String, val account: IAccount)

/** MSAL keeps all tokens in its encrypted account cache; no token is persisted by ClockPulse. */
class MicrosoftCalendarAuthorizer(context: Context) {
    private val appContext = context.applicationContext
    private val scopes = listOf("User.Read", "Calendars.Read")

    suspend fun authorize(activity: Activity, account: OAuthAccount? = null): MicrosoftAuthorization {
        val client = client()
        val msalAccount = account?.let { findAccount(client, it) }
        if (account != null && msalAccount == null) throw MicrosoftReconnectRequiredException()
        return suspendCancellableCoroutine { continuation ->
            val callback = object : AuthenticationCallback {
                override fun onSuccess(result: com.microsoft.identity.client.IAuthenticationResult) {
                    if (continuation.isActive) continuation.resume(MicrosoftAuthorization(result.accessToken, result.account))
                }
                override fun onError(exception: MsalException) {
                    if (continuation.isActive) continuation.resumeWithException(sanitize(exception))
                }
                override fun onCancel() {
                    if (continuation.isActive) {
                        continuation.resumeWithException(MicrosoftAuthorizationCancelledException())
                    }
                }
            }
            client.acquireToken(
                AcquireTokenParameters.Builder()
                    .startAuthorizationFromActivity(activity)
                    .withScopes(scopes)
                    .forAccount(msalAccount)
                    .withCallback(callback)
                    .build()
            )
        }
    }

    suspend fun accessToken(account: OAuthAccount): String {
        val client = client()
        val msalAccount = findAccount(client, account) ?: throw MicrosoftReconnectRequiredException()
        return suspendCancellableCoroutine { continuation ->
            client.acquireTokenSilentAsync(
                AcquireTokenSilentParameters.Builder()
                    .forAccount(msalAccount)
                    .fromAuthority("https://login.microsoftonline.com/common")
                    .withScopes(scopes)
                    .withCallback(object : SilentAuthenticationCallback {
                        override fun onSuccess(result: com.microsoft.identity.client.IAuthenticationResult) {
                            if (continuation.isActive) continuation.resume(result.accessToken)
                        }
                        override fun onError(exception: MsalException) {
                            if (continuation.isActive) continuation.resumeWithException(sanitize(exception))
                        }
                    })
                    .build()
            )
        }
    }

    suspend fun revoke(account: OAuthAccount) {
        val client = client()
        val msalAccount = findAccount(client, account) ?: return
        suspendCancellableCoroutine { continuation ->
            client.removeAccount(msalAccount, object : IMultipleAccountPublicClientApplication.RemoveAccountCallback {
                override fun onRemoved() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onError(exception: MsalException) {
                    if (continuation.isActive) continuation.resumeWithException(sanitize(exception))
                }
            })
        }
    }

    private suspend fun findAccount(
        client: IMultipleAccountPublicClientApplication,
        account: OAuthAccount
    ): IAccount? = suspendCancellableCoroutine { continuation ->
        client.getAccounts(object : IPublicClientApplication.LoadAccountsCallback {
            override fun onTaskCompleted(result: List<IAccount>) {
                if (continuation.isActive) continuation.resume(result.firstOrNull { it.id == account.profileId })
            }
            override fun onError(exception: MsalException) {
                if (continuation.isActive) continuation.resumeWithException(sanitize(exception))
            }
        })
    }

    private suspend fun client(): IMultipleAccountPublicClientApplication = suspendCancellableCoroutine { continuation ->
        PublicClientApplication.createMultipleAccountPublicClientApplication(
            appContext,
            R.raw.msal_config,
            object : IPublicClientApplication.IMultipleAccountApplicationCreatedListener {
                override fun onCreated(application: IMultipleAccountPublicClientApplication) {
                    if (continuation.isActive) continuation.resume(application)
                }
                override fun onError(exception: MsalException) {
                    if (continuation.isActive) continuation.resumeWithException(sanitize(exception))
                }
            }
        )
    }

    private fun sanitize(error: MsalException): IOException = when (error) {
        is MsalUiRequiredException -> MicrosoftReconnectRequiredException()
        else -> IOException("Microsoft authorization unavailable")
    }
}
