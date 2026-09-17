package com.example.worktimetracker

import com.example.worktimetracker.domain.location.AnchorLearner
import com.example.worktimetracker.domain.location.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锚点自动学习器（方案 §三.2）。
 *
 * 这组测试守的是两件事：
 *  1. **门槛一个都不许松** —— 少一个样本/少一天/少一类环境来源都必须拒绝，
 *     因为自动学习失败没人知道，只有 UI 和日志能说清「还差什么」；
 *  2. **拒绝必须说人话** —— 每个 `Insufficient` 都要带具体数字，
 *     否则功能对用户就是黑盒（违反原则 6「每次判定必须能解释依据」）。
 */
class AnchorLearnerTest {

    private val learner = AnchorLearner()

    /** 达标基线：12 个点 / 6 天 / 稳定 30 分钟 / 2 类环境来源。 */
    private fun learnableSamples(count: Int = 12, days: Int = 6, jitter: Double = 0.0) =
        (0 until count).map { i ->
            sample(day = i % days + 1, lat = BASE_LAT + jitter * (i % days))
        }

    @Test fun meetsEveryGateFormsCandidate() {
        val outcome = learner.learn(
            samples = learnableSamples(),
            ambientSourceCount = 2,
            stableMillis = 30 * 60_000L,
            configuredAnchor = GeoPoint(BASE_LAT, BASE_LNG)
        )
        val found = outcome as? AnchorLearner.Outcome.Found
        assertNotNull("达标样本必须形成候选", found)
        assertEquals(12, found!!.candidate.sampleCount)
        assertEquals(6, found.candidate.distinctDayCount)
        assertEquals(0.0, found.candidate.offsetMeters, 1.0)
    }

    @Test fun noSamplesSaysSoExplicitly() {
        val outcome = learner.learn(emptyList(), 2, 30 * 60_000L)
        assertEquals(
            AnchorLearner.Reason.NO_SAMPLES,
            (outcome as AnchorLearner.Outcome.Insufficient).reason
        )
    }

    @Test fun tooFewPointsReportsTheRealNumber() {
        val outcome = learner.learn(learnableSamples(count = 5), 2, 30 * 60_000L)
        val reason = (outcome as AnchorLearner.Outcome.Insufficient)
        assertEquals(AnchorLearner.Reason.TOO_FEW_POINTS, reason.reason)
        assertTrue("原因里必须带实际样本数，文案=${reason.detail}", reason.detail.contains("5"))
    }

    @Test fun tooFewDaysIsRejectedEvenWithManyPoints() {
        // 12 个点全挤在 2 天里 —— 点数够、天数不够，绝不能因为点多就放行
        val outcome = learner.learn(learnableSamples(count = 12, days = 2), 2, 30 * 60_000L)
        val reason = (outcome as AnchorLearner.Outcome.Insufficient)
        assertEquals(AnchorLearner.Reason.TOO_FEW_DAYS, reason.reason)
        assertTrue(reason.detail.contains("2"))
    }

    @Test fun insufficientStableTimeIsRejected() {
        val outcome = learner.learn(learnableSamples(), 2, 10 * 60_000L)
        val reason = (outcome as AnchorLearner.Outcome.Insufficient)
        assertEquals(AnchorLearner.Reason.NOT_STABLE_ENOUGH, reason.reason)
        assertTrue(reason.detail.contains("10"))
    }

    @Test fun missingAmbientSupportIsRejected() {
        val outcome = learner.learn(learnableSamples(), ambientSourceCount = 1, stableMillis = 30 * 60_000L)
        val reason = (outcome as AnchorLearner.Outcome.Insufficient)
        assertEquals(AnchorLearner.Reason.NO_AMBIENT_SUPPORT, reason.reason)
    }

    @Test fun pointsBeyondAccuracyGateAreDropped() {
        val coarse = (0 until 12).map { sample(day = it % 6 + 1, accuracy = 50f) }
        val reason = learner.learn(coarse, 2, 30 * 60_000L) as AnchorLearner.Outcome.Insufficient
        assertEquals(AnchorLearner.Reason.TOO_FEW_POINTS, reason.reason)
        assertTrue("50 米精度的点应被全部丢弃，文案=${reason.detail}", reason.detail.contains("0"))
    }

    @Test fun inferredAndReplayedPointsDoNotCount() {
        val mixed = (0 until 12).map { i ->
            when {
                i < 4 -> sample(day = i % 6 + 1, inferred = true)
                i < 8 -> sample(day = i % 6 + 1, manualReplay = true)
                else -> sample(day = i % 6 + 1)
            }
        }
        val reason = learner.learn(mixed, 2, 30 * 60_000L) as AnchorLearner.Outcome.Insufficient
        assertEquals(AnchorLearner.Reason.TOO_FEW_POINTS, reason.reason)
        assertTrue("只剩 4 个真实观测，文案=${reason.detail}", reason.detail.contains("4"))
    }

    @Test fun pointsOutsideCoreAreaDoNotCount() {
        val outside = (0 until 12).map { sample(day = it % 6 + 1, inCore = false) }
        val reason = learner.learn(outside, 2, 30 * 60_000L) as AnchorLearner.Outcome.Insufficient
        assertEquals(AnchorLearner.Reason.TOO_FEW_POINTS, reason.reason)
    }

    @Test fun medianCenterRejectsTheFarOutlier() {
        val cluster = learnableSamples()
        val outlier = sample(day = 6, lat = BASE_LAT + 0.01)
        val found = learner.learn(cluster + outlier, 2, 30 * 60_000L) as AnchorLearner.Outcome.Found
        assertEquals(13, found.candidate.sampleCount)
        assertEquals("1000 米外的点必须被剔掉", 1, found.candidate.rejectedCount)
        assertEquals(12, found.candidate.acceptedCount)
    }

    @Test fun spreadOutSamplesRejectTheWholeCandidate() {
        // 每点间隔约 111 米，12 个点拉开 1.2 公里 —— 这不是一个"地点"
        val spread = (0 until 12).map { sample(day = it % 6 + 1, lat = BASE_LAT + it * 0.001) }
        val reason = learner.learn(spread, 2, 30 * 60_000L) as AnchorLearner.Outcome.Insufficient
        assertEquals(AnchorLearner.Reason.SCATTERED, reason.reason)
    }

    @Test fun offsetIsMeasuredAgainstConfiguredAnchorNotItsOwnCenter() {
        val samples = learnableSamples()
        // 配置锚点挪开约 80 米 → 偏移应反映出来，而不是恒为 0
        val configured = GeoPoint(BASE_LAT + 0.0007, BASE_LNG)
        val found = learner.learn(samples, 2, 30 * 60_000L, configured) as AnchorLearner.Outcome.Found
        assertTrue("偏移应在 60~100 米之间，实际=${found.candidate.offsetMeters}", found.candidate.offsetMeters > 60)
        assertTrue("偏移应在 60~100 米之间，实际=${found.candidate.offsetMeters}", found.candidate.offsetMeters < 100)
    }

    @Test fun offsetPenalizesConfidenceAgainstAConfigAnchor() {
        val samples = learnableSamples()
        val onSpot = learner.learn(samples, 3, 30 * 60_000L, GeoPoint(BASE_LAT, BASE_LNG))
        val farOff = learner.learn(samples, 3, 30 * 60_000L, GeoPoint(BASE_LAT + 0.0007, BASE_LNG))
        val a = (onSpot as AnchorLearner.Outcome.Found).candidate.confidence
        val b = (farOff as AnchorLearner.Outcome.Found).candidate.confidence
        assertTrue("偏移越大置信度越低（$b 应小于 $a）", b < a)
    }

    @Test fun withoutConfiguredAnchorOffsetIsZero() {
        val found = learner.learn(learnableSamples(), 2, 30 * 60_000L, null) as AnchorLearner.Outcome.Found
        assertEquals(0.0, found.candidate.offsetMeters, 0.001)
    }

    @Test fun gatesMatchTheDesignDocument() {
        assertEquals(30f, AnchorLearner.MAX_ACCURACY_METERS, 0.001f)
        assertEquals(10, AnchorLearner.MIN_POINTS)
        assertEquals(5, AnchorLearner.MIN_DAYS)
        assertEquals(20 * 60_000L, AnchorLearner.MIN_STABLE_MILLIS)
        assertEquals(2, AnchorLearner.MIN_AMBIENT_SOURCES)
    }

    private fun sample(
        day: Int,
        lat: Double = BASE_LAT,
        lng: Double = BASE_LNG,
        accuracy: Float = 10f,
        inCore: Boolean = true,
        inferred: Boolean = false,
        manualReplay: Boolean = false
    ) = AnchorLearner.Sample(
        latitude = lat,
        longitude = lng,
        accuracyMeters = accuracy,
        time = day * DAY_MILLIS,
        localDay = "2026-09-%02d".format(day),
        inCore = inCore,
        inferred = inferred,
        manualReplay = manualReplay
    )

    private companion object {
        const val BASE_LAT = 31.0
        const val BASE_LNG = 121.0
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
