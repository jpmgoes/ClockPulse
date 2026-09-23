package com.bnyro.clock.domain.repository

import com.bnyro.clock.data.database.dao.AgendaEventsDao
import com.bnyro.clock.domain.model.AgendaEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class AgendaRepository(private val dao: AgendaEventsDao) {
    fun getEventsStream(): Flow<List<AgendaEvent>> = dao.getAllStream()

    suspend fun getEvents(): List<AgendaEvent> = withContext(Dispatchers.IO) { dao.getAll() }

    suspend fun findByKey(eventKey: String): AgendaEvent? =
        withContext(Dispatchers.IO) { dao.findByKey(eventKey) }

    suspend fun upsert(event: AgendaEvent) = withContext(Dispatchers.IO) { dao.upsert(event) }

    suspend fun updateEnabled(eventKey: String, enabled: Boolean) =
        withContext(Dispatchers.IO) { dao.updateEnabled(eventKey, enabled) }

    suspend fun delete(eventKey: String) = withContext(Dispatchers.IO) { dao.deleteByKey(eventKey) }
}
