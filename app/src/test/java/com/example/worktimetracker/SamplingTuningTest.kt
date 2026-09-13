package com.example.worktimetracker

import com.example.worktimetracker.location.evidence.AmbientScanPolicy
import com.example.worktimetracker.location.evidence.ScanDecision
import com.example.worktimetracker.location.evidence.ScanPolicyInput
import com.example.worktimetracker.location.service.LocationSamplingPolicy
import com.example.worktimetracker.location.service.SamplingTuning
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 用户档位（常规采集间隔 / Burst 上限）→ 引擎毫秒值的护栏。
 *
 * 重点保护两条不变量，任何一条被绕过都会造成省电回退或后台被系统限制：
 * 1. 常规采集间隔不会比快速档更激进、也不会比默认档更省电；
 * 2. **Burst 上限只能调低，永远不突破 10 分钟硬顶**。
 */
class SamplingTuningTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val policy = LocationSamplingPolicy(zone)

    private fun ms(h: Int, min: Int): Long =
        LocalDateTime.of(2026, 7, 29, h, min).atZone(zone).toInstant().toEpochMilli()

    // ----------------------------------------------------- 常规采集间隔

    @Test
    fun `未设置时回落到历史默认5分钟`() {
        assertEquals(5 * 60_000L, SamplingTuning.normalIntervalMillis(null))
    }

    @Test
    fun `界面提供的四档原样生效`() {
        assertEquals(1 * 60_000L, SamplingTuning.normalIntervalMillis(1))
        assertEquals(3 * 60_000L, SamplingTuning.normalIntervalMillis(3))
        assertEquals(5 * 60_000L, SamplingTuning.normalIntervalMillis(5))
        assertEquals(10 * 60_000L, SamplingTuning.normalIntervalMillis(10))
    }

    @Test
    fun `越界档位被夹回合法区间`() {
        assertEquals(10 * 60_000L, SamplingTuning.normalIntervalMillis(30))
        assertEquals(1 * 60_000L, SamplingTuning.normalIntervalMillis(0))
        assertEquals(1 * 60_000L, SamplingTuning.normalIntervalMillis(-5))
    }

    // ----------------------------------------------------- Burst 硬顶

    @Test
    fun `未设置Burst时用10分钟硬顶`() {
        assertEquals(10 * 60_000L, SamplingTuning.burstCapMillis(null))
    }

    @Test
    fun `Burst上限可以调低`() {
        assertEquals(3 * 60_000L, SamplingTuning.burstCapMillis(3))
        assertEquals(5 * 60_000L, SamplingTuning.burstCapMillis(5))
    }

    @Test
    fun `Burst上限永远不能突破10分钟硬顶`() {
        assertEquals(10 * 60_000L, SamplingTuning.burstCapMillis(10))
        assertEquals(10 * 60_000L, SamplingTuning.burstCapMillis(11))
        assertEquals(10 * 60_000L, SamplingTuning.burstCapMillis(60))
        assertEquals(10 * 60_000L, SamplingTuning.burstCapMillis(9999))
    }

    @Test
    fun `Burst上限夹到至少1分钟，不会变成0`() {
        assertEquals(1 * 60_000L, SamplingTuning.burstCapMillis(0))
    }

    // ----------------------------------------------------- 移动跟踪下限

    @Test
    fun `移动跟踪下限不低于5分钟`() {
        assertEquals(5 * 60_000L, SamplingTuning.movingTrackFloorMillis(null))
        assertEquals(5 * 60_000L, SamplingTuning.movingTrackFloorMillis(1))
        assertEquals(5 * 60_000L, SamplingTuning.movingTrackFloorMillis(5))
    }

    @Test
    fun `常规间隔调到10分钟时移动跟踪下限跟随`() {
        assertEquals(10 * 60_000L, SamplingTuning.movingTrackFloorMillis(10))
    }

    // ----------------------------------------------------- 策略真的读档位

    @Test
    fun `班次窗口档位跟随用户设置`() {
        val three = LocationSamplingPolicy(zone).intervalMillis(
            currentState = "REST",
            locationType = "HOME",
            distanceToFenceMeters = 20.0,
            fenceRadiusMeters = 200,
            speedMetersPerSecond = 0f,
            nowMillis = ms(8, 0),
            workStartMinutes = 9 * 60,
            workEndMinutes = 21 * 60,
            normalIntervalMillis = SamplingTuning.normalIntervalMillis(3)
        )
        assertEquals(3 * 60_000L, three)
    }

    @Test
    fun `不传档位时保持历史5分钟不变`() {
        assertEquals(
            5 * 60_000L,
            policy.intervalMillis("REST", "HOME", 20.0, 200, 0f, ms(8, 0), 9 * 60, 21 * 60)
        )
    }

    @Test
    fun `快速档与稳定档不受用户档位影响`() {
        // 贴边/移动 → 仍是 1 分钟
        assertEquals(
            60_000L,
            policy.intervalMillis("WORKING", "COMPANY", 180.0, 200, 0f, ms(14, 0), 9 * 60, 21 * 60,
                normalIntervalMillis = SamplingTuning.normalIntervalMillis(10))
        )
        // 稳定家/公司 → 仍是 30 分钟
        assertEquals(
            30 * 60_000L,
            policy.intervalMillis("REST", "HOME", 20.0, 200, 0f, ms(14, 0), 9 * 60, 21 * 60,
                normalIntervalMillis = SamplingTuning.normalIntervalMillis(10))
        )
    }

    // ----------------------------------------------------- 扫描冷却跟随档位

    @Test
    fun `扫描冷却跟随用户档位`() {
        val now = ms(14, 0)
        val input = ScanPolicyInput(
            now = now,
            lastScanAt = now - 2 * 60_000L,
            significantMotion = false,
            gnssStale = false,
            nearShiftWindow = true,
            stableKnownPlace = false
        )
        // 3 分钟档：2 分钟前扫过 → 仍在冷却
        assertEquals(ScanDecision.NONE, AmbientScanPolicy().evaluate(input, 3 * 60_000L))
        // 1 分钟档：2 分钟前扫过 → 冷却已过，按班次窗口给 SNAPSHOT
        assertEquals(ScanDecision.SNAPSHOT, AmbientScanPolicy().evaluate(input, 1 * 60_000L))
        // 默认档（历史 5 分钟）→ 仍在冷却
        assertEquals(ScanDecision.NONE, AmbientScanPolicy().evaluate(input))
    }
}
