package com.bnyro.clock.domain.repository

import com.bnyro.clock.data.database.dao.OAuthAccountsDao
import com.bnyro.clock.domain.model.OAuthAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class OAuthAccountsRepository(private val dao: OAuthAccountsDao) {
    fun getAccountsStream(): Flow<List<OAuthAccount>> = dao.getAllStream()

    suspend fun getAccounts(): List<OAuthAccount> = withContext(Dispatchers.IO) { dao.getAll() }

    suspend fun findById(id: String): OAuthAccount? = withContext(Dispatchers.IO) {
        dao.findById(id)
    }

    suspend fun upsert(account: OAuthAccount) = withContext(Dispatchers.IO) {
        dao.upsert(account)
    }

    suspend fun updateState(id: String, state: String) = withContext(Dispatchers.IO) {
        dao.updateState(id, state)
    }

    suspend fun updateLastSyncedAt(id: String, lastSyncedAt: Long) = withContext(Dispatchers.IO) {
        dao.updateLastSyncedAt(id, lastSyncedAt)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { dao.deleteById(id) }

    suspend fun deleteAll() = withContext(Dispatchers.IO) { dao.deleteAll() }
}
