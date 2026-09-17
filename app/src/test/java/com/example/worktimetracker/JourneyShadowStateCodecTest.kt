package com.example.worktimetracker

import com.example.worktimetracker.data.entity.JourneyShadowStateEntity
import com.example.worktimetracker.domain.evidence.EvidenceSource
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.journey.JourneyCandidate
import com.example.worktimetracker.domain.journey.JourneyPhase
import com.example.worktimetracker.domain.journey.JourneySnapshot
import com.example.worktimetracker.domain.journey.RetryState
import com.example.worktimetracker.location.service.JourneyShadowStateCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JourneyShadowStateCodecTest {
    private val now = 1_800_000_000_000L

    @Test
    fun fullRoundTripPreservesCandidateAndRetryMemory() {
        val snapshot = JourneySnapshot(
            phase = JourneyPhase.LEAVING_WORK,
            candidate = JourneyCandidate(
                targetPhase = JourneyPhase.TEMP_LEAVE,
                firstObservedAt = now - 5_000,
                lastSupportedAt = now - 1_000,
                supportCount = 4,
                accumulatedStableMillis = 4_000,
                evidenceSources = setOf(EvidenceSource.WIFI, EvidenceSource.GNSS),
                strongestDecision = FusedDecision.CONFIRMED,
                confidence = 0.91,
                lastUnsupportedAt = now - 3_000
            ),
            lastConfirmedPhase = JourneyPhase.AT_WORK,
            lastTransitionAt = now - 5_000
        )
        val retry = RetryState(2, now - 100, now - 10_000, now - 20_000)
        val row = JourneyShadowStateCodec.encode(snapshot, retry, now)
        val restored = JourneyShadowStateCodec.decode(row, now)

        assertEquals(snapshot, restored?.snapshot)
        assertEquals(retry, restored?.retry)
    }

    @Test
    fun unknownPersistedEnumResetsInsteadOfGuessing() {
        val row = minimal().copy(phase = "FUTURE_PHASE")
        assertNull(JourneyShadowStateCodec.decode(row, now))
    }

    @Test
    fun timeRollbackDropsCandidateButPreservesConfirmedPhase() {
        val row = minimal().copy(
            phase = JourneyPhase.LEAVING_WORK.name,
            candidatePhase = JourneyPhase.TEMP_LEAVE.name,
            firstObservedAt = now - 100,
            lastSupportedAt = now - 50,
            supportCount = 2,
            accumulatedStableMillis = 50,
            candidateEvidenceSources = EvidenceSource.GNSS.name,
            candidateStrongestDecision = FusedDecision.CONFIRMED.name,
            candidateConfidence = 0.9,
            lastConfirmedPhase = JourneyPhase.AT_WORK.name,
            updatedAt = now + 1
        )
        val restored = JourneyShadowStateCodec.decode(row, now)
        assertEquals(JourneyPhase.UNKNOWN, restored?.snapshot?.phase)
        assertEquals(JourneyPhase.AT_WORK, restored?.snapshot?.lastConfirmedPhase)
        assertNull(restored?.snapshot?.candidate)
        assertEquals(RetryState(), restored?.retry)
    }

    @Test
    fun modelVersionMismatchReturnsNoRestorableState() {
        assertNull(JourneyShadowStateCodec.decode(minimal().copy(modelVersion = 999), now))
    }

    private fun minimal() = JourneyShadowStateEntity(
        phase = JourneyPhase.AT_HOME.name,
        candidatePhase = null,
        firstObservedAt = null,
        lastSupportedAt = null,
        candidateLastUnsupportedAt = null,
        supportCount = 0,
        accumulatedStableMillis = 0,
        candidateEvidenceSources = null,
        candidateStrongestDecision = null,
        candidateConfidence = null,
        lastConfirmedPhase = JourneyPhase.AT_HOME.name,
        lastTransitionAt = now - 1,
        samplingAttempt = 0,
        samplingLastAttemptAt = null,
        samplingCriticalStartedAt = null,
        samplingLastCriticalEndedAt = null,
        modelVersion = JourneyShadowStateCodec.MODEL_VERSION,
        updatedAt = now - 1
    )
}
