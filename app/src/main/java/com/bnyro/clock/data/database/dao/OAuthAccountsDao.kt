package com.bnyro.clock.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bnyro.clock.domain.model.OAuthAccount
import kotlinx.coroutines.flow.Flow

@Dao
interface OAuthAccountsDao {
    @Query("SELECT * FROM oauth_accounts ORDER BY displayName ASC")
    fun getAllStream(): Flow<List<OAuthAccount>>

    @Query("SELECT * FROM oauth_accounts ORDER BY displayName ASC")
    suspend fun getAll(): List<OAuthAccount>

    @Query("SELECT * FROM oauth_accounts WHERE id = :id")
    suspend fun findById(id: String): OAuthAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: OAuthAccount)

    @Query("UPDATE oauth_accounts SET state = :state WHERE id = :id")
    suspend fun updateState(id: String, state: String)

    @Query("UPDATE oauth_accounts SET lastSyncedAt = :lastSyncedAt WHERE id = :id")
    suspend fun updateLastSyncedAt(id: String, lastSyncedAt: Long)

    @Query("DELETE FROM oauth_accounts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM oauth_accounts")
    suspend fun deleteAll()
}
