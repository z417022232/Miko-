package com.example.worktimetracker.domain.journey

import com.example.worktimetracker.domain.evidence.FusedDecision
import kotlin.math.max

/**
 * 行程状态、证据健康和重试记忆到采样档位的纯映射。
 *
 * 本对象不读取时钟、Room、Android 或定位服务；[now] 和全部跨拍记忆均由调用方显式传入。
 * 数值契约集中在 [SamplingContract]，这里仅负责编排已经冻结的规则。
 */
object AdaptiveSamplingPolicy {

    fun decide(
        nextSnapshot: JourneySnapshot,
        health: EvidenceHealth,
        now: Long,
        retry: RetryState,
        fallbackTier: SamplingTier
    ): SamplingDecision {
        val baseUrgency = SamplingContract.baseUrgencyOf(nextSnapshot.phase)

        // NaN 是整拍无效，不让健康度表用比较恒 false 的方式静默吞掉。
        if (health.confidence.isNaN()) {
            return fallback(
                tier = fallbackTier,
                urgency = baseUrgency,
                retry = retry,
                reasons = setOf(SamplingReason.FALLBACK_ENGINE_FAILED)
            )
        }

        val floor = SamplingContract.urgencyFloorOf(health)
        val urgency = requireNotNull(SamplingContract.cleanUrgency(max(baseUrgency, floor.value)))
        val requestedTier = requireNotNull(SamplingContract.tierFor(urgency))
        val requestedReasons = reasonsFor(nextSnapshot.phase) + floor.reasons

        // 权限/系统开关关闭时，高频请求不会产生证据。即使 fallback 本身是 CRITICAL 也要压掉。
        if (!health.locationAvailable) {
            val nextRetry = if (retry.inCritical) retry.exitCriticalOnUnavailable(now) else retry
            val tier = SamplingContract.withoutCritical(fallbackTier)
            val cooldownUntil = nextRetry.lastCriticalEndedAt?.let {
                safeAdd(it, SamplingContract.cooldownMillis(nextRetry.attempt))
            }
            return fallback(
                tier = tier,
                urgency = urgency,
                retry = nextRetry,
                reasons = requestedReasons + SamplingReason.LOCATION_UNAVAILABLE,
                cooldownUntil = cooldownUntil
            )
        }

        // 一旦取得可靠证据，当前 CRITICAL 轮立即成功结束；本拍不得借 fallback 再进 CRITICAL。
        if (retry.inCritical && health.placeDecision == FusedDecision.CONFIRMED) {
            val nextRetry = retry.exitCriticalOnSuccess(now)
            val baseTier = requireNotNull(SamplingContract.tierFor(baseUrgency))
            val tier = SamplingContract.withoutCritical(denserOf(baseTier, fallbackTier))
            return SamplingDecision(
                tier = tier,
                urgency = baseUrgency,
                reasonCodes = reasonsFor(nextSnapshot.phase),
                expiresAt = null,
                cooldownUntil = safeAdd(now, SamplingContract.cooldownMillis(nextRetry.attempt)),
                nextRetryState = nextRetry,
                fallbackApplied = tier != baseTier
            )
        }

        if (retry.inCritical) {
            val startedAt = requireNotNull(retry.currentCriticalStartedAt)
            val expiresAt = safeAdd(startedAt, SamplingContract.CRITICAL_MAX_MILLIS)
            if (now >= expiresAt) {
                val nextRetry = retry.exitCriticalOnTimeout(now)
                val cooldownUntil = safeAdd(now, SamplingContract.cooldownMillis(nextRetry.attempt))
                return fallback(
                    tier = SamplingContract.withoutCritical(fallbackTier),
                    urgency = urgency,
                    retry = nextRetry,
                    reasons = requestedReasons +
                        SamplingReason.CRITICAL_TIMEOUT +
                        SamplingReason.RETRY_BACKOFF,
                    cooldownUntil = cooldownUntil
                )
            }

            return SamplingDecision(
                tier = SamplingTier.CRITICAL,
                urgency = urgency,
                reasonCodes = requestedReasons,
                expiresAt = expiresAt,
                cooldownUntil = null,
                nextRetryState = retry.noteAttempt(now),
                fallbackApplied = false
            )
        }

        val activeCooldownUntil = retry.lastCriticalEndedAt?.let {
            safeAdd(it, SamplingContract.cooldownMillis(retry.attempt))
        }?.takeIf { now < it }

        if (requestedTier == SamplingTier.CRITICAL && activeCooldownUntil != null) {
            return fallback(
                tier = SamplingContract.withoutCritical(fallbackTier),
                urgency = urgency,
                retry = retry,
                reasons = requestedReasons + SamplingReason.COOLDOWN + SamplingReason.RETRY_BACKOFF,
                cooldownUntil = activeCooldownUntil
            )
        }

        val tier = denserOf(requestedTier, fallbackTier)
        if (tier == SamplingTier.CRITICAL) {
            val nextRetry = retry.enterCritical(now)
            return SamplingDecision(
                tier = SamplingTier.CRITICAL,
                urgency = urgency,
                reasonCodes = requestedReasons,
                expiresAt = safeAdd(now, SamplingContract.CRITICAL_MAX_MILLIS),
                cooldownUntil = null,
                nextRetryState = nextRetry,
                fallbackApplied = fallbackTier.ordinal > requestedTier.ordinal
            )
        }

        return SamplingDecision(
            tier = tier,
            urgency = urgency,
            reasonCodes = requestedReasons,
            expiresAt = null,
            cooldownUntil = activeCooldownUntil,
            nextRetryState = retry,
            fallbackApplied = fallbackTier.ordinal > requestedTier.ordinal
        )
    }

    private fun fallback(
        tier: SamplingTier,
        urgency: Double,
        retry: RetryState,
        reasons: Set<SamplingReason>,
        cooldownUntil: Long? = null
    ): SamplingDecision = SamplingDecision(
        tier = tier,
        urgency = urgency,
        reasonCodes = reasons,
        expiresAt = null,
        cooldownUntil = cooldownUntil,
        nextRetryState = retry,
        fallbackApplied = true
    )

    private fun denserOf(first: SamplingTier, second: SamplingTier): SamplingTier =
        if (first.ordinal >= second.ordinal) first else second

    private fun reasonsFor(phase: JourneyPhase): Set<SamplingReason> = when (phase) {
        JourneyPhase.AT_HOME, JourneyPhase.AT_WORK -> setOf(SamplingReason.STABLE_PLACE)
        JourneyPhase.AWAY -> setOf(SamplingReason.BASELINE)
        JourneyPhase.LEAVING_HOME, JourneyPhase.ARRIVING_HOME,
        JourneyPhase.LEAVING_WORK, JourneyPhase.ARRIVING_WORK,
        JourneyPhase.TEMP_LEAVE, JourneyPhase.OTHER_STOP -> setOf(SamplingReason.CANDIDATE_PENDING)
        JourneyPhase.COMMUTING_TO_WORK, JourneyPhase.COMMUTING_HOME -> setOf(SamplingReason.COMMUTING)
        JourneyPhase.UNKNOWN, JourneyPhase.STALE -> setOf(SamplingReason.STALE_WINDOW)
    }

    private fun safeAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
}
