package com.bnyro.clock.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.bnyro.clock.domain.model.AgendaSource
import com.bnyro.clock.domain.model.AgendaSourceSelection
import kotlinx.coroutines.flow.Flow

@Dao
interface AgendaSourceDao {
    @Query("SELECT * FROM agenda_source WHERE id = 1")
    suspend fun current(): AgendaSourceSelection?

    @Query("SELECT * FROM agenda_source WHERE id = 1")
    fun currentStream(): Flow<AgendaSourceSelection?>

    @Query("INSERT OR REPLACE INTO agenda_source (id, source) VALUES (1, :source)")
    suspend fun select(source: AgendaSource)

    @Query("DELETE FROM agenda_source")
    suspend fun clear()
}
