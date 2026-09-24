package com.bnyro.clock

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.bnyro.clock.domain.model.AgendaSource
import com.bnyro.clock.presentation.screens.agenda.AgendaSourceContent
import com.bnyro.clock.presentation.screens.agenda.OAuthProvider
import com.bnyro.clock.presentation.screens.agenda.model.AgendaUiState
import org.junit.Rule
import org.junit.Test

class AgendaErrorPopupTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun agendaErrorUsesPopupInsteadOfInlineRedText() {
        compose.setContent {
            val error = remember { mutableStateOf<Int?>(R.string.agenda_sync_failed) }
            MaterialTheme {
                AgendaSourceContent(
                    state = AgendaUiState(loaded = true, source = AgendaSource.OAUTH),
                    hasPermission = true,
                    busy = false,
                    errorMessage = error.value,
                    onDismissError = { error.value = null },
                    onSelect = {},
                    onChooseOAuth = { _: OAuthProvider -> },
                    onGrantPermission = {},
                    onAddGoogleAccount = {},
                    onAddMicrosoftAccount = {},
                    onReconnect = {},
                    onRemove = {},
                    onDisconnectAll = {},
                    onDisconnectLocal = {},
                    onEnabledChanged = { _, _ -> }
                )
            }
        }

        compose.onNodeWithTag("agenda-error").assertDoesNotExist()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithTag("dismiss-agenda-error").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
    }
}
