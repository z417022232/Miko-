package com.example.worktimetracker

import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.journey.EvidenceFreshness
import com.example.worktimetracker.domain.journey.EvidenceHealth
import com.example.worktimetracker.domain.journey.JourneyPhase
import com.example.worktimetracker.domain.journey.RetryState
import com.example.worktimetracker.domain.journey.SamplingContract
import com.example.worktimetracker.domain.journey.SamplingReason
import com.example.worktimetracker.domain.journey.SamplingTier
import com.example.worktimetracker.location.service.SamplingTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采样契约的冻结数值（阶段 3 §5.3 / §5.4 第 7 条）。
 *
 * 这组用例的存在理由是：规格原本只写"urgency 0..1 映射五档""1/2/4 分钟……"，
 * **没有具体数值**，实现者只能现场凑 —— 于是同一个档位在实现里和在文档里不是一回事，
 * 逐档断言也无从下手。数值冻结在这里之后，边界就能一条一条钉住。
 *
 * ⚠️ 测试里的边界值**必须手写字面量**，不能从被实现的表里读 ——
 * 否则改一处数值两处一起变，门槛就是"结构上不可能失败"（本项目踩过的老坑）。
 */
class SamplingContractTest {

    // ---------------------------------------------------------------- 一、urgency → 档位（逐档上下边界）

    @Test
    fun fiveTiersCoverZeroToOneEquidistantly() {
        // 上边界（含）与下边界（不含）：每档各两条，共九条
        assertEquals(SamplingTier.STABLE, SamplingContract.tierFor(0.0))
        assertEquals(SamplingTier.STABLE, SamplingContract.tierFor(0.1999))
        assertEquals(SamplingTier.NORMAL, SamplingContract.tierFor(0.2))
        assertEquals(SamplingTier.NORMAL, SamplingContract.tierFor(0.3999))
        assertEquals(SamplingTier.WATCH, SamplingContract.tierFor(0.4))
        assertEquals(SamplingTier.WATCH, SamplingContract.tierFor(0.5999))
        assertEquals(SamplingTier.TRANSITION, SamplingContract.tierFor(0.6))
        assertEquals(SamplingTier.TRANSITION, SamplingContract.tierFor(0.7999))
        assertEquals(SamplingTier.CRITICAL, SamplingContract.tierFor(0.8))
        // 最高档的上边界是闭区间：1.0 必须是 CRITICAL
        assertEquals(SamplingTier.CRITICAL, SamplingContract.tierFor(1.0))
    }

    @Test
    fun tierOrderIsMonotoneAcrossTheWholeRange() {
        // 千分位步进扫全区间：档位 ordinal 只许不降、且五档都要出现
        val seen = LinkedHashSet<SamplingTier>()
        var previous = SamplingTier.STABLE
        var urgency = 0.0
        while (urgency <= 1.0) {
            val tier = requireNotNull(SamplingContract.tierFor(urgency))
            assertTrue(
                "urgency=$urgency 处档位倒退了：$previous → $tier",
                tier.ordinal >= previous.ordinal
            )
            previous = tier
            seen += tier
            urgency = Math.round((urgency + 0.001) * 1000.0) / 1000.0
        }
        assertEquals(
            "0..1 全区间必须无空洞地覆盖五档",
            SamplingTier.entries.toSet(),
            seen
        )
    }

    @Test
    fun illegalUrgencyFallsBackToConservativeSide() {
        // NaN 不能掉进 CRITICAL：NaN 与任何数比较都是 false，
        // 直接套分段写法会落到最后一个 else（= 最高频采样）—— 方向正好反了
        assertNull(SamplingContract.tierFor(Double.NaN))
        assertNull(SamplingContract.cleanUrgency(Double.NaN))

        assertEquals("负数按 0", 0.0, SamplingContract.cleanUrgency(-0.5)!!, 0.0)
        assertEquals(SamplingTier.STABLE, SamplingContract.tierFor(-0.5))
        assertEquals("大于 1 按 1", 1.0, SamplingContract.cleanUrgency(1.7)!!, 0.0)
        assertEquals(SamplingTier.CRITICAL, SamplingContract.tierFor(1.7))
    }

    // ---------------------------------------------------------------- 二、状态基础紧迫度

    @Test
    fun everyPhaseHasAFrozenBaseUrgency() {
        val expected = mapOf(
            JourneyPhase.AT_HOME to 0.1,
            JourneyPhase.AT_WORK to 0.1,
            JourneyPhase.AWAY to 0.3,
            JourneyPhase.LEAVING_HOME to 0.5,
            JourneyPhase.ARRIVING_HOME to 0.5,
            JourneyPhase.LEAVING_WORK to 0.5,
            JourneyPhase.ARRIVING_WORK to 0.5,
            JourneyPhase.TEMP_LEAVE to 0.5,
            JourneyPhase.OTHER_STOP to 0.5,
            JourneyPhase.COMMUTING_TO_WORK to 0.7,
            JourneyPhase.COMMUTING_HOME to 0.7,
            JourneyPhase.UNKNOWN to 0.9,
            JourneyPhase.STALE to 0.9
        )
        assertEquals("13 个状态一个都不许漏", expected.size, JourneyPhase.entries.size)
        expected.forEach { (phase, urgency) ->
            assertEquals("$phase 的基础紧迫度", urgency, SamplingContract.baseUrgencyOf(phase), 0.0)
        }
    }

    @Test
    fun baseUrgencyRisesWithHowLittleWeKnow() {
        // 稳定在宅/在岗最省 < 无会话在外 < 候选期 < 通勤 < 判不出来
        assertTrue(
            SamplingContract.baseUrgencyOf(JourneyPhase.AT_HOME) <
                SamplingContract.baseUrgencyOf(JourneyPhase.AWAY)
        )
        assertTrue(
            SamplingContract.baseUrgencyOf(JourneyPhase.AWAY) <
                SamplingContract.baseUrgencyOf(JourneyPhase.LEAVING_WORK)
        )
        assertTrue(
            SamplingContract.baseUrgencyOf(JourneyPhase.TEMP_LEAVE) <
                SamplingContract.baseUrgencyOf(JourneyPhase.COMMUTING_HOME)
        )
        assertTrue(
            SamplingContract.baseUrgencyOf(JourneyPhase.COMMUTING_HOME) <
                SamplingContract.baseUrgencyOf(JourneyPhase.UNKNOWN)
        )
        // 基础值一律是合法紧迫度（不可能靠自身越界；只有健康度才能把紧迫度推高）
        JourneyPhase.entries.forEach {
            val urgency = SamplingContract.baseUrgencyOf(it)
            assertTrue("$it 的基础紧迫度越界：$urgency", urgency in 0.0..1.0)
        }
    }

    // ---------------------------------------------------------------- 三、冷却与 CRITICAL 上限

    @Test
    fun cooldownBacksOffOneTwoFourEightThenCapsAtSixteen() {
        assertEquals(1, SamplingContract.cooldownMinutes(0))
        assertEquals(2, SamplingContract.cooldownMinutes(1))
        assertEquals(4, SamplingContract.cooldownMinutes(2))
        assertEquals(8, SamplingContract.cooldownMinutes(3))
        assertEquals(16, SamplingContract.cooldownMinutes(4))
        assertEquals("封顶后不再增长", 16, SamplingContract.cooldownMinutes(5))
        assertEquals(16, SamplingContract.cooldownMinutes(10))
        assertEquals("16 分钟即上限，位移位次与上限必须一致", SamplingContract.COOLDOWN_MAX_MINUTES, SamplingContract.cooldownMinutes(SamplingContract.COOLDOWN_MAX_BACKOFF_SHIFT))
    }

    @Test
    fun cooldownNeverOverflowsOrGoesNegative() {
        // 直接 1 shl attempt 会在 attempt >= 31 时溢出成负数（冷却期算成"过去"= 立刻再进 CRITICAL）
        assertEquals(16, SamplingContract.cooldownMinutes(Int.MAX_VALUE))
        assertEquals(16, SamplingContract.cooldownMinutes(31))
        assertEquals("负数尝试次数按首次退避算", 1, SamplingContract.cooldownMinutes(-3))
        assertTrue(SamplingContract.cooldownMillis(Int.MAX_VALUE) > 0L)
        assertEquals(16 * 60_000L, SamplingContract.cooldownMillis(Int.MAX_VALUE))
    }

    @Test
    fun criticalCapStaysInSyncWithSamplingTuning() {
        // domain 不许反向 import location/service，所以这条"同源"只能靠测试守：
        // Burst 已硬封 10 分钟，CRITICAL 与它同上限（§7 第 10 条）
        assertEquals(
            "CRITICAL 上限与 Burst 硬顶必须同源，改一处必须改另一处",
            SamplingTuning.HARD_BURST_CAP_MINUTES,
            SamplingContract.CRITICAL_MAX_MINUTES
        )
        assertEquals(600_000L, SamplingContract.CRITICAL_MAX_MILLIS)
    }

    // ---------------------------------------------------------------- 四、重试状态的分工

    @Test
    fun criticalStartSurvivesInnerRetriesSoTheTenMinuteCapIsReachable() {
        val entered = RetryState(attempt = 0, lastAttemptAt = NOW, currentCriticalStartedAt = NOW)
        // CRITICAL 内部连续重试：只刷新 lastAttemptAt
        val afterTwoRetries = entered.copy(lastAttemptAt = NOW + 8 * 60_000L)

        assertEquals("起点不许被刷新，否则 10 分钟上限永远到不了", NOW, afterTwoRetries.currentCriticalStartedAt)
        assertEquals(NOW + 8 * 60_000L, afterTwoRetries.lastAttemptAt)
        assertTrue(afterTwoRetries.inCritical)

        // 起点没被刷新 ⇒ 到第 11 分钟这一轮必然会被判超时
        val start = requireNotNull(afterTwoRetries.currentCriticalStartedAt)
        assertTrue("重试 8 分钟后已经算得出 8 分钟", NOW + 8 * 60_000L - start >= 8 * 60_000L)
        assertTrue(
            "上限判定只有在起点不被刷新时才可能成立",
            NOW + 11 * 60_000L - start >= SamplingContract.CRITICAL_MAX_MILLIS
        )
    }

    @Test
    fun leavingCriticalClearsTheStartAndWritesTheEndForAllThreeExits() {
        val inCritical = RetryState(attempt = 0, lastAttemptAt = NOW, currentCriticalStartedAt = NOW - 60_000L)

        // 退出方式一：取得可靠证据 —— 起点清掉、结束时刻写上、失败次数清零
        val succeeded = inCritical.exitCriticalOnSuccess(NOW)
        assertNull(succeeded.currentCriticalStartedAt)
        assertFalse(succeeded.inCritical)
        assertEquals(NOW, succeeded.lastCriticalEndedAt)
        assertEquals(0, succeeded.attempt)

        // 退出方式二：达到时长上限 —— 起点清掉、结束时刻写上、失败次数 +1
        val fromTwoFailures = inCritical.copy(attempt = 2)
        val timedOut = fromTwoFailures.exitCriticalOnTimeout(NOW)
        assertNull(timedOut.currentCriticalStartedAt)
        assertEquals(NOW, timedOut.lastCriticalEndedAt)
        assertEquals(3, timedOut.attempt)

        // 退出方式三：定位不可用 —— 起点清掉、结束时刻**同样要写**，失败数不变
        val unavailable = fromTwoFailures.exitCriticalOnUnavailable(NOW)
        assertNull(unavailable.currentCriticalStartedAt)
        assertEquals("不可用退出不写结束时刻 = 轮次消失却无冷却起点", NOW, unavailable.lastCriticalEndedAt)
        assertEquals("前置条件不满足 ≠ 提供器失败，不污染退避指数", 2, unavailable.attempt)
    }

    @Test
    fun enterCriticalAndNoteAttemptKeepTheStartStable() {
        val idle = RetryState(attempt = 2, lastCriticalEndedAt = NOW - 1)
        val entered = idle.enterCritical(NOW)
        assertEquals(NOW, entered.currentCriticalStartedAt)
        assertEquals(NOW, entered.lastAttemptAt)
        assertEquals("进入不清退避指数（只在退出时改）", 2, entered.attempt)

        val retried = entered.noteAttempt(NOW + 60_000L)
        assertEquals("内部重试只动 lastAttemptAt", NOW, retried.currentCriticalStartedAt)
        assertEquals(NOW + 60_000L, retried.lastAttemptAt)
    }

    // ---------------------------------------------------------------- 五、健康度 → 紧迫度下限（§5.3.2）

    @Test
    fun freshnessDerivationUsesInjectedThresholds() {
        val staleAfter = 20 * 60L
        val reliableAging = 5 * 60L
        // 边界：恰好等于阈值不算越界（>），超过才算
        assertEquals(EvidenceFreshness.FRESH, EvidenceHealth.freshnessOf(0, 0, staleAfter, reliableAging))
        assertEquals(EvidenceFreshness.FRESH, EvidenceHealth.freshnessOf(staleAfter, reliableAging, staleAfter, reliableAging))
        assertEquals(EvidenceFreshness.STALE, EvidenceHealth.freshnessOf(staleAfter + 1, 0, staleAfter, reliableAging))
        assertEquals(EvidenceFreshness.AGING, EvidenceHealth.freshnessOf(0, reliableAging + 1, staleAfter, reliableAging))
        // STALE 优先于 AGING（任意定位断了比可靠定位变旧更严重）
        assertEquals(
            EvidenceFreshness.STALE,
            EvidenceHealth.freshnessOf(staleAfter + 1, reliableAging + 1, staleAfter, reliableAging)
        )
        // 负数秒数按 0：无效输入不凭空制造"断流"
        assertEquals(EvidenceFreshness.FRESH, EvidenceHealth.freshnessOf(-5, -5, staleAfter, reliableAging))
    }

    @Test
    fun healthFloorLeavesFreshConfirmedEvidenceAlone() {
        val floor = SamplingContract.urgencyFloorOf(health())
        assertEquals("全新鲜 + CONFIRMED + 无失败 + 高置信：不提高", 0.0, floor.value, 0.0)
        assertTrue(floor.reasons.isEmpty())
    }

    @Test
    fun healthFloorTableAppliesEachRule() {
        assertEquals(0.8, SamplingContract.urgencyFloorOf(health(freshness = EvidenceFreshness.STALE)).value, 0.0)
        assertEquals(0.4, SamplingContract.urgencyFloorOf(health(freshness = EvidenceFreshness.AGING)).value, 0.0)
        assertEquals(0.4, SamplingContract.urgencyFloorOf(health(decision = FusedDecision.MAINTAINED)).value, 0.0)
        assertEquals(0.8, SamplingContract.urgencyFloorOf(health(decision = FusedDecision.UNKNOWN)).value, 0.0)
        assertEquals(0.4, SamplingContract.urgencyFloorOf(health(failures = 1)).value, 0.0)
        assertEquals(0.8, SamplingContract.urgencyFloorOf(health(failures = 3)).value, 0.0)
        assertEquals(0.4, SamplingContract.urgencyFloorOf(health(confidence = 0.69)).value, 0.0)
        assertEquals(0.6, SamplingContract.urgencyFloorOf(health(confidence = 0.39)).value, 0.0)
        // 边界：恰好 0.7 / 0.4 不触发更高档
        assertEquals(0.0, SamplingContract.urgencyFloorOf(health(confidence = 0.7)).value, 0.0)
        assertEquals(0.4, SamplingContract.urgencyFloorOf(health(confidence = 0.4)).value, 0.0)
    }

    @Test
    fun healthFloorTakesTheMaximumAndMergesReasons() {
        val floor = SamplingContract.urgencyFloorOf(
            health(
                decision = FusedDecision.MAINTAINED,
                confidence = 0.3,
                failures = 3,
                freshness = EvidenceFreshness.AGING
            )
        )
        assertEquals("多条命中取最大（0.8），不是求和也不是平均", 0.8, floor.value, 0.0)
        assertTrue(floor.reasons.containsAll(
            setOf(
                SamplingReason.WEAK_EVIDENCE,
                SamplingReason.LOW_CONFIDENCE,
                SamplingReason.PROVIDER_FAILURES,
                SamplingReason.AGING_EVIDENCE
            )
        ))
    }

    @Test
    fun nanConfidenceGivesNoFloorButPolicyMustFallbackEarlier() {
        // NaN 与任何数比较都是 false ⇒ 本表不给下限；无效拍必须由策略在进表之前整拍兜底。
        // 这条测试钉的是"表不会替调用方兜住 NaN"——谁消费 confidence 谁先验 NaN。
        val floor = SamplingContract.urgencyFloorOf(health(confidence = Double.NaN))
        assertEquals(0.0, floor.value, 0.0)
        assertTrue(floor.reasons.isEmpty())
    }

    @Test
    fun withoutCriticalClampsOnlyCritical() {
        assertEquals(SamplingTier.TRANSITION, SamplingContract.withoutCritical(SamplingTier.CRITICAL))
        assertEquals(SamplingTier.TRANSITION, SamplingContract.withoutCritical(SamplingTier.TRANSITION))
        assertEquals(SamplingTier.WATCH, SamplingContract.withoutCritical(SamplingTier.WATCH))
        assertEquals(SamplingTier.NORMAL, SamplingContract.withoutCritical(SamplingTier.NORMAL))
        assertEquals(SamplingTier.STABLE, SamplingContract.withoutCritical(SamplingTier.STABLE))
    }

    private fun health(
        decision: FusedDecision = FusedDecision.CONFIRMED,
        confidence: Double = 0.9,
        failures: Int = 0,
        freshness: EvidenceFreshness = EvidenceFreshness.FRESH
    ) = EvidenceHealth(
        secondsSinceFix = 0L,
        secondsSinceReliableFix = 0L,
        confidence = confidence,
        placeDecision = decision,
        locationAvailable = true,
        providerFailureStreak = failures,
        freshness = freshness
    )

    private companion object {
        const val NOW: Long = 1_784_000_000_000L
    }
}
