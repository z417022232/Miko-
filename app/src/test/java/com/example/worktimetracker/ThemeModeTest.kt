package com.example.worktimetracker

import com.example.worktimetracker.ui.theme.DayNightSchedule
import com.example.worktimetracker.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 主题模式与按时间自动切换测试。 */
class ThemeModeTest {

    // ---------- 解析容错 ----------

    @Test
    fun `modeParsingIsForgivingAndDefaultsToAutoTime`() {
        assertEquals(ThemeMode.default, ThemeMode.AUTO_TIME)
        assertEquals(ThemeMode.AUTO_TIME, ThemeMode.parse(null))
        assertEquals(ThemeMode.AUTO_TIME, ThemeMode.parse(""))
        assertEquals(ThemeMode.AUTO_TIME, ThemeMode.parse("乱码"))
        // 大小写不敏感，兼容历史写入
        assertEquals(ThemeMode.DARK, ThemeMode.parse("dark"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(" System "))
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.parse(it.name)) }
    }

    // ---------- 昼夜边界 ----------

    @Test
    fun `dayAndNightBoundariesAreExact`() {
        // 06:59 深色 / 07:00 浅色 / 18:59 浅色 / 19:00 深色
        assertTrue("06:59 应深色", DayNightSchedule.isDark(LocalTime.of(6, 59)))
        assertFalse("07:00 应浅色", DayNightSchedule.isDark(LocalTime.of(7, 0)))
        assertFalse("18:59 应浅色", DayNightSchedule.isDark(LocalTime.of(18, 59)))
        assertTrue("19:00 应深色", DayNightSchedule.isDark(LocalTime.of(19, 0)))
        assertTrue("午夜应深色", DayNightSchedule.isDark(LocalTime.MIDNIGHT))
    }

    @Test
    fun `nextSwitchIsTheUpcomingBoundary`() {
        // 深夜 → 当天 07:00
        assertEquals(
            LocalDateTime.of(2026, 9, 13, 7, 0),
            DayNightSchedule.nextSwitchAt(LocalDateTime.of(2026, 9, 13, 3, 30))
        )
        // 上午 → 当天 19:00
        assertEquals(
            LocalDateTime.of(2026, 9, 13, 19, 0),
            DayNightSchedule.nextSwitchAt(LocalDateTime.of(2026, 9, 13, 9, 0))
        )
        // 晚间 → 次日 07:00
        assertEquals(
            LocalDateTime.of(2026, 9, 14, 7, 0),
            DayNightSchedule.nextSwitchAt(LocalDateTime.of(2026, 9, 13, 22, 15))
        )
    }

    @Test
    fun `millisUntilNextSwitchIsPositiveAndBoundedWithinADay`() {
        listOf(0, 3, 7, 12, 19, 23).forEach { hour ->
            val now = LocalDateTime.of(2026, 9, 13, hour, 0)
            val millis = DayNightSchedule.millisUntilNextSwitch(now)
            assertTrue("$hour 时延迟必须为正", millis > 0)
            assertTrue("$hour 时延迟不得超过 24 小时", millis <= 24L * 60 * 60 * 1000)
        }
        // 恰好落在切换点上：必须跳到下一个边界，不能返回 0 造成死循环
        val atBoundary = LocalDateTime.of(2026, 9, 13, 19, 0)
        assertTrue(DayNightSchedule.millisUntilNextSwitch(atBoundary) >= 1_000L)
    }

    // ---------- 模式解析结果 ----------

    @Test
    fun `resolveHonoursEachMode`() {
        val day = LocalDateTime.of(2026, 9, 13, 12, 0)
        val night = LocalDateTime.of(2026, 9, 13, 23, 0)

        assertFalse("自动·白天", DayNightSchedule.resolve(ThemeMode.AUTO_TIME, day, systemDark = true))
        assertTrue("自动·夜间", DayNightSchedule.resolve(ThemeMode.AUTO_TIME, night, systemDark = false))
        assertTrue("跟随系统·深色", DayNightSchedule.resolve(ThemeMode.SYSTEM, day, systemDark = true))
        assertFalse("跟随系统·浅色", DayNightSchedule.resolve(ThemeMode.SYSTEM, night, systemDark = false))
        assertFalse("始终浅色不受时间影响", DayNightSchedule.resolve(ThemeMode.LIGHT, night, systemDark = true))
        assertTrue("始终深色不受时间影响", DayNightSchedule.resolve(ThemeMode.DARK, day, systemDark = false))
    }

    @Test
    fun `autoTimeCoversWholeDayWithoutGap`() {
        // 一天 1440 分钟必须被浅/深两段完整覆盖，不存在未定义时刻
        var minutes = 0
        var darkCount = 0
        while (minutes < 24 * 60) {
            val time = LocalTime.of(minutes / 60, minutes % 60)
            if (DayNightSchedule.isDark(time)) darkCount++
            minutes++
        }
        assertEquals("深色时段 = 00:00–07:00 与 19:00–24:00 共 12 小时", 12 * 60, darkCount)
        assertEquals("浅色时段 12 小时", 12 * 60, 24 * 60 - darkCount)
    }

    @Test
    fun `scheduleUsesConfiguredHours`() {
        assertEquals(7, DayNightSchedule.LIGHT_FROM_HOUR)
        assertEquals(19, DayNightSchedule.DARK_FROM_HOUR)
        assertEquals(LocalDate.of(2026, 9, 13).atTime(7, 0), DayNightSchedule.lightStart.atDate(LocalDate.of(2026, 9, 13)))
    }
}
