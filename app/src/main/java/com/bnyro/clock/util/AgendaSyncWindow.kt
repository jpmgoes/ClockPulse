package com.bnyro.clock.util

import java.time.Instant
import java.time.ZoneId

internal object AgendaSyncWindow {
    fun startOfCurrentDay(now: Long, zoneId: ZoneId): Long =
        Instant.ofEpochMilli(now)
            .atZone(zoneId)
            .toLocalDate()
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
}
