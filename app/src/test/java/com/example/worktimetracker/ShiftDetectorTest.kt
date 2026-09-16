package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.ShiftDetector
import com.example.worktimetracker.domain.model.ShiftType
import com.example.worktimetracker.domain.model.WorkSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 班次自动识别回归测试（算法冻结）。
 *
 * 背景（2026-09-13 用户确认）：班次识别**保持原有算法不变**——"8 月的白班和夜班的信息数据基本是对的"。
 * 本测试不改进算法，只把当前行为用真实数据锁死，防止后续改动（如 A5 引入 shiftType 参数、
 * expectedWindow 重构）无意破坏识别结果。
 *
 * 算法（ShiftDetector.detectShift）：
 *   以【到岗时刻】为基准，在 {当天 09:00, 当天 21:00, ±1 天的 09:00/21:00} 共 6 个候选锚点中
 *   取距离最近者（Duration.toMinutes() 向零截断）；最近锚点时刻等于 workStartMinutes 时刻 ⇒ 白班，
 *   否则 ⇒ 夜班。候选列表以 dayStart 开头，故完全并列时（15:00:00）判定为**白班**。
 *
 * 2026-09-16 起 `detectShift` 与 `assignedDate` 共同委托 [ShiftDetector.anchorFor]：
 * **班次与归属日必须来自同一个锚点**，否则凌晨到岗会出现「识别为夜班、却归到当天」的矛盾。
 * 识别结果本身一字未改（下面的冻结边界用例可证）。
 *
 * 2026-09-13 设备实测：8 月 29 条记录 28 条与库中 shift 完全一致；唯一"不一致"是 id=158
 * 库内为中文 '白班'（UI 手工写入）而算法预测 DAY_SHIFT——语义一致，非算法错误。
 * 全库另有 4 条枚举不一致（id=66/71/114 为旧软件导入时无条件盖 DAY_SHIFT，
 * id=145 为"用户确认夜班"手工覆盖），均非识别链路所致。
 */
class ShiftDetectorTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val detector = ShiftDetector(zone)

    /** 本机实际设置：09:00 上班 / 21:00 下班（user_settings: workStartMinutes=540, workEndMinutes=1260）。 */
    private val settings = WorkSettings(workStartMinutes = 9 * 60, workEndMinutes = 21 * 60)

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int, sec: Int = 0) =
        LocalDateTime.of(y, m, d, h, min, sec).atZone(zone).toInstant().toEpochMilli()

    private fun shiftAt(y: Int, m: Int, d: Int, h: Int, min: Int, sec: Int = 0) =
        detector.detectShift(ms(y, m, d, h, min, sec), settings)

    // ---------- 8 月真实到岗时刻回放 ----------

    /**
     * 8 月白班真实到岗时刻（设备 work_records.startTime，全部识别为 DAY_SHIFT）。
     * 覆盖 08:21–09:58，含 09:00 整点与 09:58 迟到场景。
     */
    @Test fun augustDayShiftArrivalsAreAllDetectedAsDayShift() {
        val arrivals = listOf(
            8 to 21, 8 to 31, 8 to 32, 8 to 32, 8 to 34, 8 to 38, 8 to 40,
            8 to 42, 8 to 42, 8 to 43, 8 to 43, 8 to 48, 8 to 48,
            9 to 0, 9 to 58
        )
        arrivals.forEachIndexed { index, (h, min) ->
            val day = 1 + (index % 28)
            assertEquals(
                "8月白班到岗 %02d:%02d 应识别为白班".format(h, min),
                ShiftType.DAY_SHIFT,
                shiftAt(2026, 8, day, h, min)
            )
        }
    }

    /**
     * 8 月夜班真实到岗时刻（全部识别为 NIGHT_SHIFT）。
     * 覆盖 20:24–20:56，即夜班"提前到岗"的典型区间。
     */
    @Test fun augustNightShiftArrivalsAreAllDetectedAsNightShift() {
        val arrivals = listOf(
            20 to 24, 20 to 30, 20 to 37, 20 to 43, 20 to 43, 20 to 43, 20 to 44,
            20 to 48, 20 to 48, 20 to 49, 20 to 51, 20 to 54, 20 to 54, 20 to 56
        )
        arrivals.forEachIndexed { index, (h, min) ->
            val day = 1 + (index % 28)
            assertEquals(
                "8月夜班到岗 %02d:%02d 应识别为夜班".format(h, min),
                ShiftType.NIGHT_SHIFT,
                shiftAt(2026, 8, day, h, min)
            )
        }
    }

    /** 8 月夜班跨夜归属复检：workDate 一律等于到岗（上班）日，且与端到端 session 一致。 */
    @Test fun augustNightShiftRecordsKeepStartDateAsWorkDate() {
        // 20:44 到岗 → 次日 09:12 下班（id=149 实况），归 8/1
        val start = ms(2026, 8, 1, 20, 44)
        val end = ms(2026, 8, 2, 9, 12)
        assertEquals(ShiftType.NIGHT_SHIFT, detector.detectShift(start, settings))
        assertEquals("2026-08-01", detector.assignedDate(start, settings))
        assertTrue(detector.crossesMidnight(start, end))
        assertEquals("跨夜归属日不得由 endMillis 推导", "2026-08-01", detector.assignedDate(start, settings))
    }

    // ---------- 识别边界（9:00 与 21:00 两个锚点的 Voronoi 分界）----------

    @Test fun arrivalAtExactWorkStartIsDayShift() {
        assertEquals(ShiftType.DAY_SHIFT, shiftAt(2026, 8, 31, 9, 0))
    }

    @Test fun arrivalJustBeforeMidpointIsDayShift() {
        // 14:59 → 距 09:00 为 359min，距 21:00 为 361min
        assertEquals(ShiftType.DAY_SHIFT, shiftAt(2026, 8, 15, 14, 59))
    }

    @Test fun arrivalAtExactMidpointTiesToDayShift() {
        // 15:00:00 恰好并列（360 / 360）；minBy 取列表首个候选 dayStart ⇒ 白班。
        // 这是**有意的现状行为**，若日后改为四舍五入/后半段归夜班，此测试会失败以提示风险。
        assertEquals(ShiftType.DAY_SHIFT, shiftAt(2026, 8, 15, 15, 0, 0))
    }

    @Test fun arrivalJustAfterMidpointIsNightShift() {
        // 15:00:30：到 09:00 为 360min（截断），到 21:00 为 359min ⇒ 夜班
        assertEquals(ShiftType.NIGHT_SHIFT, shiftAt(2026, 8, 15, 15, 0, 30))
        assertEquals(ShiftType.NIGHT_SHIFT, shiftAt(2026, 8, 15, 15, 1))
    }

    @Test fun arrivalCloseToNightStartIsNightShift() {
        assertEquals(ShiftType.NIGHT_SHIFT, shiftAt(2026, 8, 15, 20, 59))
        assertEquals(ShiftType.NIGHT_SHIFT, shiftAt(2026, 8, 15, 21, 0))
        assertEquals(ShiftType.NIGHT_SHIFT, shiftAt(2026, 8, 15, 21, 1))
    }

    @Test fun earlyMorningArrivalIsNightShiftBecauseItIsClosestToPreviousNightStart() {
        // 02:00：距前一晚 21:00 为 300min，距当天 09:00 为 420min ⇒ 夜班（凌晨到岗＝夜班迟到大户）
        assertEquals(ShiftType.NIGHT_SHIFT, shiftAt(2026, 8, 15, 2, 0))
    }

    @Test fun morningArrivalIsDayShift() {
        assertEquals(ShiftType.DAY_SHIFT, shiftAt(2026, 8, 15, 8, 0))
        assertEquals(ShiftType.DAY_SHIFT, shiftAt(2026, 8, 15, 4, 0))
    }

    // ---------- 期望窗（供 v1 计薪公式套用，非识别本身）----------

    @Test fun expectedWindowForDayShiftIsSameDay() {
        val date = LocalDate.of(2026, 9, 12)
        assertEquals(
            LocalDateTime.of(2026, 9, 12, 9, 0),
            detector.expectedStart(date, ShiftType.DAY_SHIFT, settings)
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 12, 21, 0),
            detector.expectedEnd(date, ShiftType.DAY_SHIFT, settings)
        )
    }

    @Test fun expectedWindowForNightShiftSpansToNextMorning() {
        val date = LocalDate.of(2026, 8, 1)
        assertEquals(
            LocalDateTime.of(2026, 8, 1, 21, 0),
            detector.expectedStart(date, ShiftType.NIGHT_SHIFT, settings)
        )
        assertEquals(
            LocalDateTime.of(2026, 8, 2, 9, 0),
            detector.expectedEnd(date, ShiftType.NIGHT_SHIFT, settings)
        )
    }

    // ---------- 归属日 / 跨夜派生 ----------

    /**
     * 归属日 = **吸附锚点的开班日**，不是「到岗时刻的日历日」。
     *
     * 2026-09-16 复查 P0 修正：凌晨到岗曾被归到当天，与「识别为夜班」自相矛盾
     * （夜班开班在前一晚 21:00），还会和当天白班撞在同一个 workDate。
     */
    @Test fun assignedDateFollowsShiftAnchor() {
        assertEquals("早班 09:00 到岗 → 当天", "2026-09-12", detector.assignedDate(ms(2026, 9, 12, 9, 0), settings))
        assertEquals("夜班 20:44 到岗 → 当天", "2026-08-01", detector.assignedDate(ms(2026, 8, 1, 20, 44), settings))
        // 次日凌晨 02:00 到岗：最近锚点是**前一晚 21:00**（夜班开班）→ 归属日必须是 8/1
        assertEquals("凌晨到岗 → 归前一晚开班的那个夜班", "2026-08-01", detector.assignedDate(ms(2026, 8, 2, 2, 0), settings))
        // 而当天早班 08:00 到岗仍归当天，两者不会撞在同一天
        assertEquals("早班到岗 → 当天", "2026-08-02", detector.assignedDate(ms(2026, 8, 2, 8, 0), settings))
    }

    /** 班次与归属日必须同源：`anchorFor` 一次产出两者，不允许分叉。 */
    @Test fun shiftAndAssignedDateComeFromTheSameAnchor() {
        listOf(
            ms(2026, 8, 1, 20, 44), ms(2026, 8, 2, 2, 0), ms(2026, 8, 2, 8, 0),
            ms(2026, 8, 2, 0, 30), ms(2026, 8, 1, 9, 0)
        ).forEach { arrival ->
            val anchor = detector.anchorFor(arrival, settings)
            assertEquals("detectShift 必须等于锚点班次 @ $arrival", anchor.shift, detector.detectShift(arrival, settings))
            assertEquals("assignedDate 必须等于锚点日期 @ $arrival", anchor.date.toString(), detector.assignedDate(arrival, settings))
        }
    }

    @Test fun crossesMidnightReflectsCalendarDateChange() {
        assertFalse(detector.crossesMidnight(ms(2026, 9, 12, 9, 0), ms(2026, 9, 12, 21, 0)))
        assertTrue(detector.crossesMidnight(ms(2026, 8, 1, 21, 0), ms(2026, 8, 2, 9, 0)))
        assertFalse("endMillis 缺失按不跨夜处理", detector.crossesMidnight(ms(2026, 8, 1, 21, 0), null))
    }

    // ---------- 设置变化时识别随之平移（非硬编码 09:00/21:00）----------

    @Test fun detectionFollowsCustomWorkWindow() {
        val custom = WorkSettings(workStartMinutes = 8 * 60, workEndMinutes = 20 * 60)
        assertEquals(ShiftType.DAY_SHIFT, detector.detectShift(ms(2026, 8, 15, 7, 30), custom))
        assertEquals(ShiftType.NIGHT_SHIFT, detector.detectShift(ms(2026, 8, 15, 19, 40), custom))
        // 新窗口中点 14:00 并列 ⇒ 仍取白班
        assertEquals(ShiftType.DAY_SHIFT, detector.detectShift(ms(2026, 8, 15, 14, 0), custom))
    }
}
