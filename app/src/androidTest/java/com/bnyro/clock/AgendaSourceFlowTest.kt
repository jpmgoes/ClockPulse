package com.bnyro.clock

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bnyro.clock.domain.model.AgendaEvent
import com.bnyro.clock.domain.model.AgendaSource
import com.bnyro.clock.domain.model.OAuthAccount
import com.bnyro.clock.presentation.screens.agenda.AgendaSourceContent
import com.bnyro.clock.presentation.screens.agenda.model.AgendaUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgendaSourceFlowTest {
    @get:Rule val compose = createComposeRule()
    private val account = OAuthAccount("one", "GOOGLE_CALENDAR", "one", "Test Person", "test@example.com", "CONNECTED", null)

    @Test fun initialSelectorOffersBothSources() {
        val state = mutableStateOf(AgendaUiState(loaded = true))
        render(state)
        compose.onNodeWithTag("choose-local").assertIsDisplayed()
        compose.onNodeWithTag("choose-oauth").assertIsDisplayed().performClick()
        compose.onNodeWithTag("choose-local").assertDoesNotExist()
        compose.onNodeWithTag("manage-google-accounts").assertIsDisplayed()
    }

    @Test fun localNeverRendersOAuthAccountsOrEvents() {
        render(mutableStateOf(AgendaUiState(true, AgendaSource.LOCAL, listOf(account), mixedEvents())))
        compose.onNodeWithTag("disconnect-local").assertIsDisplayed()
        compose.onNodeWithTag("oauth-account").assertDoesNotExist()
        compose.onNodeWithTag("manage-google-accounts").assertDoesNotExist()
        compose.onNodeWithText("Local event").assertIsDisplayed()
        compose.onNodeWithText("Remote event").assertDoesNotExist()
    }

    @Test fun oauthShowsProviderAndProfileButNeverLocalControlsOrEvents() {
        render(mutableStateOf(AgendaUiState(true, AgendaSource.OAUTH, listOf(account), mixedEvents())))
        compose.onNodeWithTag("manage-google-accounts").performClick()
        compose.onNodeWithText("Google Calendar", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(account.displayName, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(account.email, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("local-permission").assertDoesNotExist()
        compose.onNodeWithTag("disconnect-local").assertDoesNotExist()
        compose.onNodeWithTag("choose-local").assertDoesNotExist()
        compose.onNodeWithText("Local event").assertDoesNotExist()
    }

    @Test fun localChoiceReturnsOnlyAfterConfirmingDisconnectAll() {
        val state = mutableStateOf(AgendaUiState(true, AgendaSource.OAUTH, listOf(account)))
        var disconnected = 0
        render(state) { disconnected++; state.value = AgendaUiState(loaded = true) }
        compose.onNodeWithTag("manage-google-accounts").performClick()
        compose.onNodeWithTag("disconnect-all").performScrollTo().performClick()
        compose.onNodeWithTag("choose-local").assertDoesNotExist()
        assertEquals(0, disconnected)
        compose.onNodeWithTag("confirm-disconnect").performClick()
        assertEquals(1, disconnected)
        compose.onNodeWithTag("choose-local").assertIsDisplayed().performClick()
        compose.onNodeWithTag("disconnect-local").assertIsDisplayed()
        compose.onNodeWithTag("oauth-account").assertDoesNotExist()
    }

    private fun render(state: androidx.compose.runtime.MutableState<AgendaUiState>, disconnect: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                AgendaSourceContent(state.value, true, false, null, onDismissError = {},
                    onSelect = { state.value = AgendaUiState(true, it) },
                    onChooseOAuth = {}, onGrantPermission = {}, onAddGoogleAccount = {}, onAddMicrosoftAccount = {},
                    onReconnect = {}, onRemove = {},
                    onDisconnectAll = disconnect, onDisconnectLocal = {}, onEnabledChanged = { _, _ -> })
            }
        }
    }

    private fun mixedEvents() = listOf(
        AgendaEvent("local:1", 1, 1, "Local event", 1000, 2000, alarmId = 1),
        AgendaEvent("google:one:1", 0, 0, "Remote event", 1000, 2000, alarmId = 2, connectionId = "one")
    )
}
