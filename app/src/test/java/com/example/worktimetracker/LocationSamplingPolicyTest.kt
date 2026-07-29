package com.example.worktimetracker

import com.example.worktimetracker.location.service.LocationSamplingPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LocationSamplingPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val policy = LocationSamplingPolicy(zone)

    @Test fun stableHomeAndCompanyUseThirtyMinuteSampling() {
        assertEquals(
            30 * 60_000L,
            policy.intervalMillis(
                currentState = "REST",
                locationType = "HOME",
                distanceToFenceMeters = 20.0,
                fenceRadiusMeters = 200,
                speedMetersPerSecond = 0f,
                nowMillis = ms(2026, 7, 29, 14, 0),
                workStartMinutes = 9 * 60,
                workEndMinutes = 21 * 60
            )
        )
        assertEquals(
            30 * 60_000L,
            policy.intervalMillis(
                currentState = "WORKING",
                locationType = "COMPANY",
                distanceToFenceMeters = 20.0,
                fenceRadiusMeters = 200,
                speedMetersPerSecond = 0f,
                nowMillis = ms(2026, 7, 29, 14, 0),
                workStartMinutes = 9 * 60,
                workEndMinutes = 21 * 60
            )
        )
    }

    @Test fun configuredStartAndEndWindowsUseFiveMinuteSampling() {
        for (now in listOf(ms(2026, 7, 29, 8, 0), ms(2026, 7, 29, 20, 0))) {
            assertEquals(
                5 * 60_000L,
                policy.intervalMillis("REST", "HOME", 20.0, 200, 0f, now, 9 * 60, 21 * 60)
            )
        }
    }

    @Test fun fenceEdgeMovementAndTransitionStatesUseOneMinuteSampling() {
        val afternoon = ms(2026, 7, 29, 14, 0)
        assertEquals(60_000L, policy.intervalMillis("WORKING", "COMPANY", 180.0, 200, 0f, afternoon, 9 * 60, 21 * 60))
        assertEquals(60_000L, policy.intervalMillis("REST", "HOME", 20.0, 200, 2f, afternoon, 9 * 60, 21 * 60))
        assertEquals(60_000L, policy.intervalMillis("TEMP_LEAVE", "OTHER", 300.0, 200, 0f, afternoon, 9 * 60, 21 * 60))
    }

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
}
