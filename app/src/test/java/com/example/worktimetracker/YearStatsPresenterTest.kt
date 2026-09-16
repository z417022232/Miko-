package com.example.worktimetracker

import com.example.worktimetracker.data.entity.MonthlySalaryEntity
import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.engine.DayKind
import com.example.worktimetracker.domain.engine.HolidayCalendar
import com.example.worktimetracker.ui.YearStatsPresenter
import com.example.worktimetracker.ui.YearStatsPresenter.RestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 「今日」Tab = XX 年数据统计 的纯逻辑护栏。
 *
 * 三类容易回归的点：
 * 1. 月度序列（12 个月恒在、工时/出勤日汇总、实发只认**已录入**的计薪月）；
 * 2. 休息日口径的自适应判定（日均 ≤8h 含周末，>8h 只算法定节假日）；
 * 3. 年休息日的计数与"下一个休息日"定位。
 *
 * 基准年 2026（有内嵌公告表）：9/19 周六、9/20 调休上班、9/25 中秋节、
 * 9/26 假期休息日，正好覆盖四种 [DayKind]。
 */
class YearStatsPresenterTest {

    private val today = LocalDate.of(2026, 9, 16)

    private fun rec(workDate: String, minutes: Int) =
        WorkRecordEntity(workDate = workDate, status = "白班", shift = "白班", finalMinutes = minutes)

    private fun sal(payrollMonth: String, netCents: Long) =
        MonthlySalaryEntity(month = payrollMonth, netSalaryCents = netCents, payrollMonth = payrollMonth)

    private fun build(
        records: List<WorkRecordEntity> = emptyList(),
        salaries: List<MonthlySalaryEntity> = emptyList(),
        fallbackDailyMinutes: Int = YearStatsPresenter.WEEKEND_SCOPE_THRESHOLD_MINUTES
    ) = YearStatsPresenter.build(
        year = 2026,
        records = records,
        salaries = salaries,
        today = today,
        fallbackDailyMinutes = fallbackDailyMinutes
    )

    // ------------------------------------------------------------- 月度序列

    @Test
    fun `一年恒返回 12 个月，序号 1 到 12`() {
        val stats = build()
        assertEquals(12, stats.months.size)
        assertEquals((1..12).toList(), stats.months.map { it.month })
    }

    @Test
    fun `月度工时按 finalMinutes 汇总，出勤日只算大于 0 的天`() {
        val stats = build(
            records = listOf(
                rec("2026-01-05", 480),
                rec("2026-01-06", 600),
                rec("2026-03-10", 0)
            )
        )
        val jan = stats.months.first { it.month == 1 }
        assertEquals(1080, jan.minutes)
        assertEquals(2, jan.workedDays)

        val mar = stats.months.first { it.month == 3 }
        assertEquals(0, mar.minutes)
        assertEquals(0, mar.workedDays)

        assertEquals(1080, stats.totalMinutes)
        assertEquals(2, stats.workedDays)
    }

    @Test
    fun `跨年记录不计入本年度`() {
        val stats = build(records = listOf(rec("2025-12-31", 480), rec("2027-01-01", 480)))
        assertEquals(0, stats.totalMinutes)
        assertEquals(0, stats.workedDays)
    }

    @Test
    fun `实发只认已录入的计薪月，未录入与零值都留空`() {
        val stats = build(
            salaries = listOf(
                sal("2026-01", 500_000),
                sal("2026-02", 0),          // 明确为零 → 不当作"已录入实发"
                sal("2025-12", 999_900)     // 去年 → 不计入
            )
        )
        assertEquals(500_000L, stats.months.first { it.month == 1 }.salaryCents)
        assertNull(stats.months.first { it.month == 2 }.salaryCents)
        assertNull(stats.months.first { it.month == 12 }.salaryCents)
    }

    // ------------------------------------------------------------- 口径判定

    @Test
    fun `日均不超过 8h 走含周末口径，超过则只算法定节假日`() {
        val atThreshold = build(records = listOf(rec("2026-01-05", 480)))
        assertEquals(RestScope.INCLUDE_WEEKEND, atThreshold.rest.scope)
        assertEquals(480, atThreshold.rest.averageDailyMinutes)

        val aboveThreshold = build(records = listOf(rec("2026-01-05", 481)))
        assertEquals(RestScope.FESTIVAL_ONLY, aboveThreshold.rest.scope)
        assertEquals(481, aboveThreshold.rest.averageDailyMinutes)
    }

    @Test
    fun `没有任何出勤记录时回落到传入的默认工时`() {
        val shortDay = build(fallbackDailyMinutes = 480)
        assertEquals(RestScope.INCLUDE_WEEKEND, shortDay.rest.scope)
        assertEquals(480, shortDay.rest.averageDailyMinutes)

        val longDay = build(fallbackDailyMinutes = 660)
        assertEquals(RestScope.FESTIVAL_ONLY, longDay.rest.scope)
        assertEquals(660, longDay.rest.averageDailyMinutes)
    }

    @Test
    fun `日均取本年度真实出勤日的均值，休息日不计入`() {
        // 两个长班 + 一个休息日出勤的短班；三个出勤日一起拉低/抬高均值
        val stats = build(
            records = listOf(
                rec("2026-01-05", 660),
                rec("2026-01-06", 660),
                rec("2026-01-10", 120) // 周六加班
            )
        )
        assertEquals((660 + 660 + 120) / 3, stats.rest.averageDailyMinutes)
    }

    // ------------------------------------------------------------- 日期口径

    @Test
    fun `isRestDay 对四种日历身份的判定`() {
        val saturday = LocalDate.of(2026, 9, 19)      // WEEKEND
        val makeupDay = LocalDate.of(2026, 9, 20)     // MAKEUP_WORKDAY
        val festival = LocalDate.of(2026, 9, 25)      // FESTIVAL 中秋节
        val holidayRest = LocalDate.of(2026, 9, 26)   // HOLIDAY_REST
        val workday = LocalDate.of(2026, 9, 16)       // WORKDAY

        assertEquals(DayKind.WEEKEND, HolidayCalendar.info(saturday).kind)
        assertEquals(DayKind.MAKEUP_WORKDAY, HolidayCalendar.info(makeupDay).kind)
        assertEquals(DayKind.FESTIVAL, HolidayCalendar.info(festival).kind)
        assertEquals(DayKind.HOLIDAY_REST, HolidayCalendar.info(holidayRest).kind)
        assertEquals(DayKind.WORKDAY, HolidayCalendar.info(workday).kind)

        // 含周末口径：周末算休，法定节日与假期休息日也算休
        assertTrue(YearStatsPresenter.isRestDay(saturday, RestScope.INCLUDE_WEEKEND))
        assertTrue(YearStatsPresenter.isRestDay(festival, RestScope.INCLUDE_WEEKEND))
        assertTrue(YearStatsPresenter.isRestDay(holidayRest, RestScope.INCLUDE_WEEKEND))

        // 只算法定节假日口径：周末不算
        assertFalse(YearStatsPresenter.isRestDay(saturday, RestScope.FESTIVAL_ONLY))
        assertTrue(YearStatsPresenter.isRestDay(festival, RestScope.FESTIVAL_ONLY))
        assertTrue(YearStatsPresenter.isRestDay(holidayRest, RestScope.FESTIVAL_ONLY))

        // 调休上班日两种口径都不算休息；普通工作日都不算
        assertFalse(YearStatsPresenter.isRestDay(makeupDay, RestScope.INCLUDE_WEEKEND))
        assertFalse(YearStatsPresenter.isRestDay(makeupDay, RestScope.FESTIVAL_ONLY))
        assertFalse(YearStatsPresenter.isRestDay(workday, RestScope.INCLUDE_WEEKEND))
        assertFalse(YearStatsPresenter.isRestDay(workday, RestScope.FESTIVAL_ONLY))
    }

    // ------------------------------------------------------------- 休息日摘要

    @Test
    fun `含周末口径下，下一个休息日是最近的周六`() {
        val rest = build(fallbackDailyMinutes = 480).rest
        assertEquals(RestScope.INCLUDE_WEEKEND, rest.scope)
        assertEquals(LocalDate.of(2026, 9, 19), rest.nextDate)
        assertEquals(DayKind.WEEKEND, rest.nextKind)
        assertNull(rest.nextName)
        assertEquals(3, rest.daysUntilNext)
    }

    @Test
    fun `只算节日口径下，下一个休息日跳过周末直奔中秋节`() {
        val rest = build(fallbackDailyMinutes = 660).rest
        assertEquals(RestScope.FESTIVAL_ONLY, rest.scope)
        assertEquals(LocalDate.of(2026, 9, 25), rest.nextDate)
        assertEquals(DayKind.FESTIVAL, rest.nextKind)
        assertEquals("中秋节", rest.nextName)
        assertEquals(9, rest.daysUntilNext)
    }

    @Test
    fun `休息日出勤算加班，不算休息，下一个休息日顺延`() {
        val saturday = LocalDate.of(2026, 9, 19)
        val rest = YearStatsPresenter.build(
            year = 2026,
            records = listOf(rec("2026-09-19", 480)),
            salaries = emptyList(),
            today = saturday,
            fallbackDailyMinutes = 480
        ).rest
        assertEquals(1, rest.workedOnRest)
        // 今天的周六被加班占掉、明天调休上班，最近的休息日顺延到中秋假期
        assertEquals(LocalDate.of(2026, 9, 25), rest.nextDate)
        assertEquals(DayKind.FESTIVAL, rest.nextKind)
        assertEquals(6, rest.daysUntilNext)
    }

    @Test
    fun `休息日记录为 0 分钟不算加班，仍算休息`() {
        val rest = build(
            records = listOf(rec("2026-09-19", 0)),
            fallbackDailyMinutes = 480
        ).rest
        assertEquals(0, rest.workedOnRest)
        assertEquals(LocalDate.of(2026, 9, 19), rest.nextDate)
    }

    @Test
    fun `今天本身是休息日且没出勤时，下一个休息日就是今天`() {
        val saturdayToday = LocalDate.of(2026, 9, 19)
        val stats = YearStatsPresenter.build(
            year = 2026, records = emptyList(), salaries = emptyList(),
            today = saturdayToday, fallbackDailyMinutes = 480
        )
        assertEquals(saturdayToday, stats.rest.nextDate)
        assertEquals(0, stats.rest.daysUntilNext)
    }

    @Test
    fun `休息日总数等于已休加未休，且与加班日互补`() {
        val rest = build(
            records = listOf(rec("2026-09-12", 480)), // 9/12 周六（已过去）加班
            fallbackDailyMinutes = 480
        ).rest
        assertEquals(1, rest.workedOnRest)
        assertEquals(rest.taken + rest.ahead, rest.total)

        // 全年"本该休息"的天 = 已经休掉的 + 未来还休的 + 出勤占掉的
        val restDaysInYear = generateSequence(LocalDate.of(2026, 1, 1)) { it.plusDays(1) }
            .takeWhile { it.year == 2026 }
            .count { YearStatsPresenter.isRestDay(it, rest.scope) }
        assertEquals(restDaysInYear, rest.total + rest.workedOnRest)
    }

    @Test
    fun `休息日的记录一律算加班，不会因日期在未来被静默丢弃`() {
        val rest = build(records = listOf(rec("2026-09-26", 600))).rest // 9/26 假期休息日
        assertEquals(1, rest.workedOnRest)
        val restDaysInYear = generateSequence(LocalDate.of(2026, 1, 1)) { it.plusDays(1) }
            .takeWhile { it.year == 2026 }
            .count { YearStatsPresenter.isRestDay(it, rest.scope) }
        assertEquals(restDaysInYear, rest.total + rest.workedOnRest)
    }

    @Test
    fun `两个口径下的全年休息日数差异明显`() {
        val withWeekend = build(fallbackDailyMinutes = 480).rest
        val festivalOnly = build(fallbackDailyMinutes = 660).rest
        assertTrue(
            "含周末口径(${withWeekend.total})应明显多于只算节日口径(${festivalOnly.total})",
            withWeekend.total > festivalOnly.total + 50
        )
        // 只算节日口径下的"休息日"必然也是含周末口径的子集
        assertTrue(festivalOnly.total < withWeekend.total)
    }

    // ------------------------------------------------------------- 年份边界（2026-09-16 复查 P2）

    @Test
    fun `1 月 1 日：全年休息日都还没休完，且下一个休息日就是今天`() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val stats = YearStatsPresenter.build(
            year = 2026, records = emptyList(), salaries = emptyList(),
            today = jan1, fallbackDailyMinutes = 480
        )
        // 口径：taken = 「截止今天（含今天）没出勤的休息日」；ahead = 「今天之后」。
        // 元旦本身就是 2026 年的第一个休息日，所以今天落进 taken 这一个桶。
        assertEquals(1, stats.rest.taken)
        assertEquals(stats.rest.total - 1, stats.rest.ahead)
        assertEquals(stats.rest.total, stats.rest.taken + stats.rest.ahead)
        // 元旦是法定节日 → 今天本身就算休息，nextDate 不越过今天
        assertEquals(DayKind.FESTIVAL, HolidayCalendar.info(jan1).kind)
        assertEquals(jan1, stats.rest.nextDate)
        assertEquals(0, stats.rest.daysUntilNext)
    }

    @Test
    fun `年末：年内不再有未来休息日，nextDate 不会跨到下一年`() {
        val dec31 = LocalDate.of(2026, 12, 31)
        val lastRestInYear = generateSequence(dec31) { it.minusDays(1) }
            .take(60)
            .first { YearStatsPresenter.isRestDay(it, RestScope.INCLUDE_WEEKEND) }

        val stats = YearStatsPresenter.build(
            year = 2026, records = emptyList(), salaries = emptyList(),
            today = dec31, fallbackDailyMinutes = 480
        )
        assertEquals("12/31 之后年内已无休息日", 0, stats.rest.ahead)
        if (lastRestInYear != dec31) {
            assertNull("最后一个休息日已过去，nextDate 必须留空", stats.rest.nextDate)
            assertNull(stats.rest.daysUntilNext)
            assertNull(stats.rest.nextName)
        }
        // 无论如何都不能指向 2027
        assertTrue(stats.rest.nextDate == null || stats.rest.nextDate!!.year == 2026)
    }

    @Test
    fun `月初与月末的记录都归入本年`() {
        val stats = build(records = listOf(rec("2026-01-01", 480), rec("2026-12-31", 600)))
        assertEquals(1080, stats.totalMinutes)
        assertEquals(2, stats.workedDays)
        assertEquals(480, stats.months.first { it.month == 1 }.minutes)
        assertEquals(600, stats.months.first { it.month == 12 }.minutes)
    }

    @Test
    fun `闰年 2 月 29 日的记录归入 2 月，且三桶恒等式仍成立`() {
        val stats = YearStatsPresenter.build(
            year = 2028,
            records = listOf(rec("2028-02-29", 480)),
            salaries = emptyList(),
            today = LocalDate.of(2028, 6, 1),
            fallbackDailyMinutes = 480
        )
        assertEquals(480, stats.months.first { it.month == 2 }.minutes)
        val restDaysInYear = generateSequence(LocalDate.of(2028, 1, 1)) { it.plusDays(1) }
            .takeWhile { it.year == 2028 }
            .count { YearStatsPresenter.isRestDay(it, stats.rest.scope) }
        assertTrue("闰年也要有休息日", restDaysInYear > 0)
        assertEquals(restDaysInYear, stats.rest.total + stats.rest.workedOnRest)
    }

    @Test
    fun `没有内置公告的年份也不能崩，且口径与计数自洽`() {
        val stats = YearStatsPresenter.build(
            year = 2030, records = emptyList(), salaries = emptyList(),
            today = LocalDate.of(2030, 6, 1), fallbackDailyMinutes = 480
        )
        assertEquals(12, stats.months.size)
        assertEquals(RestScope.INCLUDE_WEEKEND, stats.rest.scope)
        val restDaysInYear = generateSequence(LocalDate.of(2030, 1, 1)) { it.plusDays(1) }
            .takeWhile { it.year == 2030 }
            .count { YearStatsPresenter.isRestDay(it, stats.rest.scope) }
        assertTrue(restDaysInYear > 0)
        assertEquals(restDaysInYear, stats.rest.total + stats.rest.workedOnRest)
    }

    @Test
    fun `空年份的总工时为零但月份序列仍完整`() {
        val stats = build()
        assertEquals(0, stats.totalMinutes)
        assertEquals(0, stats.workedDays)
        assertEquals((1..12).toList(), stats.months.map { it.month })
        assertTrue(stats.months.all { it.minutes == 0 && it.salaryCents == null })
    }
}
