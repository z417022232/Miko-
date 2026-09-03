package com.example.worktimetracker

import com.example.worktimetracker.location.service.EvidenceContinuityPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceContinuityPolicyTest {
    private val policy = EvidenceContinuityPolicy()

    @Test fun firstObservationIsContinuous() {
        assertTrue(policy.isContinuous(null, 1_000L))
        assertFalse(policy.breaks(null, 1_000L))
    }

    @Test fun gapWithinTwentyMinutesIsContinuous() {
        assertTrue(policy.isContinuous(1_000L, 1_000L + 20 * 60_000L))
        assertTrue(policy.isContinuous(1_000L, 1_000L + 19 * 60_000L))
        assertFalse(policy.breaks(1_000L, 1_000L + 19 * 60_000L))
    }

    @Test fun gapBeyondTwentyMinutesBreaksContinuity() {
        assertFalse(policy.isContinuous(1_000L, 1_000L + 20 * 60_000L + 1L))
        assertTrue(policy.breaks(1_000L, 1_000L + 20 * 60_000L + 1L))
        assertTrue(policy.breaks(1_000L, 54_000_100L))
    }

    @Test fun backwardsTimeBreaksContinuity() {
        assertFalse(policy.isContinuous(1_000L, 999L))
        assertTrue(policy.breaks(1_000L, 999L))
    }

    @Test fun equalTimestampsAreContinuous() {
        assertTrue(policy.isContinuous(1_000L, 1_000L))
    }
}
