package com.example.worktimetracker

import com.example.worktimetracker.location.service.LocationSamplingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun intervalSwitchDoesNotRecursivelyReregister() {
        val state = com.example.worktimetracker.location.service.SourceRegistrationState()
        // 第一次注册
        assertTrue(state.begin("location", 30 * 60_000L))
        // 间隔未变：反复应用同一间隔不触发重注册
        repeat(5) { assertFalse(state.begin("location", 30 * 60_000L)) }
        // 间隔切换到 1 分钟：注册一次
        assertTrue(state.begin("location", 60_000L))
        // 再切回 30 分钟：又注册一次，但相同配置不会反复注册
        assertTrue(state.begin("location", 30 * 60_000L))
        assertFalse(state.begin("location", 30 * 60_000L))
    }

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
}
