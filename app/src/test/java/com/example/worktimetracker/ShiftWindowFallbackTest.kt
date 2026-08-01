package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.ShiftWindowFallback
import com.example.worktimetracker.domain.model.ShiftType
import com.example.worktimetracker.domain.model.WorkSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

class ShiftWindowFallbackTest {
    private val rules = ShiftWindowFallback(ZoneId.of("Asia/Shanghai"))
    private val settings = WorkSettings(workStartMinutes = 9 * 60, workEndMinutes = 21 * 60)

    @Test
    fun `0900 to 2100 creates opposite day and night windows`() {
        val date = LocalDate.of(2026, 8, 1)
        val day = rules.windowFor(date, ShiftType.DAY_SHIFT, settings)
        val night = rules.windowFor(date, ShiftType.NIGHT_SHIFT, settings)
        assertEquals("2026-08-01T09:00", day.startLocal.toString())
        assertEquals("2026-08-01T21:00", day.endLocal.toString())
        assertEquals("2026-08-01T21:00", night.startLocal.toString())
        assertEquals("2026-08-02T09:00", night.endLocal.toString())
    }

    @Test
    fun `company detection chooses nearest shift window`() {
        assertEquals(ShiftType.NIGHT_SHIFT, rules.nearestWindow(instant("2026-08-01T22:10:00+08:00"), settings).shift)
    }

    @Test
    fun `unfinished window never creates future departure`() {
        val result = rules.complete(instant("2026-08-01T12:00:00+08:00"), null, null, settings)
        assertNull(result.endMillis)
        assertTrue(result.needsReview)
    }

    @Test
    fun `completed window fills only missing edges`() {
        val reliableStart = instant("2026-08-01T09:08:00+08:00")
        val result = rules.complete(instant("2026-08-01T22:00:00+08:00"), reliableStart, null, settings)
        assertEquals(reliableStart, result.startMillis)
        assertEquals(instant("2026-08-01T21:00:00+08:00"), result.endMillis)
        assertTrue(result.usedFallbackEnd)
    }

    private fun instant(value: String): Long = OffsetDateTime.parse(value).toInstant().toEpochMilli()
}
