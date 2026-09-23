package com.bnyro.clock

import com.bnyro.clock.util.AgendaSyncWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AgendaSyncWindowTest {
    @Test
    fun `keeps events from earlier today in the synchronization window`() {
        val zone = ZoneId.of("America/Fortaleza")
        val now = ZonedDateTime.of(2026, 9, 23, 14, 38, 0, 0, zone).toInstant().toEpochMilli()
        val eventAt = ZonedDateTime.of(2026, 9, 23, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val expectedWindowStart = ZonedDateTime.of(2026, 9, 23, 0, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()

        assertEquals(expectedWindowStart, AgendaSyncWindow.startOfCurrentDay(now, zone))
        assert(eventAt >= AgendaSyncWindow.startOfCurrentDay(now, zone))
    }
}
