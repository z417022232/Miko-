package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.WorkHourCalculator
import com.example.worktimetracker.domain.model.RecordStatus
import com.example.worktimetracker.domain.model.WorkSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A4: R1 迟到 ≤3min 容差边界守护。
 *
 * 规则（verification/工时计薪规则.md §2 R1）：
 * - `startTime − 09:00 ≤ 3min`（含早到）→ 不触发迟到、按 09:00 起算
 * - `startTime − 09:00 > 3min` → 向上取整到下一整点（⌈startTime⌉）
 *
 * 守护 2026-09-12 发现的缺陷：容差判断误用 `alignUpToHour(start) − start`（那是"距下一整点还剩多少"，
 * 09:03 会算成 57min 而误判超容差），正确基准是 `start − expectedStart`。
 */
class LateArrivalToleranceTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val calculator = WorkHourCalculator(zone)
    private val settings = WorkSettings(
        workStartMinutes = 9 * 60, workEndMinutes = 21 * 60,
        earlyLeaveToleranceMinutes = 3, arrivalToleranceMinutes = 3
    )

    private fun at(time: String): Long =
        LocalDateTime.parse("2026-09-12T$time").atZone(zone).toInstant().toEpochMilli()

    private fun expectedAt(time: String): Long =
        LocalDateTime.parse("2026-09-12T$time").atZone(zone).toInstant().toEpochMilli()

    /** 迟到日统一按 21:00 整下班，隔离出只看上班侧的分支。 */
    private fun calcStartSide(arrival: String) = calculator.calculateV1FinalMinutes(
        status = RecordStatus.ARRIVAL_EXCEPTION,
        startMillis = at(arrival),
        endMillis = at("21:00:00"),
        settings = settings
    )

    @Test fun exactNineOClockCountsAsR1() {
        val r = calcStartSide("09:00:00")
        assertTrue("应标 R1", r.ruleTrace.contains("R1"))
        assertEquals("应精确按 09:00 起算", expectedAt("09:00:00"), r.effectiveStartMillis)
        assertEquals("11h（9:00-21:00-1h）", 11 * 60, r.finalMinutes)
    }

    @Test fun threeMinutesLateIsWithinTolerance() {
        // 09:03 差 3min → 容差内，仍按 09:00 起算
        val r = calcStartSide("09:03:00")
        assertTrue("应标 R1", r.ruleTrace.contains("R1"))
        assertFalse("不应标对齐", r.ruleTrace.contains("R1_ALIGN_UP"))
        assertEquals("应精确按 09:00 起算", expectedAt("09:00:00"), r.effectiveStartMillis)
        assertEquals("11h", 11 * 60, r.finalMinutes)
    }

    @Test fun fourMinutesLateIsOutOfTolerance() {
        // 09:04 差 4min → 超容差，向上取整到 10:00
        val r = calcStartSide("09:04:00")
        assertTrue("应标对齐", r.ruleTrace.contains("R1_ALIGN_UP"))
        assertFalse("不应标 R1", r.ruleTrace.contains("R1"))
        assertEquals("应向上取整到 10:00", expectedAt("10:00:00"), r.effectiveStartMillis)
        assertEquals("10h（10:00-21:00-1h）", 10 * 60, r.finalMinutes)
    }

    @Test fun fiftyNineSecondsLateStillWithinTolerance() {
        // 09:03:59 差不足 4min → 容差内
        val r = calcStartSide("09:03:59")
        assertTrue("应标 R1", r.ruleTrace.contains("R1"))
        assertEquals(expectedAt("09:00:00"), r.effectiveStartMillis)
    }

    @Test fun arrivingEarlyIsR1AndStartsAtNine() {
        // 08:55 早到 → R1，按 09:00 起算（早到不算加班）
        val r = calcStartSide("08:55:00")
        assertTrue("应标 R1", r.ruleTrace.contains("R1"))
        assertEquals(expectedAt("09:00:00"), r.effectiveStartMillis)
        assertEquals(11 * 60, r.finalMinutes)
    }

    @Test fun fortyNineMinutesLateAlignsUpToTen() {
        // 9/12 实战：09:49 迟到 49min → 10:00 起算 → 10h
        val r = calcStartSide("09:49:00")
        assertTrue(r.ruleTrace.contains("R1_ALIGN_UP"))
        assertEquals(expectedAt("10:00:00"), r.effectiveStartMillis)
        assertEquals(10 * 60, r.finalMinutes)
    }

    @Test fun exactlyOneHourLateAlignsToTen() {
        // 10:00 整到岗：arrivalLateMinutes=60 > 3 → alignUpToHour(10:00)=10:00（整点不跳下一个）
        val r = calcStartSide("10:00:00")
        assertTrue(r.ruleTrace.contains("R1_ALIGN_UP"))
        assertEquals(expectedAt("10:00:00"), r.effectiveStartMillis)
        assertEquals(10 * 60, r.finalMinutes)
    }

    @Test fun oneMinutePastTenAlignsToEleven() {
        // 10:01 到岗 → 向上取整 11:00 → 9h
        val r = calcStartSide("10:01:00")
        assertTrue(r.ruleTrace.contains("R1_ALIGN_UP"))
        assertEquals(expectedAt("11:00:00"), r.effectiveStartMillis)
        assertEquals("9h（11:00-21:00-1h）", 9 * 60, r.finalMinutes)
    }

    @Test fun zeroToleranceMakesAnyLateAlign() {
        // 容差配 0 时，09:01 就应对齐到 10:00
        val strict = settings.copy(arrivalToleranceMinutes = 0)
        val r = calculator.calculateV1FinalMinutes(
            status = RecordStatus.ARRIVAL_EXCEPTION,
            startMillis = at("09:01:00"),
            endMillis = at("21:00:00"),
            settings = strict
        )
        assertTrue(r.ruleTrace.contains("R1_ALIGN_UP"))
        assertEquals(expectedAt("10:00:00"), r.effectiveStartMillis)
    }

    @Test fun widerToleranceKeepsNineOClock() {
        // 容差放宽到 10min 时，09:08 应仍在容差内 → 09:00 起算
        val loose = settings.copy(arrivalToleranceMinutes = 10)
        val r = calculator.calculateV1FinalMinutes(
            status = RecordStatus.ARRIVAL_EXCEPTION,
            startMillis = at("09:08:00"),
            endMillis = at("21:00:00"),
            settings = loose
        )
        assertTrue(r.ruleTrace.contains("R1"))
        assertEquals(expectedAt("09:00:00"), r.effectiveStartMillis)
        assertEquals(11 * 60, r.finalMinutes)
    }
}