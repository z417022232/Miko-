package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.DayKind
import com.example.worktimetracker.ui.PayrollPresenter
import com.example.worktimetracker.ui.UiDayRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 「整月预估」纯逻辑护栏（用户 2026-09-14 选定的「工时 × 到手单价」口径）。
 *
 * 口径要点（回归重点）：
 * 1. 只补 `date >= today` 的无记录日子 —— 过去漏记的不算“预估成上班”，那是补录的事；
 * 2. 工作日 / 调休上班 / 普通周末 → 计出勤 + 标准工时；节日当天 / 假期休息日 → 计出勤 + 0 工时
 *    （上海口径节假日带薪，只是不上班，所以钱照给、工时不给）；
 * 3. 整月工时 = 已记工时 + 上班日 × 标准工时；
 * 4. 夜班天数：用户填了「本月夜班天数」以填的为准，否则按**最近一天**的班次延续；
 * 5. 没有可补的日子 → `hasProjection=false`（界面不显示这一行）。
 */
class SalaryProjectionTest {

    private val today = LocalDate.of(2026, 9, 13)
    private val standard = 11 * 60

    private fun rec(
        day: Int,
        minutes: Int,
        shift: String? = "白班",
        kind: DayKind = DayKind.WORKDAY,
    ) = UiDayRecord(
        date = LocalDate.of(2026, 9, day),
        status = shift ?: "白班",
        shift = shift,
        finalMinutes = minutes,
        dayKind = kind,
    )

    /** 9/1~9/13 已记 13 天白班（各 11h）＝ 143h。 */
    private fun recordedThirteen() = (1..13).map { rec(it, standard) }

    /** 9/14~9/30 无记录共 17 天：其中 9/25 中秋当天、9/26 假期休息日。 */
    private fun pendingSeventeen() = (14..30).map { d ->
        when (d) {
            25 -> rec(d, 0, kind = DayKind.FESTIVAL)
            26 -> rec(d, 0, kind = DayKind.HOLIDAY_REST)
            else -> rec(d, 0)
        }
    }

    @Test
    fun `已记天只算 finalMinutes 大于 0 且工时求和`() {
        val stats = PayrollPresenter.projectionStats(
            recordedThirteen() + pendingSeventeen(), today, standard
        )
        assertEquals(13, stats.recordedDays)
        assertEquals(13 * standard, stats.recordedMinutes)
    }

    @Test
    fun `只补 today 及之后的无记录日子`() {
        // 9/5 缺记（过去） + 9/14 缺记（未来）
        val records = listOf(rec(5, 0), rec(14, 0))
        val stats = PayrollPresenter.projectionStats(records, today, standard)
        assertEquals(1, stats.unrecordedWorkDays)   // 只有 9/14
        assertEquals(1, stats.projectedAttendDays)  // 9/5 在今天之前且无记录 → 不计出勤
    }

    @Test
    fun `节日与假期休息日计出勤但不计工时`() {
        val stats = PayrollPresenter.projectionStats(
            recordedThirteen() + pendingSeventeen(), today, standard
        )
        assertEquals(15, stats.unrecordedWorkDays)
        assertEquals(2, stats.unrecordedPaidRestDays)
        assertEquals(17, stats.unrecordedDays)
        // 只有上班日补工时，节日/休息日不补
        assertEquals(13 * standard + 15 * standard, stats.projectedMinutes)
        assertEquals(30, stats.projectedAttendDays)
    }

    @Test
    fun `调休上班与普通周末都算要上班的日子`() {
        val records = listOf(
            rec(14, 0, kind = DayKind.MAKEUP_WORKDAY),
            rec(15, 0, kind = DayKind.WEEKEND),
            rec(16, 0, kind = DayKind.WORKDAY),
        )
        val stats = PayrollPresenter.projectionStats(records, today, standard)
        assertEquals(3, stats.unrecordedWorkDays)
        assertEquals(0, stats.unrecordedPaidRestDays)
        assertEquals(3 * standard, stats.projectedMinutes)
    }

    @Test
    fun `夜班按最近一天班次延续`() {
        val records = recordedThirteen().dropLast(1) +
            rec(13, standard, shift = "夜班") +
            pendingSeventeen()
        val stats = PayrollPresenter.projectionStats(records, today, standard)
        assertEquals(1, stats.recordedNightShifts)
        // 最近班次 = 夜班 → 未记录的 15 个上班日也算夜班
        assertEquals(1 + 15, stats.projectedNightShifts)
        assertFalse(stats.nightShiftsFromOverride)
    }

    @Test
    fun `用户填的夜班天数优先于延续推算`() {
        val stats = PayrollPresenter.projectionStats(
            recordedThirteen() + pendingSeventeen(), today, standard, nightShiftsOverride = 10
        )
        assertEquals(10, stats.projectedNightShifts)
        assertTrue(stats.nightShiftsFromOverride)
    }

    @Test
    fun `月已走完时 hasProjection 为 false`() {
        val stats = PayrollPresenter.projectionStats(recordedThirteen(), today, standard)
        assertEquals(0, stats.unrecordedDays)
        assertFalse(stats.hasProjection)
    }

    @Test
    fun `hoursLabel 整小时不带小数 半小时代一位`() {
        assertEquals("0h", PayrollPresenter.hoursLabel(0))
        assertEquals("11h", PayrollPresenter.hoursLabel(660))
        assertEquals("10.5h", PayrollPresenter.hoursLabel(630))
        assertEquals("308h", PayrollPresenter.hoursLabel(18480))
        assertEquals("143h", PayrollPresenter.hoursLabel(8580))
    }

    @Test
    fun `projectionBasisText 反映上班日与带薪休息日`() {
        val stats = PayrollPresenter.projectionStats(
            recordedThirteen() + pendingSeventeen(), today, standard
        )
        val text = PayrollPresenter.projectionBasisText(stats, standard)
        assertTrue(text, text.contains("已记 143h"))
        assertTrue(text, text.contains("15 个上班日"))
        assertTrue(text, text.contains("= 308h"))
        assertTrue(text, text.contains("节假日 2 天带薪不计工时"))
    }

    @Test
    fun `没有带薪休息日时依据文案不带括号注`() {
        val stats = PayrollPresenter.projectionStats(
            recordedThirteen() + pendingSeventeen().filterNot { it.dayKind == DayKind.FESTIVAL || it.dayKind == DayKind.HOLIDAY_REST },
            today,
            standard,
        )
        val text = PayrollPresenter.projectionBasisText(stats, standard)
        assertFalse(text, text.contains("带薪不计工时"))
    }
}
