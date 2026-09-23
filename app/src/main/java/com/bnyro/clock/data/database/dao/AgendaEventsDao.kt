package com.bnyro.clock.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bnyro.clock.domain.model.AgendaEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface AgendaEventsDao {
    @Query("SELECT * FROM agenda_events ORDER BY beginAt ASC")
    fun getAllStream(): Flow<List<AgendaEvent>>

    @Query("SELECT * FROM agenda_events")
    suspend fun getAll(): List<AgendaEvent>

    @Query("SELECT * FROM agenda_events WHERE eventKey = :eventKey")
    suspend fun findByKey(eventKey: String): AgendaEvent?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: AgendaEvent)

    @Query("UPDATE agenda_events SET enabled = :enabled WHERE eventKey = :eventKey")
    suspend fun updateEnabled(eventKey: String, enabled: Boolean)

    @Query("DELETE FROM agenda_events WHERE eventKey = :eventKey")
    suspend fun deleteByKey(eventKey: String)
}
