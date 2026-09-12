package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.ChineseCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 农历换算与法定节日推导回归测试。
 *
 * 验证基准（2026-09-13）：本算法算出的 2020–2026 全部 43 个「节日 × 年份」组合，
 * 100% 落在国务院公告（gov.cn）的实际放假区间内；2026 年与项目人工核对过的
 * 内嵌表逐日一致。
 */
class ChineseCalendarTest {

    private fun date(s: String) = LocalDate.parse(s)

    private fun statutory(year: Int) = ChineseCalendar.statutoryFestivals(year)

    // ---------- 与国务院公告一致的已知值 ----------

    @Test
    fun `springFestivalDaysAreComputedFromLunarCalendar`() {
        // 2025：除夕 1/28、初一 1/29（2024 年修订后除夕纳入法定）
        assertEquals("2025 除夕", date("2025-01-28"), ChineseCalendar.lunarToSolar(2025, 1, 1).minusDays(1))
        assertEquals("2025 初一", date("2025-01-29"), ChineseCalendar.lunarToSolar(2025, 1, 1))
        // 2026：除夕 2/16、初一 2/17 —— 与项目内嵌表完全一致
        assertEquals("2026 除夕", date("2026-02-16"), ChineseCalendar.lunarToSolar(2026, 1, 1).minusDays(1))
        assertEquals("2026 初一", date("2026-02-17"), ChineseCalendar.lunarToSolar(2026, 1, 1))
        // 2027：初一 2/6
        assertEquals("2027 初一", date("2027-02-06"), ChineseCalendar.lunarToSolar(2027, 1, 1))
    }

    @Test
    fun `dragonBoatAndMidAutumnMatchOfficialDates`() {
        assertEquals("2025 端午", date("2025-05-31"), ChineseCalendar.lunarToSolar(2025, 5, 5))
        assertEquals("2026 端午", date("2026-06-19"), ChineseCalendar.lunarToSolar(2026, 5, 5))
        assertEquals("2027 端午", date("2027-06-09"), ChineseCalendar.lunarToSolar(2027, 5, 5))

        assertEquals("2025 中秋", date("2025-10-06"), ChineseCalendar.lunarToSolar(2025, 8, 15))
        assertEquals("2026 中秋", date("2026-09-25"), ChineseCalendar.lunarToSolar(2026, 8, 15))
        assertEquals("2027 中秋", date("2027-09-15"), ChineseCalendar.lunarToSolar(2027, 8, 15))
    }

    @Test
    fun `qingmingSolarTermMatchesOfficialDates`() {
        assertEquals(date("2020-04-04"), ChineseCalendar.qingming(2020))
        assertEquals(date("2021-04-04"), ChineseCalendar.qingming(2021))
        assertEquals(date("2022-04-05"), ChineseCalendar.qingming(2022))
        assertEquals(date("2023-04-05"), ChineseCalendar.qingming(2023))
        assertEquals(date("2024-04-04"), ChineseCalendar.qingming(2024))
        assertEquals(date("2025-04-04"), ChineseCalendar.qingming(2025))
        assertEquals(date("2026-04-05"), ChineseCalendar.qingming(2026))
    }

    @Test
    fun `leapMonthsMatchKnownLunarYears`() {
        // 2020 闰四月、2023 闰二月、2025 闰六月、2028 闰五月
        assertEquals(4, ChineseCalendar.leapMonth(2020))
        assertEquals(2, ChineseCalendar.leapMonth(2023))
        assertEquals(6, ChineseCalendar.leapMonth(2025))
        assertEquals(5, ChineseCalendar.leapMonth(2028))
        // 2021 / 2022 / 2024 / 2026 无闰月
        listOf(2021, 2022, 2024, 2026).forEach {
            assertEquals("$it 应为无闰月", 0, ChineseCalendar.leapMonth(it))
        }
    }

    // ---------- 展示口径：只有法定节日当天带名字 ----------

    @Test
    fun `statutoryFestivals2026MatchTheVerifiedEmbeddedTable`() {
        val days = statutory(2026)
        // 与 ChinaHolidayProvider 内嵌表逐日一致
        assertEquals("元旦", days["2026-01-01"])
        listOf("2026-02-16", "2026-02-17", "2026-02-18", "2026-02-19").forEach {
            assertEquals("$it 春节", "春节", days[it])
        }
        assertEquals("清明节", days["2026-04-05"])
        assertEquals("劳动节", days["2026-05-01"])
        assertEquals("劳动节", days["2026-05-02"])
        assertEquals("端午节", days["2026-06-19"])
        assertEquals("中秋节", days["2026-09-25"])
        listOf("2026-10-01", "2026-10-02", "2026-10-03").forEach {
            assertEquals("$it 国庆节", "国庆节", days[it])
        }
        assertEquals("2026 年法定节日共 13 天", 13, days.size)
    }

    @Test
    fun `holidayRestDaysAreNotMarkedAsFestival`() {
        val days = statutory(2026)
        // 中秋假期 9/26、9/27 是"休"，不能带节日名（用户报过的缺陷）
        assertNull(days["2026-09-26"])
        assertNull(days["2026-09-27"])
        // 春节假期里的补休日同样不带名字
        assertNull(days["2026-02-15"])
        assertNull(days["2026-02-20"])
    }

    @Test
    fun `springFestivalHasThreeStatutoryDaysBefore2025Revision`() {
        val days2024 = statutory(2024)
        // 2024 年除夕（2/9）尚未纳入法定，法定为初一~初三
        assertNull("2024 除夕不应为法定日", days2024["2024-02-09"])
        listOf("2024-02-10", "2024-02-11", "2024-02-12").forEach {
            assertEquals("$it 春节", "春节", days2024[it])
        }
        assertNull(days2024["2024-02-13"])
    }

    // ---------- 真实存在的重合日 ----------

    @Test
    fun `overlappingMidAutumnAndNationalDayAreMerged`() {
        // 2028-10-03 与 2031-10-01 中秋与国庆同日（真实天文重合）
        assertEquals("中秋节·国庆节", statutory(2028)["2028-10-03"])
        assertEquals("中秋节·国庆节", statutory(2031)["2031-10-01"])
    }

    // ---------- 全覆盖 & 健壮性 ----------

    @Test
    fun `everyYearHasThirteenStatutoryDaysAcrossWideRange`() {
        for (year in 2025..2050) {
            val days = statutory(year)
            val count = days.size
            assertTrue("$year 法定日数量异常: $count", count == 13 || count == 12)
            // 每个节日名都必须出现（重合年份会合并到同一天）
            val names = days.values.joinToString("·")
            listOf("元旦", "春节", "清明节", "劳动节", "端午节", "中秋节", "国庆节").forEach {
                assertTrue("$year 缺节日 $it", names.contains(it))
            }
        }
    }

    @Test
    fun `everyStatutoryDayOf2026IsInsideItsOfficialHolidaySpan`() {
        // 官方 2026 放假区间（国务院办公厅国办发明电〔2025〕7 号）
        val spans = listOf(
            "2026-01-01" to "2026-01-03",
            "2026-02-15" to "2026-02-23",
            "2026-04-04" to "2026-04-06",
            "2026-05-01" to "2026-05-05",
            "2026-06-19" to "2026-06-21",
            "2026-09-25" to "2026-09-27",
            "2026-10-01" to "2026-10-07"
        ).map { date(it.first) to date(it.second) }

        statutory(2026).keys.forEach { key ->
            val day = date(key)
            assertTrue(
                "$day 是法定节日，却不在任何官方放假区间内（说明农历算法或公告表出错）",
                spans.any { !day.isBefore(it.first) && !day.isAfter(it.second) }
            )
        }
    }

    @Test
    fun `lunarConversionRejectsOutOfRangeInput`() {
        listOf(1899, 2101).forEach { year ->
            runCatching { ChineseCalendar.lunarToSolar(year, 1, 1) }
                .onSuccess { throw AssertionError("$year 超出 1900–2100，应拒绝") }
        }
        runCatching { ChineseCalendar.lunarToSolar(2026, 13, 1) }
            .onSuccess { throw AssertionError("农历 13 月不存在") }
    }
}
