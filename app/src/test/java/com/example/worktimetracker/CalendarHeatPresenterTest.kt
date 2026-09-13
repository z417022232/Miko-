package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.DayInfo
import com.example.worktimetracker.domain.engine.DayKind
import com.example.worktimetracker.ui.CalendarHeatPresenter
import com.example.worktimetracker.ui.HeatLevel
import com.example.worktimetracker.ui.UiDayRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 日历热力月历的纯逻辑护栏（界面稿 v4 屏 01）。
 *
 * 覆盖三类容易回归的点：
 * 1. 格子网格的补空位置（1 号落在哪一列）；
 * 2. 底色/文本档位的判定（满勤 / 不足 / 休息 / 节日 / 调休 / 无记录）；
 * 3. 汇总口径（加班只算超出法定 8h 的部分，日均按出勤天数）。
 */
class CalendarHeatPresenterTest {

    private val today = LocalDate.of(2026, 9, 13)
    private val month = YearMonth.of(2026, 9)

    private fun workdayInfo(): (LocalDate) -> DayInfo = { DayInfo(DayKind.WORKDAY) }
    private fun weekendInfo(): (LocalDate) -> DayInfo = { DayInfo(DayKind.WEEKEND) }
    private fun festivalInfo(name: String): (LocalDate) -> DayInfo = { DayInfo(DayKind.FESTIVAL, name) }
    private fun restInfo(): (LocalDate) -> DayInfo = { DayInfo(DayKind.HOLIDAY_REST) }
    private fun makeupInfo(): (LocalDate) -> DayInfo = { DayInfo(DayKind.MAKEUP_WORKDAY) }

    private fun record(
        day: Int,
        minutes: Int,
        endMillis: Long? = 1L,
        needsReview: Boolean = false
    ) = UiDayRecord(
        date = LocalDate.of(2026, 9, day),
        status = "白班",
        finalMinutes = minutes,
        startMillis = 1L,
        endMillis = endMillis,
        needsReview = needsReview
    )

    // ------------------------------------------------------------- 网格

    @Test
    fun `2026年9月1日是周二，前补两格`() {
        val cells = CalendarHeatPresenter.buildCells(month, emptyList(), today, today, workdayInfo())
        assertEquals(0, cells.size % 7)
        assertNull(cells[0])
        assertNull(cells[1])
        assertEquals(1, cells[2]!!.dayOfMonth)
        // 1..30 全部有格子
        assertEquals(30, cells.filterNotNull().size)
        assertEquals(30, cells[31]!!.dayOfMonth)
    }

    @Test
    fun `前导空位数随月份变化，总是7的整数倍`() {
        for (m in 1..12) {
            val ym = YearMonth.of(2026, m)
            val cells = CalendarHeatPresenter.buildCells(ym, emptyList(), today, today, workdayInfo())
            assertEquals(0, cells.size % 7)
            assertEquals(ym.lengthOfMonth(), cells.filterNotNull().size)
            val leading = ym.atDay(1).dayOfWeek.value % 7
            assertEquals(leading, cells.indexOfFirst { it != null })
        }
    }

    // ------------------------------------------------------------- 档位

    @Test
    fun `满勤8小时及以上是FULL`() {
        val cells = CalendarHeatPresenter.buildCells(
            month, listOf(record(1, 480), record(2, 510)), today, today, workdayInfo()
        )
        assertEquals(HeatLevel.FULL, cells[2]?.heat)   // 9/1
        assertEquals(HeatLevel.FULL, cells[3]?.heat)   // 9/2
        assertEquals("8.0", cells[2]?.text)
        assertEquals("8.5", cells[3]?.text)
    }

    @Test
    fun `不满8小时是PARTIAL且文本仍显示工时`() {
        val cells = CalendarHeatPresenter.buildCells(month, listOf(record(3, 247)), today, today, workdayInfo())
        assertEquals(HeatLevel.PARTIAL, cells[4]?.heat)
        assertEquals("4.1", cells[4]?.text)
    }

    @Test
    fun `零工时的工作日显示破折号并压暗`() {
        val cells = CalendarHeatPresenter.buildCells(month, emptyList(), today, today, workdayInfo())
        val cell = cells[2]!!
        assertEquals(HeatLevel.NONE, cell.heat)
        assertEquals("—", cell.text)
        assertTrue(cell.isDim)
        assertFalse(cell.isRest)
    }

    @Test
    fun `周末没有出工算休息日，文本为休`() {
        val cells = CalendarHeatPresenter.buildCells(month, emptyList(), today, today, weekendInfo())
        val cell = cells[2]!!
        assertTrue(cell.isRest)
        assertFalse(cell.isDim)
        assertEquals("休", cell.text)
    }

    @Test
    fun `周末出工打虚线框标记`() {
        val cells = CalendarHeatPresenter.buildCells(month, listOf(record(5, 498)), today, today, weekendInfo())
        val cell = cells[6]!!   // 9/5 周六
        assertTrue(cell.weekendWork)
        assertFalse(cell.isRest)
        assertEquals(HeatLevel.FULL, cell.heat)
    }

    @Test
    fun `工作日出工不打虚线框`() {
        val cells = CalendarHeatPresenter.buildCells(month, listOf(record(1, 498)), today, today, workdayInfo())
        assertFalse(cells[2]!!.weekendWork)
    }

    @Test
    fun `法定节日当天用短名并标紫`() {
        val cells = CalendarHeatPresenter.buildCells(
            month, emptyList(), today, today, festivalInfo("中秋节")
        )
        val cell = cells[2]!!
        assertTrue(cell.isFestival)
        assertEquals("中秋", cell.text)
        assertFalse(cell.isDim)
    }

    @Test
    fun `节日重合日只取第一个名字`() {
        val cells = CalendarHeatPresenter.buildCells(
            month, emptyList(), today, today, festivalInfo("中秋节·国庆节")
        )
        assertEquals("中秋", cells[2]!!.text)
    }

    @Test
    fun `调休上班日无出工时显示班`() {
        val cells = CalendarHeatPresenter.buildCells(month, emptyList(), today, today, makeupInfo())
        val cell = cells[2]!!
        assertTrue(cell.isMakeup)
        assertEquals("班", cell.text)
        assertFalse(cell.isDim)
    }

    @Test
    fun `假期休息日按休息处理`() {
        val cells = CalendarHeatPresenter.buildCells(month, emptyList(), today, today, restInfo())
        assertTrue(cells[2]!!.isRest)
        assertEquals("休", cells[2]!!.text)
    }

    @Test
    fun `只有上班卡时标记缺卡`() {
        val cells = CalendarHeatPresenter.buildCells(
            month, listOf(record(10, 360, endMillis = null)), today, today, workdayInfo()
        )
        assertTrue(cells[11]!!.missingPunch)
    }

    @Test
    fun `待确认也标记缺卡`() {
        val cells = CalendarHeatPresenter.buildCells(
            month, listOf(record(10, 360, needsReview = true)), today, today, workdayInfo()
        )
        assertTrue(cells[11]!!.missingPunch)
    }

    @Test
    fun `完整出勤不标记缺卡`() {
        val cells = CalendarHeatPresenter.buildCells(month, listOf(record(10, 480)), today, today, workdayInfo())
        assertFalse(cells[11]!!.missingPunch)
    }

    @Test
    fun `今天与选中态按日期标记`() {
        val cells = CalendarHeatPresenter.buildCells(
            month, emptyList(), today, LocalDate.of(2026, 9, 20), workdayInfo()
        )
        assertTrue(cells[14]!!.isToday)      // 9/13
        assertTrue(cells[21]!!.isSelected)   // 9/20
        assertFalse(cells[15]!!.isToday)
    }

    // ------------------------------------------------------------- 汇总

    @Test
    fun `汇总统计工时出勤加班与日均`() {
        // 8h + 11h + 4h = 23h；出勤 3 天；加班 = 0 + 180 + 0 = 180
        val records = listOf(record(1, 480), record(2, 660), record(3, 240))
        val s = CalendarHeatPresenter.summarize(records)
        assertEquals(1380, s.totalMinutes)
        assertEquals(3, s.workDays)
        assertEquals(180, s.overtimeMinutes)
        assertEquals(460, s.averageMinutes)
    }

    @Test
    fun `零工时的天不计入出勤与加班`() {
        val records = listOf(record(1, 0), record(2, 0))
        val s = CalendarHeatPresenter.summarize(records)
        assertEquals(0, s.totalMinutes)
        assertEquals(0, s.workDays)
        assertEquals(0, s.overtimeMinutes)
        assertEquals(0, s.averageMinutes)
    }

    @Test
    fun `空记录汇总全为零，不除零`() {
        val s = CalendarHeatPresenter.summarize(emptyList())
        assertEquals(0, s.totalMinutes)
        assertEquals(0, s.averageMinutes)
    }

    @Test
    fun `加班口径可按参数覆盖`() {
        val s = CalendarHeatPresenter.summarize(listOf(record(1, 660)), standardMinutes = 660)
        assertEquals(0, s.overtimeMinutes)
    }

    // ------------------------------------------------------------- 假期提示

    @Test
    fun `下一个假期从明天开始找，不报今天`() {
        // 9/25 与 9/27 都算节日：从 9/25 出发必须跳过它、报 9/27
        val tip = CalendarHeatPresenter.nextHoliday(
            LocalDate.of(2026, 9, 25),
            { d ->
                if (d == LocalDate.of(2026, 9, 25) || d == LocalDate.of(2026, 9, 27)) {
                    DayInfo(DayKind.FESTIVAL, "中秋节")
                } else {
                    DayInfo(DayKind.WORKDAY)
                }
            },
            maxDays = 10
        )
        assertEquals(LocalDate.of(2026, 9, 27), tip?.date)
        assertEquals(2L, tip?.daysUntil)
    }

    @Test
    fun `找不到节日时返回null而不是死循环`() {
        val tip = CalendarHeatPresenter.nextHoliday(today, workdayInfo(), maxDays = 30)
        assertNull(tip)
    }

    @Test
    fun `假期提示文案符合稿子格式`() {
        val tip = CalendarHeatPresenter.nextHoliday(
            today,
            { d -> if (d == LocalDate.of(2026, 9, 25)) DayInfo(DayKind.FESTIVAL, "中秋节") else DayInfo(DayKind.WORKDAY) }
        )!!
        assertEquals("下一个假期：9 月 25 日 中秋（周五）· 还有 12 天", CalendarHeatPresenter.holidayTipText(tip))
    }

    // ------------------------------------------------------------- 文案工具

    @Test
    fun `工时时长文本固定一位小数`() {
        assertEquals("0.0", CalendarHeatPresenter.hoursText(0))
        assertEquals("8.0", CalendarHeatPresenter.hoursText(480))
        assertEquals("8.5", CalendarHeatPresenter.hoursText(510))
        assertEquals("11.0", CalendarHeatPresenter.hoursText(660))
        assertEquals("0.5", CalendarHeatPresenter.hoursText(30))
    }

    @Test
    fun `负数工时被夹到零`() {
        assertEquals("0.0", CalendarHeatPresenter.hoursText(-60))
    }

    @Test
    fun `节日短名去掉尾字节`() {
        assertEquals("中秋", CalendarHeatPresenter.shortFestivalName("中秋节"))
        assertEquals("元旦", CalendarHeatPresenter.shortFestivalName("元旦"))
        assertEquals("劳动", CalendarHeatPresenter.shortFestivalName("劳动节"))
        assertEquals("国庆", CalendarHeatPresenter.shortFestivalName("国庆节"))
    }

    @Test
    fun `周几短标签`() {
        assertEquals("周日", CalendarHeatPresenter.weekdayShort(LocalDate.of(2026, 9, 13)))
        assertEquals("周一", CalendarHeatPresenter.weekdayShort(LocalDate.of(2026, 9, 14)))
        assertEquals("周六", CalendarHeatPresenter.weekdayShort(LocalDate.of(2026, 9, 19)))
    }

    @Test
    fun `满勤阈值与法定标准工作日都是8小时`() {
        assertEquals(480, CalendarHeatPresenter.FULL_DAY_MINUTES)
        assertEquals(480, CalendarHeatPresenter.STANDARD_WORKDAY_MINUTES)
    }
}
