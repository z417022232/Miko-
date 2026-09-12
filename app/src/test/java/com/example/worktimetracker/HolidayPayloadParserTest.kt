package com.example.worktimetracker

import com.example.worktimetracker.data.remote.HolidayPayloadParser
import com.example.worktimetracker.domain.engine.HolidaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 节假日接口响应解析测试（覆盖两种形态 + 脏数据防御）。 */
class HolidayPayloadParserTest {

    // ---------- 形态 A：holiday-cn（国务院公告机器生成）----------

    private val holidayCn2026 = """
    {
      "${'$'}schema": "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/schema.json",
      "year": 2026,
      "papers": ["https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm"],
      "days": [
        { "name": "元旦", "date": "2026-01-01", "isOffDay": true },
        { "name": "元旦", "date": "2026-01-02", "isOffDay": true },
        { "name": "元旦", "date": "2026-01-04", "isOffDay": false },
        { "name": "中秋节", "date": "2026-09-25", "isOffDay": true },
        { "name": "中秋节", "date": "2026-09-26", "isOffDay": true },
        { "name": "中秋节", "date": "2026-09-27", "isOffDay": true },
        { "name": "国庆节", "date": "2026-09-20", "isOffDay": false }
      ]
    }
    """.trimIndent()

    @Test
    fun `parsesHolidayCnShape`() {
        val result = HolidayPayloadParser.parse(2026, holidayCn2026)
        requireNotNull(result)
        assertEquals("年份", 2026, result.year)
        assertEquals(HolidaySource.REMOTE, result.source)
        assertEquals(
            setOf("2026-01-01", "2026-01-02", "2026-09-25", "2026-09-26", "2026-09-27"),
            result.restDays
        )
        assertEquals(setOf("2026-01-04", "2026-09-20"), result.makeupWorkdays)
    }

    @Test
    fun `neverLeaksRemoteFestivalNamesIntoFestivalNames`() {
        // 关键：远端把整段假期都标成"中秋节"，绝不能采信为节日名，否则重现
        // "9/26、9/27 显示中秋节"的缺陷。法定节日当天由农历算法决定。
        val result = HolidayPayloadParser.parse(2026, holidayCn2026)
        requireNotNull(result)
        assertTrue("远端数据不得写入 festivalNames", result.festivalNames.isEmpty())
        // 9/26、9/27 只作为"休"，节日名交由 ChineseCalendar 判定
        assertTrue(result.restDays.contains("2026-09-26"))
        assertTrue(result.restDays.contains("2026-09-27"))
        assertNull(com.example.worktimetracker.domain.engine.ChineseCalendar.festivalName(java.time.LocalDate.parse("2026-09-26")))
    }

    // ---------- 形态 B：timor.tech ----------

    private val timor2026 = """
    {
      "code": 0,
      "holiday": {
        "01-01": { "holiday": true,  "name": "元旦", "wage": 3, "date": "2026-01-01" },
        "01-02": { "holiday": true,  "name": "元旦", "wage": 2, "date": "2026-01-02" },
        "01-04": { "holiday": false, "name": "元旦调休",       "date": "2026-01-04" },
        "09-20": { "holiday": false, "name": "国庆节前补班" }
      }
    }
    """.trimIndent()

    @Test
    fun `parsesTimorShapeAndSynthesizesMissingDate`() {
        val result = HolidayPayloadParser.parse(2026, timor2026)
        requireNotNull(result)
        assertEquals(setOf("2026-01-01", "2026-01-02"), result.restDays)
        // 09-20 没带 date 字段，用 key 补年份
        assertEquals(setOf("2026-01-04", "2026-09-20"), result.makeupWorkdays)
    }

    // ---------- 脏数据防御 ----------

    @Test
    fun `rejectsGarbageAndEmptyPayloads`() {
        assertNull(HolidayPayloadParser.parse(2026, ""))
        assertNull(HolidayPayloadParser.parse(2026, "   "))
        assertNull(HolidayPayloadParser.parse(2026, "<html>502 Bad Gateway</html>"))
        assertNull(HolidayPayloadParser.parse(2026, "{}"))
        assertNull(HolidayPayloadParser.parse(2026, """{"year":2026,"days":[]}"""))
        assertNull(HolidayPayloadParser.parse(2026, """{"code":0}"""))
    }

    @Test
    fun `dropsDatesBelongingToOtherYears`() {
        // 接口把隔壁年份的数据混进来时必须丢弃，否则会污染本年判定
        val mixed = """
        {"year":2026,"days":[
          {"name":"元旦","date":"2026-01-01","isOffDay":true},
          {"name":"元旦","date":"2025-01-01","isOffDay":true},
          {"name":"元旦","date":"not-a-date","isOffDay":true}
        ]}
        """.trimIndent()
        val result = HolidayPayloadParser.parse(2026, mixed)
        requireNotNull(result)
        assertEquals(setOf("2026-01-01"), result.restDays)
        assertTrue(result.makeupWorkdays.isEmpty())
    }

    @Test
    fun `dropsEntriesWithUnparsableDatesButKeepsValidOnes`() {
        val payload = """
        {"year":2026,"days":[
          {"name":"元旦","date":"2026-01-01","isOffDay":true},
          {"name":"乱码","date":"","isOffDay":true},
          {"name":"乱码","date":"2026-13-45","isOffDay":true}
        ]}
        """.trimIndent()
        val result = HolidayPayloadParser.parse(2026, payload)
        requireNotNull(result)
        assertEquals(setOf("2026-01-01"), result.restDays)
    }
}
