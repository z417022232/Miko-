package com.example.worktimetracker

import com.example.worktimetracker.data.dao.JourneyShadowStateDao
import com.example.worktimetracker.data.entity.JourneyShadowStateEntity
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.domain.journey.*
import com.example.worktimetracker.location.service.JourneyCoordinator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyCoordinatorTest {
    private val now = 1_800_000_000_000L
    private val config = JourneyConfig(1_200, 60_000, 60_000, 1_200_000, 3_600_000, 300)

    @Test
    fun combinesPureOutputsAndPersistsCompleteNextState() = runTest {
        val dao = FakeDao()
        val nextSnapshot = JourneySnapshot(JourneyPhase.AT_HOME, null, JourneyPhase.AT_HOME, now)
        val transition = JourneyTransition(nextSnapshot, emptyList(), setOf(JourneyReason.PLACE_CONFIRMED), "在家")
        val sampling = SamplingDecision(
            SamplingTier.STABLE, 0.1, setOf(SamplingReason.STABLE_PLACE), null, null,
            RetryState(), false
        )
        val coordinator = JourneyCoordinator(
            dao = dao,
            reducer = { _, _, _ -> transition },
            sampler = { _, _, _, _, _ -> sampling }
        )

        val result = coordinator.process(observation(), config, health(), SamplingTier.NORMAL)

        assertTrue(result.transition === transition)
        assertTrue(result.sampling === sampling)
        assertEquals(JourneyPhase.AT_HOME.name, dao.row?.phase)
        assertEquals(JourneyPhase.AT_HOME.name, dao.row?.lastConfirmedPhase)
        assertEquals(JourneyCoordinator.MODEL_VERSION, dao.row?.modelVersion)
    }

    @Test
    fun reducerFailureFallsBackWithoutTouchingFormalState() = runTest {
        val dao = FakeDao()
        val logs = mutableListOf<String>()
        val coordinator = JourneyCoordinator(
            dao = dao,
            reducer = { _, _, _ -> error("boom") },
            diagnosticLogger = { logs += it }
        )

        val result = coordinator.process(observation(), config, health(), SamplingTier.WATCH)

        assertEquals(JourneyPhase.UNKNOWN, result.transition.snapshot.phase)
        assertTrue(result.transition.reasonCodes.contains(JourneyReason.ENGINE_FAILED))
        assertEquals(SamplingTier.WATCH, result.sampling.tier)
        assertTrue(result.sampling.fallbackApplied)
        assertTrue(result.sampling.reasonCodes.contains(SamplingReason.FALLBACK_ENGINE_FAILED))
        assertTrue(logs.single().contains("boom"))
    }

    private fun observation() = JourneyObservation(
        now, ResolvedPlace.HOME, FusedDecision.CONFIRMED, 0.9, emptySet(),
        MotionPhase.STATIONARY, now, 0, false, 1.0, 100.0
    )

    private fun health() = EvidenceHealth(
        0, 0, 0.9, FusedDecision.CONFIRMED, true, 0, EvidenceFreshness.FRESH
    )

    private class FakeDao : JourneyShadowStateDao {
        var row: JourneyShadowStateEntity? = null
        override suspend fun get(): JourneyShadowStateEntity? = row
        override suspend fun upsert(state: JourneyShadowStateEntity) { row = state }
        override suspend fun clear() { row = null }
    }
}
