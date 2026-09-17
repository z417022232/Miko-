package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.dao.JourneyShadowStateDao
import com.example.worktimetracker.domain.journey.*

/**
 * 阶段 3 的 IO 编排器。它只恢复/保存影子快照并原样组合两个纯算法产物；
 * 地点、状态转换、候选确认与采样档位判断均不在这里实现。
 */
class JourneyCoordinator(
    private val dao: JourneyShadowStateDao,
    private val reducer: (JourneySnapshot, JourneyObservation, JourneyConfig) -> JourneyTransition =
        JourneyEngine::reduce,
    private val sampler: (JourneySnapshot, EvidenceHealth, Long, RetryState, SamplingTier) -> SamplingDecision =
        AdaptiveSamplingPolicy::decide,
    private val diagnosticLogger: (String) -> Unit = {}
) {
    suspend fun process(
        observation: JourneyObservation,
        config: JourneyConfig,
        health: EvidenceHealth,
        fallbackTier: SamplingTier
    ): JourneyRuntimeDecision {
        val restored = dao.get()?.let { JourneyShadowStateCodec.decode(it, observation.now) }
        val previousSnapshot = restored?.snapshot ?: JourneySnapshot.initial(observation.now)
        val previousRetry = restored?.retry ?: RetryState()

        return try {
            val transition = reducer(previousSnapshot, observation, config)
            val sampling = sampler(
                transition.snapshot,
                health,
                observation.now,
                previousRetry,
                fallbackTier
            )
            dao.upsert(JourneyShadowStateCodec.encode(transition.snapshot, sampling.nextRetryState, observation.now))
            JourneyRuntimeDecision(transition, sampling)
        } catch (error: Throwable) {
            diagnosticLogger("JOURNEY fallback: ${error.message ?: error::class.java.simpleName}")
            fallback(previousSnapshot, previousRetry, fallbackTier)
        }
    }

    private fun fallback(
        snapshot: JourneySnapshot,
        retry: RetryState,
        fallbackTier: SamplingTier
    ): JourneyRuntimeDecision = JourneyRuntimeDecision(
        transition = JourneyTransition(
            snapshot = snapshot,
            confirmedEvents = emptyList(),
            reasonCodes = setOf(JourneyReason.ENGINE_FAILED),
            explanation = "新行程状态机本拍执行失败，已保持原状态并回落既有采样策略"
        ),
        sampling = SamplingDecision(
            tier = fallbackTier,
            urgency = SamplingContract.baseUrgencyOf(snapshot.phase),
            reasonCodes = setOf(SamplingReason.FALLBACK_ENGINE_FAILED),
            expiresAt = null,
            cooldownUntil = null,
            nextRetryState = retry,
            fallbackApplied = true
        )
    )

    companion object {
        const val MODEL_VERSION: Long = JourneyShadowStateCodec.MODEL_VERSION
    }
}
