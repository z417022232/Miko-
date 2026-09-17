package com.example.worktimetracker

import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.journey.AdaptiveSamplingPolicy
import com.example.worktimetracker.domain.journey.EvidenceFreshness
import com.example.worktimetracker.domain.journey.EvidenceHealth
import com.example.worktimetracker.domain.journey.JourneyPhase
import com.example.worktimetracker.domain.journey.JourneySnapshot
import com.example.worktimetracker.domain.journey.RetryState
import com.example.worktimetracker.domain.journey.SamplingContract
import com.example.worktimetracker.domain.journey.SamplingDecision
import com.example.worktimetracker.domain.journey.SamplingReason
import com.example.worktimetracker.domain.journey.SamplingTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 3 第 3 步：`AdaptiveSamplingPolicy` 行为测试。
 *
 * 覆盖面按 §5.4 的验收条目排：
 * - §5.3 四条 CRITICAL 契约（限时 / 即时退出 / 冷却退避 / 权限关闭不强制）各有独立用例；
 * - §5.3.2 健康度下限表的每一条规则都要真的抬档（保留字段却静默忽略 = 没实现）；
 * - 两条硬覆盖的**反例**：`fallbackTier = CRITICAL` 也拦得住（否则整条契约可被调用方绕过）；
 * - 冷却的**一致性**：本拍报告的 `cooldownUntil` 与下一拍从 `RetryState` 重算的必须相等。
 */
class AdaptiveSamplingPolicyTest {

    private val now = 1_800_000_000_000L

    /** 干净健康度：FRESH + CONFIRMED + 无失败 + 高置信 —— 不提高紧迫度。 */
    private fun health(
        decision: FusedDecision = FusedDecision.CONFIRMED,
        confidence: Double = 0.9,
        available: Boolean = true,
        failures: Int = 0,
        freshness: EvidenceFreshness = EvidenceFreshness.FRESH
    ) = EvidenceHealth(
        secondsSinceFix = 0L,
        secondsSinceReliableFix = 0L,
        confidence = confidence,
        placeDecision = decision,
        locationAvailable = available,
        providerFailureStreak = failures,
        freshness = freshness
    )

    private fun snapshot(phase: JourneyPhase) = JourneySnapshot(phase, null, phase, now - 1)

    // ---------------------------------------------------------------- 基础映射与方向约束

    @Test
    fun mapsEveryPhaseThroughTheFrozenContract() {
        JourneyPhase.entries.forEach { phase ->
            val result = decide(snapshot(phase), health(), fallbackTier = SamplingTier.STABLE)
            assertEquals(
                phase.name,
                SamplingContract.tierFor(SamplingContract.baseUrgencyOf(phase)),
                result.tier
            )
        }
    }

    @Test
    fun neverSamplesBelowFallbackTier() {
        val result = decide(snapshot(JourneyPhase.AT_HOME), health(), fallbackTier = SamplingTier.TRANSITION)
        assertEquals(SamplingTier.TRANSITION, result.tier)
        assertTrue(result.fallbackApplied)
    }

    // ---------------------------------------------------------------- 硬覆盖一：定位不可用

    @Test
    fun unavailableLocationHardOverridesCritical() {
        val result = decide(snapshot(JourneyPhase.STALE), health(available = false), fallbackTier = SamplingTier.NORMAL)
        assertEquals(SamplingTier.NORMAL, result.tier)
        assertTrue(result.reasonCodes.contains(SamplingReason.LOCATION_UNAVAILABLE))
        assertTrue(result.fallbackApplied)
        assertNull(result.nextRetryState.currentCriticalStartedAt)
    }

    @Test
    fun unavailableLocationClampsEvenACriticalFallbackTier() {
        // P0 回归：调用方传 fallbackTier = CRITICAL 也不许绕过「不可用不得 CRITICAL」
        val result = decide(
            snapshot(JourneyPhase.STALE),
            health(available = false),
            fallbackTier = SamplingTier.CRITICAL
        )
        assertEquals(SamplingTier.TRANSITION, result.tier)
        assertTrue(result.reasonCodes.contains(SamplingReason.LOCATION_UNAVAILABLE))
    }

    @Test
    fun unavailableExitClosesTheCriticalRoundWithEndTimestampAndNoAttemptBump() {
        // P1 回归：不可用退出必须写结束时刻，且 attempt 不变（前置条件不满足 ≠ 提供器失败）
        val inCritical = RetryState(attempt = 2, lastAttemptAt = now - 1, currentCriticalStartedAt = now - 60_000L)
        val result = decide(snapshot(JourneyPhase.STALE), health(available = false), retry = inCritical)
        val next = result.nextRetryState
        assertNull("轮次必须结束", next.currentCriticalStartedAt)
        assertEquals("结束时刻是冷却起点，不许丢", now, next.lastCriticalEndedAt)
        assertEquals("不可用不增加失败数", 2, next.attempt)
    }

    // ---------------------------------------------------------------- 硬覆盖二：取得可靠证据

    @Test
    fun confirmedEvidenceImmediatelyExitsCritical() {
        val inCritical = RetryState(attempt = 3, lastAttemptAt = now - 1, currentCriticalStartedAt = now - 1)
        val result = decide(snapshot(JourneyPhase.AT_HOME), health(), retry = inCritical)
        assertEquals(SamplingTier.NORMAL, result.tier)
        assertEquals(0, result.nextRetryState.attempt)
        assertNull(result.nextRetryState.currentCriticalStartedAt)
        assertEquals(now, result.nextRetryState.lastCriticalEndedAt)
    }

    @Test
    fun confirmedExitNeverStaysCriticalEvenWithCriticalFallback() {
        val inCritical = RetryState(attempt = 1, lastAttemptAt = now - 1, currentCriticalStartedAt = now - 1)
        val result = decide(
            snapshot(JourneyPhase.UNKNOWN),   // 基础档本身是 CRITICAL 的状态
            health(),
            retry = inCritical,
            fallbackTier = SamplingTier.CRITICAL
        )
        assertTrue("刚取得可靠点，退出路径不得再是 CRITICAL", result.tier != SamplingTier.CRITICAL)
        assertEquals(SamplingTier.TRANSITION, result.tier)
    }

    // ---------------------------------------------------------------- 契约 1：限时

    @Test
    fun entersCriticalWithTenMinuteExpiry() {
        val result = decide(snapshot(JourneyPhase.STALE), health())
        assertEquals(SamplingTier.CRITICAL, result.tier)
        assertEquals(now + 10 * 60_000L, result.expiresAt)
        assertEquals(now, result.nextRetryState.currentCriticalStartedAt)
        assertEquals(now, result.nextRetryState.lastAttemptAt)
    }

    @Test
    fun internalRetryDoesNotRefreshCriticalStart() {
        val started = now - 60_000L
        val result = decide(
            snapshot(JourneyPhase.STALE),
            health(decision = FusedDecision.UNKNOWN, failures = 2),
            retry = RetryState(attempt = 0, lastAttemptAt = now - 30_000L, currentCriticalStartedAt = started)
        )
        assertEquals(started, result.nextRetryState.currentCriticalStartedAt)
        assertEquals(now, result.nextRetryState.lastAttemptAt)
        assertEquals(started + 10 * 60_000L, result.expiresAt)
    }

    @Test
    fun criticalRoundOnlyEndsThroughTheThreeExits() {
        // 状态需求消失（AT_WORK 基础值 0.1）不算出口：轮内维持 CRITICAL，由 10 分钟上限兜底。
        // 注意决策不能是 CONFIRMED（那是出口二），用 MAINTAINED 只抬到 WATCH、不退出。
        val inCritical = RetryState(currentCriticalStartedAt = now - 60_000L)
        val result = decide(snapshot(JourneyPhase.AT_WORK), health(decision = FusedDecision.MAINTAINED), retry = inCritical)
        assertEquals(SamplingTier.CRITICAL, result.tier)
        assertNotNull(result.nextRetryState.currentCriticalStartedAt)
    }

    // ---------------------------------------------------------------- 契约 3：冷却 + 指数退避

    /** 超时路径用的健康度：不能是 CONFIRMED（那是「即时退出」，会抢在超时判定之前）。 */
    private fun stillSearching() = health(decision = FusedDecision.UNKNOWN)

    @Test
    fun timeoutClosesTheRoundAndStartsCooldownFromTheNewAttempt() {
        val inCritical = RetryState(currentCriticalStartedAt = now - 10 * 60_000L)
        val result = decide(snapshot(JourneyPhase.STALE), stillSearching(), retry = inCritical)
        assertEquals(SamplingTier.NORMAL, result.tier)
        assertTrue(result.reasonCodes.contains(SamplingReason.CRITICAL_TIMEOUT))
        assertEquals(1, result.nextRetryState.attempt)
        assertNull(result.nextRetryState.currentCriticalStartedAt)
        assertEquals(now, result.nextRetryState.lastCriticalEndedAt)
        // 冷却按退出后的 attempt（1 → 2 分钟），与本拍报告值一致
        assertEquals(now + 2 * 60_000L, result.cooldownUntil)
    }

    @Test
    fun timeoutBackoffGrowsTwoFourEightSixteenAndCaps() {
        listOf(0 to 2, 1 to 4, 2 to 8, 3 to 16, 4 to 16, 30 to 16).forEach { (attempt, minutes) ->
            val result = decide(
                snapshot(JourneyPhase.STALE),
                stillSearching(),
                retry = RetryState(attempt = attempt, currentCriticalStartedAt = now - 10 * 60_000L)
            )
            assertEquals(attempt.toString(), now + minutes * 60_000L, result.cooldownUntil)
            assertTrue(result.tier != SamplingTier.CRITICAL)
        }
    }

    @Test
    fun successExitCooldownIsOneMinute() {
        val inCritical = RetryState(attempt = 2, currentCriticalStartedAt = now - 1)
        val result = decide(snapshot(JourneyPhase.AT_HOME), health(), retry = inCritical)
        // 成功退出 attempt 清 0 ⇒ 冷却 1 分钟（表中 1 分钟只出现在这条路径）
        assertEquals(now + 1 * 60_000L, result.cooldownUntil)
    }

    @Test
    fun activeCooldownBlocksCriticalReentry() {
        val result = decide(
            snapshot(JourneyPhase.STALE),
            health(),
            retry = RetryState(attempt = 2, lastCriticalEndedAt = now - 60_000L)
        )
        assertEquals(SamplingTier.NORMAL, result.tier)
        assertTrue(result.reasonCodes.contains(SamplingReason.COOLDOWN))
        assertEquals(now - 60_000L + 4 * 60_000L, result.cooldownUntil)
    }

    @Test
    fun cooldownBlocksEvenACriticalFallbackTier() {
        val result = decide(
            snapshot(JourneyPhase.STALE),
            health(),
            retry = RetryState(attempt = 2, lastCriticalEndedAt = now - 60_000L),
            fallbackTier = SamplingTier.CRITICAL
        )
        assertTrue("冷却期内不得进入 CRITICAL，兜底档是 CRITICAL 也不行", result.tier != SamplingTier.CRITICAL)
        assertTrue(result.reasonCodes.contains(SamplingReason.COOLDOWN))
    }

    @Test
    fun reportedCooldownMatchesWhatTheNextBeatEnforces() {
        // 一致性：本拍报告的 cooldownUntil 与下一拍从 RetryState 重算的必须相等（两处真相不许漂移）
        val timedOut = decide(
            snapshot(JourneyPhase.STALE),
            stillSearching(),
            retry = RetryState(attempt = 0, currentCriticalStartedAt = now - 10 * 60_000L)
        )
        val duringCooldown = decide(
            snapshot(JourneyPhase.STALE),
            stillSearching(),
            now = now + 60_000L,
            retry = timedOut.nextRetryState
        )
        assertEquals(timedOut.cooldownUntil, duringCooldown.cooldownUntil)
        assertTrue(duringCooldown.reasonCodes.contains(SamplingReason.COOLDOWN))

        val afterCooldown = decide(
            snapshot(JourneyPhase.STALE),
            stillSearching(),
            now = timedOut.cooldownUntil!! + 1,
            retry = timedOut.nextRetryState
        )
        assertEquals("冷却到期后 CRITICAL 请求恢复", SamplingTier.CRITICAL, afterCooldown.tier)
    }

    // ---------------------------------------------------------------- §5.3.2 健康度下限表逐条落地

    @Test
    fun staleFreshnessRaisesToCritical() {
        val result = decide(snapshot(JourneyPhase.AT_HOME), health(freshness = EvidenceFreshness.STALE))
        assertEquals(SamplingTier.CRITICAL, result.tier)
        assertTrue(result.reasonCodes.contains(SamplingReason.STALE_WINDOW))
    }

    @Test
    fun agingFreshnessRaisesToWatch() {
        val result = decide(snapshot(JourneyPhase.AT_HOME), health(freshness = EvidenceFreshness.AGING))
        assertEquals(SamplingTier.WATCH, result.tier)
        assertTrue(result.reasonCodes.contains(SamplingReason.AGING_EVIDENCE))
    }

    @Test
    fun maintainedDecisionRaisesToWatch() {
        val result = decide(snapshot(JourneyPhase.AT_HOME), health(decision = FusedDecision.MAINTAINED))
        assertEquals(SamplingTier.WATCH, result.tier)
        assertTrue(result.reasonCodes.contains(SamplingReason.WEAK_EVIDENCE))
    }

    @Test
    fun unresolvedPlaceRaisesToCritical() {
        val result = decide(snapshot(JourneyPhase.AT_HOME), health(decision = FusedDecision.UNKNOWN))
        assertEquals(SamplingTier.CRITICAL, result.tier)
        assertTrue(result.reasonCodes.contains(SamplingReason.UNRESOLVED_PLACE))
    }

    @Test
    fun providerFailureStreakRaisesWatchThenCritical() {
        val one = decide(snapshot(JourneyPhase.AT_HOME), health(failures = 1))
        assertEquals(SamplingTier.WATCH, one.tier)
        assertTrue(one.reasonCodes.contains(SamplingReason.PROVIDER_FAILURES))

        val three = decide(snapshot(JourneyPhase.AT_HOME), health(failures = 3))
        assertEquals(SamplingTier.CRITICAL, three.tier)
    }

    @Test
    fun lowConfidenceRaisesWatchThenTransition() {
        assertEquals(
            SamplingTier.WATCH,
            decide(snapshot(JourneyPhase.AT_HOME), health(confidence = 0.5)).tier
        )
        assertEquals(
            SamplingTier.TRANSITION,
            decide(snapshot(JourneyPhase.AT_HOME), health(confidence = 0.3)).tier
        )
    }

    @Test
    fun healthOnlyRaisesUrgencyNeverLowersIt() {
        // 高置信 + 全新鲜不产生任何下限：紧迫度就等于状态基础值，不会被"拉低"也不会被抬高
        val result = decide(snapshot(JourneyPhase.UNKNOWN), health(confidence = 0.99))
        assertEquals(SamplingContract.baseUrgencyOf(JourneyPhase.UNKNOWN), result.urgency, 0.0)
        assertEquals(SamplingTier.CRITICAL, result.tier)

        // 健康度只提高：AT_WORK(0.1) + STALE 新鲜度(0.8) ⇒ 0.8，而不是 0.8-什么的折中
        val raised = decide(snapshot(JourneyPhase.AT_WORK), health(freshness = EvidenceFreshness.STALE))
        assertEquals(0.8, raised.urgency, 0.0)
    }

    // ---------------------------------------------------------------- 非法输入

    @Test
    fun nanConfidenceFallsBackForTheWholeBeat() {
        val result = decide(snapshot(JourneyPhase.AT_HOME), health(confidence = Double.NaN), fallbackTier = SamplingTier.WATCH)
        assertEquals(SamplingTier.WATCH, result.tier)
        assertTrue(result.fallbackApplied)
        assertTrue(result.reasonCodes.contains(SamplingReason.FALLBACK_ENGINE_FAILED))
    }

    // ---------------------------------------------------------------- 辅助

    private fun decide(
        snapshot: JourneySnapshot,
        health: EvidenceHealth,
        now: Long = this.now,
        retry: RetryState = RetryState(),
        fallbackTier: SamplingTier = SamplingTier.NORMAL
    ): SamplingDecision = AdaptiveSamplingPolicy.decide(snapshot, health, now, retry, fallbackTier)
}
