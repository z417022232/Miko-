package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.ChinaHolidayProvider
import com.example.worktimetracker.domain.engine.DayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 中国节假日 / 调休 / 周末判定回归测试。
 *
 * 数据依据：国务院办公厅《关于 2026 年部分节假日安排的通知》（国办发明电〔2025〕7 号，2025-11-04）
 * ＋2024 年修订《全国年节及纪念日放假办法》的法定假日天数
 * （元旦 1、春节 4、清明 1、劳动节 2、端午 1、中秋 1、国庆 3 = 13 天）。
 *
 * ⚠️ 核心口径（用户 2026-09-13 报的缺陷）：**只有法定节日当天才带节日名**，
 * 假期里的其余天一律是"休"。旧实现把 9/25、9/26、9/27 全部标成"中秋节"，
 * 其中 9/26、9/27 应该是休。
 */
class ChinaHolidayProviderTest {

    // ---------- 2026 法定节日当天（13 天）----------

    private val festivals2026 = mapOf(
        "2026-01-01" to "元旦",
        "2026-02-16" to "春节", "2026-02-17" to "春节", "2026-02-18" to "春节", "2026-02-19" to "春节",
        "2026-04-05" to "清明节",
        "2026-05-01" to "劳动节", "2026-05-02" to "劳动节",
        "2026-06-19" to "端午节",
        "2026-09-25" to "中秋节",
        "2026-10-01" to "国庆节", "2026-10-02" to "国庆节", "2026-10-03" to "国庆节"
    )

    private val rest2026 = setOf(
        "2026-01-02", "2026-01-03",
        "2026-02-15", "2026-02-20", "2026-02-21", "2026-02-22", "2026-02-23",
        "2026-04-04", "2026-04-06",
        "2026-05-03", "2026-05-04", "2026-05-05",
        "2026-06-20", "2026-06-21",
        "2026-09-26", "2026-09-27",
        "2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07"
    )

    private val makeup2026 = setOf(
        "2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10"
    )

    private fun date(s: String) = LocalDate.parse(s)

    @Test
    fun `festival days are exactly the statutory holidays`() {
        assertEquals("法定节日共 13 天", 13, festivals2026.size)
        festivals2026.forEach { (key, name) ->
            val info = ChinaHolidayProvider.info(date(key))
            assertEquals("$key 应为节日当天", DayKind.FESTIVAL, info.kind)
            assertEquals("$key 节日名", name, info.festivalName)
            assertEquals("$key badge 应为节日名", name, ChinaHolidayProvider.badge(date(key)))
            assertEquals("$key name() 应为节日名", name, ChinaHolidayProvider.name(date(key)))
        }
    }

    @Test
    fun `holiday rest days carry no festival name`() {
        assertEquals("假期休息日共 20 天", 20, rest2026.size)
        rest2026.forEach { key ->
            val info = ChinaHolidayProvider.info(date(key))
            assertEquals("$key 应为假期休息日", DayKind.HOLIDAY_REST, info.kind)
            assertNull("$key 不得带节日名", info.festivalName)
            assertEquals("$key badge 应为休", "休", ChinaHolidayProvider.badge(date(key)))
            assertNull("$key name() 应为 null", ChinaHolidayProvider.name(date(key)))
        }
    }

    @Test
    fun `makeup workdays are flagged and all fall on weekends`() {
        assertEquals("调休上班日共 6 天", 6, makeup2026.size)
        makeup2026.forEach { key ->
            val d = date(key)
            assertEquals("$key 应为调休上班日", DayKind.MAKEUP_WORKDAY, ChinaHolidayProvider.info(d).kind)
            assertEquals("$key badge 应为班", "班", ChinaHolidayProvider.badge(d))
            assertTrue(
                "$key 是调休上班日，理应落在一个周末（否则数据表可能录错）",
                d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
            )
        }
    }

    // ---------- 用户报的缺陷：中秋假期三天不能都叫"中秋节" ----------

    @Test
    fun `midAutumnOnlyFestivalDayIsNamedTheOtherTwoAreRest`() {
        // 9/25 中秋节（周五）；9/26(周六)、9/27(周日) 是休
        assertEquals(DayKind.FESTIVAL, ChinaHolidayProvider.info(date("2026-09-25")).kind)
        assertEquals("中秋节", ChinaHolidayProvider.badge(date("2026-09-25")))

        assertEquals(DayKind.HOLIDAY_REST, ChinaHolidayProvider.info(date("2026-09-26")).kind)
        assertEquals("9/26 应为休", "休", ChinaHolidayProvider.badge(date("2026-09-26")))
        assertNull("9/26 不应再显示中秋节", ChinaHolidayProvider.name(date("2026-09-26")))

        assertEquals(DayKind.HOLIDAY_REST, ChinaHolidayProvider.info(date("2026-09-27")).kind)
        assertEquals("9/27 应为休", "休", ChinaHolidayProvider.badge(date("2026-09-27")))
        assertNull("9/27 不应再显示中秋节", ChinaHolidayProvider.name(date("2026-09-27")))
    }

    @Test
    fun `september20IsMakeupWorkdayForNationalDay`() {
        // 9/20（周日）调给国庆凑 7 天连休
        assertEquals(DayKind.MAKEUP_WORKDAY, ChinaHolidayProvider.info(date("2026-09-20")).kind)
        assertEquals("班", ChinaHolidayProvider.badge(date("2026-09-20")))
    }

    // ---------- 每个假期的放假总天数 = 节日 + 假期休息日（合计 33 天）----------

    @Test
    fun `eachHolidaySpanMatchesOfficialDayCount`() {
        val spans = listOf(
            Triple("元旦", "2026-01-01", "2026-01-03"),   // 3 天
            Triple("春节", "2026-02-15", "2026-02-23"),   // 9 天
            Triple("清明节", "2026-04-04", "2026-04-06"), // 3 天
            Triple("劳动节", "2026-05-01", "2026-05-05"), // 5 天
            Triple("端午节", "2026-06-19", "2026-06-21"), // 3 天
            Triple("中秋节", "2026-09-25", "2026-09-27"), // 3 天
            Triple("国庆节", "2026-10-01", "2026-10-07")  // 7 天
        )
        val expected = listOf(3, 9, 3, 5, 3, 3, 7)
        var total = 0
        spans.forEachIndexed { index, (name, from, to) ->
            var d = date(from)
            val end = date(to)
            var festival = 0
            var rest = 0
            while (!d.isAfter(end)) {
                when (ChinaHolidayProvider.info(d).kind) {
                    DayKind.FESTIVAL -> festival++
                    DayKind.HOLIDAY_REST -> rest++
                    else -> throw AssertionError("$name 区间内 $d 既非节日也非假期休息日")
                }
                d = d.plusDays(1)
            }
            assertEquals("$name 放假天数", expected[index], festival + rest)
            assertTrue("$name 至少有 1 天是节日当天", festival >= 1)
            total += festival + rest
        }
        assertEquals("2026 全年放假调休共 33 天", 33, total)
    }

    @Test
    fun `ordinaryWeekdaysAndWeekendsAreClassifiedNormally`() {
        // 2026-09-14 周一 / 09-19 周六（未调休）
        assertEquals(DayKind.WORKDAY, ChinaHolidayProvider.info(date("2026-09-14")).kind)
        assertNull(ChinaHolidayProvider.badge(date("2026-09-14")))
        assertNull(ChinaHolidayProvider.name(date("2026-09-14")))

        assertEquals(DayKind.WEEKEND, ChinaHolidayProvider.info(date("2026-09-19")).kind)
        assertEquals("休", ChinaHolidayProvider.badge(date("2026-09-19")))
        assertNull(ChinaHolidayProvider.name(date("2026-09-19")))
    }

    // ---------- 全年覆盖 & 未收录年份兜底 ----------

    @Test
    fun `everyDayOf2026IsClassifiedWithoutGap`() {
        var d = LocalDate.of(2026, 1, 1)
        var count = 0
        while (d.year == 2026) {
            ChinaHolidayProvider.info(d) // 不得抛异常
            count++
            d = d.plusDays(1)
        }
        assertEquals(365, count)

        // 周末要么是 WEEKEND，要么被调休成 MAKEUP_WORKDAY，二者必居其一
        val month = YearMonth.of(2026, 9)
        for (day in 1..month.lengthOfMonth()) {
            val dd = month.atDay(day)
            val weekend = dd.dayOfWeek == DayOfWeek.SATURDAY || dd.dayOfWeek == DayOfWeek.SUNDAY
            val kind = ChinaHolidayProvider.info(dd).kind
            if (weekend && kind != DayKind.MAKEUP_WORKDAY && kind != DayKind.HOLIDAY_REST) {
                assertEquals("$dd 是未被调休的周末", DayKind.WEEKEND, kind)
            }
        }
    }

    @Test
    fun `yearsOutsideTableFallBackToFixedSolarFestivalsAndWeekends`() {
        // 2027 未收录：仅公历固定的节日当天可识别
        assertEquals(DayKind.FESTIVAL, ChinaHolidayProvider.info(date("2027-01-01")).kind)
        assertEquals("元旦", ChinaHolidayProvider.badge(date("2027-01-01")))
        assertEquals(DayKind.FESTIVAL, ChinaHolidayProvider.info(date("2027-10-01")).kind)

        // 2027-01-02 是周六 → 周末（兜底不认调休假期）
        assertEquals(DayKind.WEEKEND, ChinaHolidayProvider.info(date("2027-01-02")).kind)
        // 2027-06-19 是周六 → 兜底不会把端午的假期算进来（端午随农历，无法兜底）
        assertNull(ChinaHolidayProvider.name(date("2027-06-19")))
    }
}
