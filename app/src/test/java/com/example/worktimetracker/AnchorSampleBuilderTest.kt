package com.example.worktimetracker

import com.example.worktimetracker.domain.location.AnchorSampleBuilder
import com.example.worktimetracker.domain.location.GeoPoint
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学习样本切分（方案 §三.2 的输入准备）。
 *
 * 这段是整条锚点学习链路最容易写歪的地方：什么算「一次有效停留」直接决定
 * 后面的门槛是否有意义。三条口径（入核 / 连续 / 跨天）各有一组测试钉住。
 */
class AnchorSampleBuilderTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val center = GeoPoint(31.0, 121.0)

    /** 2026-09-01 08:00（设备时区）。刻意用推导而不是硬编码毫秒 —— 手算必错。 */
    private val dayStart: Long =
        LocalDateTime.of(2026, 9, 1, 8, 0).atZone(zone).toInstant().toEpochMilli()

    /** 每 [stepMillis] 一个点，共 [count] 个，经纬度固定。 */
    private fun every(
        count: Int,
        startMillis: Long,
        stepMillis: Long = 5 * 60_000L,
        lat: Double = 31.0
    ) = (0 until count).map { i ->
        AnchorSampleBuilder.Fix(startMillis + i * stepMillis, lat, 121.0, 10f)
    }

    @Test fun emptyInputYieldsNothing() {
        val result = AnchorSampleBuilder.build(emptyList(), center, 100, zone)
        assertTrue(result.samples.isEmpty())
        assertEquals(0L, result.stableMillis)
    }

    @Test fun pointsInsideTheRadiusAreMarkedAsCore() {
        val result = AnchorSampleBuilder.build(every(4, dayStart), center, 100, zone)
        assertEquals(4, result.samples.size)
        assertTrue(result.samples.all { it.inCore })
    }

    @Test fun pointsOutsideTheRadiusAreNotCore() {
        // 纬度方向偏 0.002 度 ≈ 222 米，远超 100 米半径
        val result = AnchorSampleBuilder.build(every(3, dayStart, lat = 31.002), center, 100, zone)
        assertTrue(result.samples.none { it.inCore })
        assertEquals(0L, result.stableMillis)
    }

    @Test fun radiusBoundaryIsInclusive() {
        // 约 90 米，仍在 100 米圈内
        val result = AnchorSampleBuilder.build(
            every(2, dayStart, lat = 31.0 + 90.0 / 111_200.0), center, 100, zone
        )
        assertTrue("半径上应算圈内", result.samples.all { it.inCore })
    }

    @Test fun stableSpanIsTheLongestUnbrokenRun() {
        // 5 个点、每 5 分钟 → 跨 20 分钟
        val result = AnchorSampleBuilder.build(every(5, dayStart), center, 100, zone)
        assertEquals(20 * 60_000L, result.stableMillis)
    }

    @Test fun aLongGapSplitsTheStayIntoSeparateRuns() {
        // 前 3 个点跨 10 分钟 → 断 2 小时 → 后 2 个点跨 5 分钟；应取较长的那段
        val first = every(3, dayStart)
        val second = every(2, dayStart + 2 * 60 * 60_000L)
        val result = AnchorSampleBuilder.build(first + second, center, 100, zone)
        assertEquals(10 * 60_000L, result.stableMillis)
    }

    @Test fun gapExactlyAtTheLimitStillCountsAsContinuous() {
        val step = AnchorSampleBuilder.MAX_GAP_MILLIS
        val result = AnchorSampleBuilder.build(every(2, dayStart, stepMillis = step), center, 100, zone)
        assertEquals("间隔正好等于上限不算断开", step, result.stableMillis)
    }

    @Test fun oneMillisecondOverTheLimitBreaksTheRun() {
        val step = AnchorSampleBuilder.MAX_GAP_MILLIS + 1
        val result = AnchorSampleBuilder.build(every(2, dayStart, stepMillis = step), center, 100, zone)
        assertEquals("断开后每段只剩一个点，停留时长为 0", 0L, result.stableMillis)
    }

    @Test fun leavingAndComingBackDoesNotExtendTheStay() {
        // 入核 → 出核 → 入核：最长段只能是其中一段，绝不能把离开的时间也算进停留
        val inside = every(3, dayStart)
        val outside = every(3, dayStart + 60 * 60_000L, lat = 31.01)
        val insideAgain = every(3, dayStart + 2 * 60 * 60_000L)
        val result = AnchorSampleBuilder.build(inside + outside + insideAgain, center, 100, zone)
        assertEquals(10 * 60_000L, result.stableMillis)
        assertEquals("出核的点仍是样本，只是落不到核心区", 3, result.samples.count { !it.inCore })
    }

    @Test fun localDayFollowsTheDeviceZoneNotUtc() {
        // 东八区 2026-09-01 00:30 = UTC 2026-08-31 16:30 —— 必须记成 09-01
        val justAfterMidnight = LocalDateTime.of(2026, 9, 1, 0, 30).atZone(zone).toInstant().toEpochMilli()
        val result = AnchorSampleBuilder.build(every(1, justAfterMidnight), center, 100, zone)
        assertEquals("2026-09-01", result.samples.first().localDay)
    }

    @Test fun rawAccuracyIsPassedThroughUntouched() {
        // 粗定位不在这一层被"修正"或丢弃 —— 判定统一交给 AnchorLearner，避免两处口径不一致
        val fixes = listOf(AnchorSampleBuilder.Fix(dayStart, 31.0, 121.0, 120f))
        val result = AnchorSampleBuilder.build(fixes, center, 100, zone)
        assertEquals(120f, result.samples.first().accuracyMeters, 0.001f)
    }

    @Test fun everySampleKeepsItsOwnTimestamp() {
        val step = 5 * 60_000L
        val result = AnchorSampleBuilder.build(every(3, dayStart, stepMillis = step), center, 100, zone)
        assertEquals(dayStart, result.samples[0].time)
        assertEquals(dayStart + step, result.samples[1].time)
        assertEquals(dayStart + 2 * step, result.samples[2].time)
    }
}
