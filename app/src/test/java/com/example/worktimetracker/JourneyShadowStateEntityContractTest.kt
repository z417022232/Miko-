package com.example.worktimetracker

import com.example.worktimetracker.data.entity.JourneyShadowStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/** DB v17 单行影子快照的结构契约。字段缺失会让重启恢复丢语义。 */
class JourneyShadowStateEntityContractTest {

    @Test
    fun entityCarriesCompleteJourneyCandidateAndRetryMemory() {
        val fields = JourneyShadowStateEntity::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name }
            .toSet()

        val expected = setOf(
            "id",
            "phase",
            "candidatePhase",
            "firstObservedAt",
            "lastSupportedAt",
            "candidateLastUnsupportedAt",
            "supportCount",
            "accumulatedStableMillis",
            "candidateEvidenceSources",
            "candidateStrongestDecision",
            "candidateConfidence",
            "lastConfirmedPhase",
            "lastTransitionAt",
            "samplingAttempt",
            "samplingLastAttemptAt",
            "samplingCriticalStartedAt",
            "samplingLastCriticalEndedAt",
            "modelVersion",
            "updatedAt"
        )
        assertEquals(expected, fields)
    }

    @Test
    fun retryMemoryIsNotOptionalAtTheSchemaLevel() {
        val row = JourneyShadowStateEntity(
            phase = "STALE",
            candidatePhase = null,
            firstObservedAt = null,
            lastSupportedAt = null,
            candidateLastUnsupportedAt = null,
            supportCount = 0,
            accumulatedStableMillis = 0,
            candidateEvidenceSources = null,
            candidateStrongestDecision = null,
            candidateConfidence = null,
            lastConfirmedPhase = "AT_WORK",
            lastTransitionAt = 100,
            samplingAttempt = 3,
            samplingLastAttemptAt = 101,
            samplingCriticalStartedAt = 90,
            samplingLastCriticalEndedAt = null,
            modelVersion = 1,
            updatedAt = 102
        )
        assertEquals(1, row.id)
        assertEquals(3, row.samplingAttempt)
        assertTrue(row.samplingCriticalStartedAt!! < row.samplingLastAttemptAt!!)
    }
}
