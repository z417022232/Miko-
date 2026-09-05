package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkStateEntity
import com.example.worktimetracker.location.service.VerificationTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationTraceTest {
    private val before = WorkStateEntity(currentState = "FINISHED", sessionStart = null)
    private val after = WorkStateEntity(
        currentState = "REST", sessionStart = null,
        homeDepartureTime = 1_788_567_827_289L,
        companyArrivalConfirmedAt = 1_788_568_907_000L,
        confirmedDepartureTime = 1_788_613_618_526L,
        homeArrivalTime = 1_788_613_878_048L
    )

    @Test fun burstPhaseReflectsMediumAndFastWindows() {
        val now = 1_000_000L
        assertEquals("-", VerificationTrace.burstPhase(0L, false, now))
        assertEquals("-", VerificationTrace.burstPhase(now - 1, false, now))
        assertEquals("FAST_BURST", VerificationTrace.burstPhase(now + 60_000L, false, now))
        assertEquals("MOVING_TRACK", VerificationTrace.burstPhase(now + 60_000L, true, now))
    }

    @Test fun stateLineCarriesAllVerificationFields() {
        val now = 1_788_613_880_000L
        val line = VerificationTrace.stateLine(
            before, after, "HOME",
            samplingIntervalMillis = 5 * 60_000L,
            burstUntil = 0L, burstMedium = false, now = now
        )
        assertTrue(line.startsWith("TRACE"))
        assertTrue(line.contains("gps=HOME"))
        assertTrue(line.contains("state=FINISHED→REST"))
        assertTrue(line.contains("离家=08:23:47"))
        assertTrue(line.contains("到家=21:11:18"))
        assertTrue(line.contains("采样=5分钟档"))
        assertTrue(line.contains("burst=-"))
    }

    @Test fun stateLineShowsBurstPhaseWhileActive() {
        val now = 1_788_613_880_000L
        val line = VerificationTrace.stateLine(
            before, after, "OTHER",
            samplingIntervalMillis = 60_000L,
            burstUntil = now + 120_000L, burstMedium = true, now = now
        )
        assertTrue(line.contains("采样=1分钟档"))
        assertTrue(line.contains("burst=MOVING_TRACK"))
    }
}
