package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkRecordEntity
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
}
