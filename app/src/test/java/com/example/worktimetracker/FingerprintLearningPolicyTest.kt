package com.example.worktimetracker

import com.example.worktimetracker.domain.evidence.FingerprintLearningPolicy
import com.example.worktimetracker.domain.evidence.FingerprintLevel
import com.example.worktimetracker.domain.evidence.FingerprintState
import com.example.worktimetracker.domain.evidence.LearningGate
import com.example.worktimetracker.domain.evidence.LearningSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintLearningPolicyTest {
    private val policy = FingerprintLearningPolicy()

    @Test fun requiresReliableCoreGnssAndFiveMinutes() {
        val rejected = policy.accepts(LearningGate(51f, 10 * 60_000L, true, false, false, false))
        val accepted = policy.accepts(LearningGate(20f, 5 * 60_000L, true, false, false, false))
        assertFalse(rejected)
        assertTrue(accepted)
    }

    @Test fun rejectsInferredReplayAndAnomalousInputs() {
        assertFalse(policy.accepts(LearningGate(20f, 5 * 60_000L, true, true, false, false)))
        assertFalse(policy.accepts(LearningGate(20f, 5 * 60_000L, true, false, true, false)))
        assertFalse(policy.accepts(LearningGate(20f, 5 * 60_000L, true, false, false, true)))
        assertFalse(policy.accepts(LearningGate(20f, 5 * 60_000L, false, false, false, false)))
    }

    @Test fun promotesOnlyAfterSixObservationsAcrossThreeDays() {
        var state: FingerprintState? = null
        listOf("2026-09-01", "2026-09-01", "2026-09-02", "2026-09-02", "2026-09-03", "2026-09-03")
            .forEachIndexed { index, day -> state = policy.update(state, LearningSample(day, 1_000L + index, -60)) }
        assertEquals(FingerprintLevel.STABLE, state!!.level)
        assertEquals(3, state!!.distinctDayCount)
    }

    @Test fun fiveObservationsAcrossThreeDaysIsNotStable() {
        var state: FingerprintState? = null
        listOf("2026-09-01", "2026-09-02", "2026-09-03", "2026-09-03", "2026-09-03")
            .forEachIndexed { index, day -> state = policy.update(state, LearningSample(day, 1_000L + index, -60)) }
        assertEquals(FingerprintLevel.CANDIDATE, state!!.level)
    }

    @Test fun thirtyDaysMissingStartsDecayAndCrossPlaceDisablesFeature() {
        val stable = FingerprintState(6, 3, "2026-09-03", 1_000L, -70, -50, FingerprintLevel.STABLE, true)
        val decayed = policy.decay(stable, 1_000L + 31L * 24 * 60 * 60_000)
        assertEquals(FingerprintLevel.DECAYING, decayed.level)
        assertFalse(policy.markCrossPlace(stable).discriminative)
    }

    @Test fun ninetyDaysMissingDisablesFingerprint() {
        val stable = FingerprintState(6, 3, "2026-09-03", 1_000L, -70, -50, FingerprintLevel.STABLE, true)
        val disabled = policy.decay(stable, 1_000L + 91L * 24 * 60 * 60_000)
        assertEquals(FingerprintLevel.DISABLED, disabled.level)
    }

    @Test fun signalRangeTracksMinAndMax() {
        var state: FingerprintState? = null
        state = policy.update(state, LearningSample("2026-09-01", 1_000L, -60))
        state = policy.update(state, LearningSample("2026-09-01", 2_000L, -80))
        state = policy.update(state, LearningSample("2026-09-02", 3_000L, -50))
        assertEquals(-80, state!!.minSignal)
        assertEquals(-50, state!!.maxSignal)
        assertEquals(3, state!!.observationCount)
    }
}
