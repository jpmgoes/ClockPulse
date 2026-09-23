package com.bnyro.clock.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Public profile metadata only. OAuth credentials are managed by Google Play services. */
@Entity(tableName = "oauth_accounts")
data class OAuthAccount(
    @PrimaryKey val id: String,
    val provider: String,
    val profileId: String,
    val displayName: String,
    val email: String,
    val state: String,
    val lastSyncedAt: Long?,
    val providerDisplayName: String = "Google Calendar"
)
