package com.example.worktimetracker

import com.example.worktimetracker.location.evidence.AmbientScanPolicy
import com.example.worktimetracker.location.evidence.ScanDecision
import com.example.worktimetracker.location.evidence.ScanPolicyInput
import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientScanPolicyTest {
    private val policy = AmbientScanPolicy()

    @Test fun motionRequestsShortBurstButDuplicateDoesNotRescan() {
        val first = policy.evaluate(ScanPolicyInput(1_000_000L, 0L, true, false, false, false))
        val duplicate = policy.evaluate(ScanPolicyInput(1_010_000L, 1_000_000L, true, false, false, false))
        assertEquals(ScanDecision.BURST, first)
        assertEquals(ScanDecision.NONE, duplicate)
    }

    @Test fun stableKnownPlaceDoesNotScanAndStaleGnssDoes() {
        assertEquals(ScanDecision.NONE,
            policy.evaluate(ScanPolicyInput(2_000_000L, 0L, false, false, false, true)))
        assertEquals(ScanDecision.BURST,
            policy.evaluate(ScanPolicyInput(2_000_000L, 0L, false, true, false, false)))
    }

    @Test fun shiftWindowRequestsSnapshotOnlyAfterCooldown() {
        assertEquals(ScanDecision.SNAPSHOT,
            policy.evaluate(ScanPolicyInput(3_000_000L, 0L, false, false, true, false)))
        assertEquals(ScanDecision.NONE,
            policy.evaluate(ScanPolicyInput(3_000_010L, 3_000_000L, false, false, true, false)))
        assertEquals(ScanDecision.SNAPSHOT,
            policy.evaluate(ScanPolicyInput(3_000_000L + 5 * 60_000L, 3_000_000L, false, false, true, false)))
    }

    @Test fun cooldownExpiresAfterFiveMinutes() {
        assertEquals(ScanDecision.NONE,
            policy.evaluate(ScanPolicyInput(1_005_000L, 1_000_000L, true, false, false, false)))
        assertEquals(ScanDecision.BURST,
            policy.evaluate(ScanPolicyInput(1_300_000L, 1_000_000L, true, false, false, false)))
    }
}
