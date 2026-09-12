package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.ChinaHolidayProvider
import com.example.worktimetracker.domain.engine.DayKind
import com.example.worktimetracker.domain.engine.HolidayArrangement
import com.example.worktimetracker.domain.engine.HolidayCalendar
import com.example.worktimetracker.domain.engine.HolidaySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 运行时节假日合并层测试：**远端覆盖 > 内嵌公告 > 法定节日 + 周末**。
 *
 * 这是"以后节假日怎么获取"的关键保障：无论哪一层缺失，判定都不能崩、不能空窗。
 */
class HolidayCalendarTest {

    private fun date(s: String) = LocalDate.parse(s)

    @After
    fun tearDown() {
        HolidayCalendar.reset()
    }

    @Test
    fun `withoutRemoteDataItFallsBackToEmbeddedTable`() {
        HolidayCalendar.reset()
        // 2026 内嵌公告表生效
        assertEquals(DayKind.HOLIDAY_REST, HolidayCalendar.info(date("2026-09-26")).kind)
        assertEquals("休", HolidayCalendar.badge(date("2026-09-26")))
        assertEquals(DayKind.MAKEUP_WORKDAY, HolidayCalendar.info(date("2026-09-20")).kind)
        assertEquals("班", HolidayCalendar.badge(date("2026-09-20")))
        assertFalse("未加载远端数据", HolidayCalendar.hasOverride())
    }

    @Test
    fun `remoteArrangementOverridesEmbeddedForThatYear`() {
        // 2027 内嵌表没有 → 无远端时只剩周末与法定节日
        HolidayCalendar.reset()
        assertEquals(DayKind.WORKDAY, HolidayCalendar.info(date("2027-03-10")).kind)

        // 注入 2027 远端安排：2/11、2/12 放假，2/13（周六）调休上班。
        // 刻意避开 2027-02-05~02-08（春节法定日，那段由农历算法优先接管）
        HolidayCalendar.apply(
            listOf(
                HolidayArrangement(
                    year = 2027,
                    makeupWorkdays = setOf("2027-02-13"),
                    restDays = setOf("2027-02-11", "2027-02-12"),
                    source = HolidaySource.REMOTE
                )
            )
        )
        assertEquals(DayKind.MAKEUP_WORKDAY, HolidayCalendar.info(date("2027-02-13")).kind)
        assertEquals(DayKind.HOLIDAY_REST, HolidayCalendar.info(date("2027-02-11")).kind)
        assertEquals(HolidaySource.REMOTE, HolidayCalendar.sourceFor(date("2027-02-11")))
        assertTrue(HolidayCalendar.coveredYears().contains(2027))
    }

    @Test
    fun `statutoryFestivalsSurviveWithoutAnyAnnouncementData`() {
        // 关键降级能力：即使某年没有任何公告数据，法定节日当天依然正确
        HolidayCalendar.reset()
        assertEquals("元旦", HolidayCalendar.name(date("2030-01-01")))
        // 2030 除夕 2/2、正月初一 2/3 ⇒ 法定春节为 2/2~2/5
        assertEquals("春节", HolidayCalendar.name(date("2030-02-02")))     // 除夕
        assertEquals("春节", HolidayCalendar.name(date("2030-02-03")))     // 正月初一
        assertEquals("春节", HolidayCalendar.name(date("2030-02-05")))     // 初三
        assertNull("2030-02-06 已出法定春节区间", HolidayCalendar.name(date("2030-02-06")))
        assertEquals("清明节", HolidayCalendar.name(date("2030-04-05")))
        assertEquals("端午节", HolidayCalendar.name(date("2030-06-05")))
        assertEquals("中秋节", HolidayCalendar.name(date("2030-09-12")))
        assertEquals("国庆节", HolidayCalendar.name(date("2030-10-01")))
        // 但"哪几天放假"未知，只能按周末/工作日处理（绝不误判成假期休息）
        val sideDay = HolidayCalendar.info(date("2030-02-06")).kind
        assertTrue(
            "无公告数据时只能按周末/工作日处理，实际=",
            sideDay == DayKind.WORKDAY || sideDay == DayKind.WEEKEND
        )
    }

    @Test
    fun `festivalNamesFromImportedDataTakePriority`() {
        // 一次性纪念日放假这类公告特别指定的名称，可由导入数据补充
        HolidayCalendar.apply(
            listOf(
                HolidayArrangement(
                    year = 2030,
                    festivalNames = mapOf("2030-09-03" to "抗战胜利纪念日"),
                    source = HolidaySource.IMPORTED
                )
            )
        )
        assertEquals(DayKind.FESTIVAL, HolidayCalendar.info(date("2030-09-03")).kind)
        assertEquals("抗战胜利纪念日", HolidayCalendar.name(date("2030-09-03")))
        assertEquals(HolidaySource.IMPORTED, HolidayCalendar.sourceFor(date("2030-09-03")))
    }

    @Test
    fun `resetRestoresEmbeddedBehaviour`() {
        HolidayCalendar.apply(
            listOf(HolidayArrangement(year = 2026, restDays = setOf("2026-09-14"), source = HolidaySource.REMOTE))
        )
        assertEquals(DayKind.HOLIDAY_REST, HolidayCalendar.info(date("2026-09-14")).kind)
        HolidayCalendar.reset()
        // 恢复成内嵌表判定：9/14 是普通周一
        assertEquals(DayKind.WORKDAY, HolidayCalendar.info(date("2026-09-14")).kind)
        assertFalse(HolidayCalendar.hasOverride())
    }

    @Test
    fun `embeddedAndComputedResultsAgreeFor2026`() {
        // 双源交叉校验：内嵌公告表 + 农历算法对 2026 必须完全一致
        HolidayCalendar.reset()
        val embedded = ChinaHolidayProvider.embeddedArrangement(2026)
        requireNotNull(embedded)
        embedded.restDays.forEach { key ->
            assertNull("$key 是假期休息日，不应被算成法定节日", ChinaHolidayProvider.name(date(key)))
        }
        listOf(
            "2026-01-01", "2026-02-16", "2026-02-17", "2026-02-18", "2026-02-19",
            "2026-04-05", "2026-05-01", "2026-05-02", "2026-06-19", "2026-09-25",
            "2026-10-01", "2026-10-02", "2026-10-03"
        ).forEach { key ->
            assertFalse("$key 是法定节日，不应出现在假期休息日集合里", embedded.restDays.contains(key))
            assertFalse("$key 是法定节日，不应出现在调休上班集合里", embedded.makeupWorkdays.contains(key))
        }
    }
}
