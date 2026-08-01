package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.model.WorkSettings
import com.example.worktimetracker.location.service.CompanyPresenceFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

class CompanyPresenceFallbackTest {
    private val settings = WorkSettings(workStartMinutes = 9 * 60, workEndMinutes = 21 * 60, restDeductionMinutes = 60)
    private val policy = CompanyPresenceFallback(ZoneId.of("Asia/Shanghai"))

    @Test
    fun `company presence during active shift creates draft without future end`() {
        val action = policy.evaluate(instant("2026-08-01T12:00:00+08:00"), instant("2026-08-01T12:00:00+08:00"), null, settings)
        val record = (action as CompanyPresenceFallback.Action.Draft).record
        assertEquals(instant("2026-08-01T09:00:00+08:00"), record.startTime)
        assertNull(record.endTime)
        assertTrue(record.needsReview)
    }

    @Test
    fun `health check after shift end completes review record`() {
        val firstFix = instant("2026-08-01T12:00:00+08:00")
        val draft = (policy.evaluate(firstFix, firstFix, null, settings) as CompanyPresenceFallback.Action.Draft).record
        val action = policy.evaluate(firstFix, instant("2026-08-01T22:00:00+08:00"), draft, settings)
        val record = (action as CompanyPresenceFallback.Action.UpsertReview).record
        assertEquals(instant("2026-08-01T09:00:00+08:00"), record.startTime)
        assertEquals(instant("2026-08-01T21:00:00+08:00"), record.endTime)
        assertEquals(660, record.finalMinutes)
        assertTrue(record.needsReview)
    }

    @Test
    fun `manual record is never changed`() {
        val manual = WorkRecordEntity(workDate = "2026-08-01", status = "MANUAL", finalMinutes = 600, isManual = true)
        assertEquals(CompanyPresenceFallback.Action.None, policy.evaluate(instant("2026-08-01T12:00:00+08:00"), instant("2026-08-01T22:00:00+08:00"), manual, settings))
    }

    private fun instant(value: String) = OffsetDateTime.parse(value).toInstant().toEpochMilli()
}
