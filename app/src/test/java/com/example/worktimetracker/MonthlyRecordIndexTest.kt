package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.ui.MonthlyRecordIndex
import org.junit.Assert.assertEquals
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
}
