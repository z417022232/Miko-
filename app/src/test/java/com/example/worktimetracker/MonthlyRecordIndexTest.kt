package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.engine.DayKind
import com.example.worktimetracker.ui.MonthlyRecordIndex
import com.example.worktimetracker.ui.calendarDayLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class MonthlyRecordIndexTest {
    @Test
    fun `month mapping preserves reviews and fills missing past dates`() {
        val rows = listOf(
            WorkRecordEntity(workDate = "2026-08-01", status = "WORK", shift = "NIGHT_SHIFT", finalMinutes = 600, needsReview = true),
            WorkRecordEntity(workDate = "2026-08-03", status = "WORK", finalMinutes = 480)
        )

        val days = MonthlyRecordIndex.build(YearMonth.of(2026, 8), rows, LocalDate.of(2026, 8, 4), ZoneId.of("Asia/Shanghai"))

        assertEquals(31, days.size)
        assertTrue(days.first().needsReview)
        assertEquals("夜班", days.first().shift)
        assertEquals("休息", days[1].status)
    }

    /**
     * 回归：旧版手动工时对话框把显示标签"白班"写回 shift（真实数据 id=158）。
     * 这类记录仍必须在日历上显示班次标签，而不是因无法匹配枚举名而变空。
     */
    @Test
    fun `legacy chinese shift label still renders shift label in calendar`() {
        val rows = listOf(
            WorkRecordEntity(workDate = "2026-08-11", status = "MANUAL", shift = "白班", finalMinutes = 660),
            WorkRecordEntity(workDate = "2026-07-20", status = "MANUAL", shift = "夜班", finalMinutes = 600)
        )

        val august = MonthlyRecordIndex.build(YearMonth.of(2026, 8), rows, LocalDate.of(2026, 8, 31), ZoneId.of("Asia/Shanghai"))
        assertEquals("白班", august[10].shift)
        assertEquals("白 11h", calendarDayLabel(august[10].shift, august[10].finalMinutes))

        val july = MonthlyRecordIndex.build(YearMonth.of(2026, 7), rows, LocalDate.of(2026, 8, 31), ZoneId.of("Asia/Shanghai"))
        assertEquals("夜班", july[19].shift)

        // 未识别的班次值仍不得强行套用白班标签
        val unknown = MonthlyRecordIndex.build(
            YearMonth.of(2026, 9), listOf(WorkRecordEntity(workDate = "2026-09-05", status = "WORK", shift = "??", finalMinutes = 0)),
            LocalDate.of(2026, 9, 30), ZoneId.of("Asia/Shanghai")
        )
        assertNull(unknown[4].shift)
    }

    /**
     * 用户 2026-09-13 需求：节日当天上班显示 `白/夜 11h + 中秋节`，休息日上班显示 `白/夜 11h + 休`，
     * 调休上班日显示 `白/夜 11h + 班`。网格口径 = dayBadge（第一行）+ calendarDayLabel（第二行）。
     */
    @Test
    fun `workingOnFestivalRestOrMakeupDayKeepsBothDayKindAndHours`() {
        val rows = listOf(
            WorkRecordEntity(workDate = "2026-09-20", status = "WORK", shift = "DAY_SHIFT", finalMinutes = 660),
            WorkRecordEntity(workDate = "2026-09-25", status = "WORK", shift = "DAY_SHIFT", finalMinutes = 660),
            WorkRecordEntity(workDate = "2026-09-26", status = "WORK", shift = "NIGHT_SHIFT", finalMinutes = 660)
        )
        val days = MonthlyRecordIndex.build(YearMonth.of(2026, 9), rows, LocalDate.of(2026, 9, 30), ZoneId.of("Asia/Shanghai"))

        fun lines(i: Int) = listOf(days[i].dayBadge, calendarDayLabel(days[i].shift, days[i].finalMinutes))

        assertEquals("9/20 调休上班 + 白班", listOf("班", "白 11h"), lines(19))
        assertEquals("9/25 中秋节 + 白班", listOf("中秋节", "白 11h"), lines(24))
        assertEquals("9/26 假期休息日 + 夜班", listOf("休", "夜 11h"), lines(25))
        assertEquals(DayKind.MAKEUP_WORKDAY, days[19].dayKind)
        assertEquals(DayKind.FESTIVAL, days[24].dayKind)
        assertEquals(DayKind.HOLIDAY_REST, days[25].dayKind)

        // 9/27 无记录 → 仍显示"休"
        assertEquals("休", days[26].dayBadge)
        assertEquals(0, days[26].finalMinutes)
    }

    @Test
    fun `ordinaryWorkdayHasNoHolidayBadgeAndWeekendShowsRest`() {
        val days = MonthlyRecordIndex.build(
            YearMonth.of(2026, 9), emptyList(), LocalDate.of(2026, 8, 31), ZoneId.of("Asia/Shanghai")
        )
        assertEquals(DayKind.WORKDAY, days[13].dayKind) // 9/14 周一
        assertNull("普通工作日不应有公休标签", days[13].dayBadge)
        assertEquals(DayKind.WEEKEND, days[18].dayKind) // 9/19 周六
        assertEquals("休", days[18].dayBadge)
    }
}
