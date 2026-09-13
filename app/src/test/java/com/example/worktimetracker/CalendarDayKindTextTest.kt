package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.DayKind
import com.example.worktimetracker.ui.dayKindText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 日期卡片 / 详情弹窗公休文案的护栏。
 *
 * 起因（用户 2026-09-13 反馈）：周末上了班，卡片却仍写"周末休息"，与旁边的"白 11h"自相矛盾。
 * 规则：休息类日子（周末 / 假期休息日）的文案必须由**当天是否出工**决定；节日当天恒显节日名。
 */
class CalendarDayKindTextTest {

    @Test fun weekendWithoutWorkSaysRest() {
        assertEquals("周末休息", dayKindText(DayKind.WEEKEND, null, worked = false))
    }

    @Test fun weekendWithWorkSaysAttended() {
        assertEquals("周末出勤", dayKindText(DayKind.WEEKEND, null, worked = true))
    }

    @Test fun holidayRestDayFollowsWorkState() {
        assertEquals("假期休息", dayKindText(DayKind.HOLIDAY_REST, null, worked = false))
        assertEquals("假期出勤", dayKindText(DayKind.HOLIDAY_REST, null, worked = true))
    }

    @Test fun makeupWorkdayAlwaysSaysMakeup() {
        assertEquals("调休上班", dayKindText(DayKind.MAKEUP_WORKDAY, null, worked = false))
        assertEquals("调休上班", dayKindText(DayKind.MAKEUP_WORKDAY, null, worked = true))
    }

    @Test fun festivalAlwaysShowsFestivalName() {
        assertEquals("中秋节", dayKindText(DayKind.FESTIVAL, "中秋节", worked = false))
        assertEquals("中秋节", dayKindText(DayKind.FESTIVAL, "中秋节", worked = true))
    }

    @Test fun ordinaryWorkdayHasNoExtraText() {
        assertNull(dayKindText(DayKind.WORKDAY, null, worked = false))
        assertNull(dayKindText(DayKind.WORKDAY, null, worked = true))
    }

    @Test fun nullFestivalNameStaysNull() {
        assertNull(dayKindText(DayKind.FESTIVAL, null, worked = true))
    }

    @Test fun restTextNeverContradictsWorkedShift() {
        // 全枚举扫一遍：只要当天出工，就绝不能出现"休息"二字
        DayKind.entries.forEach { kind ->
            val text = dayKindText(kind, "中秋节", worked = true) ?: return@forEach
            assertFalse("$kind 出工时不应出现「休息」：$text", text.contains("休息"))
        }
    }
}
