package com.example.worktimetracker.data.remote

import com.example.worktimetracker.domain.engine.HolidayArrangement
import com.example.worktimetracker.domain.engine.HolidaySource
import org.json.JSONObject
import java.time.LocalDate

/**
 * 节假日接口响应解析（纯函数，无网络/无 Android 依赖，可单测）。
 *
 * 支持两种主流返回形态：
 *
 * **A. holiday-cn 形态**（由国务院公告机器生成，`papers` 字段直接给出 gov.cn 原文链接）
 * ```json
 * { "year": 2026,
 *   "papers": ["https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm"],
 *   "days": [ { "name": "元旦", "date": "2026-01-01", "isOffDay": true } ] }
 * ```
 *
 * **B. timor.tech 形态**
 * ```json
 * { "code": 0,
 *   "holiday": { "01-01": { "holiday": true,  "name": "元旦", "wage": 3 },
 *                "01-04": { "holiday": false, "name": "元旦调休" } } }
 * ```
 *
 * ⚠️ 关键设计：**解析结果只写入"休 / 调休上班"，绝不写入节日名**。
 * 这些接口会把整段假期都用同一个名字标注（中秋假期 9/25–9/27 全叫"中秋节"），
 * 若直接采信就会重现用户报过的缺陷（9/26、9/27 应该是"休"）。
 * 法定节日当天一律由 [com.example.worktimetracker.domain.engine.ChineseCalendar] 推算。
 */
object HolidayPayloadParser {

    /** 解析失败或年份不匹配时返回 null。 */
    fun parse(year: Int, body: String): HolidayArrangement? {
        if (body.isBlank()) return null
        val root = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return null
        return parseHolidayCn(year, root) ?: parseTimor(year, root)
    }

    private fun parseHolidayCn(year: Int, root: JSONObject): HolidayArrangement? {
        val days = root.optJSONArray("days") ?: return null
        if (days.length() == 0) return null
        val makeup = linkedSetOf<String>()
        val rest = linkedSetOf<String>()
        for (i in 0 until days.length()) {
            val item = days.optJSONObject(i) ?: continue
            val date = normalizeDate(item.optString("date"), year) ?: continue
            if (item.optBoolean("isOffDay", false)) rest += date else makeup += date
        }
        if (makeup.isEmpty() && rest.isEmpty()) return null
        return HolidayArrangement(
            year = year,
            makeupWorkdays = makeup,
            restDays = rest,
            source = HolidaySource.REMOTE
        )
    }

    private fun parseTimor(year: Int, root: JSONObject): HolidayArrangement? {
        val holiday = root.optJSONObject("holiday") ?: return null
        val makeup = linkedSetOf<String>()
        val rest = linkedSetOf<String>()
        val keys = holiday.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = holiday.optJSONObject(key) ?: continue
            // 该形态的 date 字段可能缺失，用 key（MM-dd）补年份
            val date = normalizeDate(item.optString("date"), year)
                ?: normalizeDate("$year-$key", year)
                ?: continue
            if (item.optBoolean("holiday", false)) rest += date else makeup += date
        }
        if (makeup.isEmpty() && rest.isEmpty()) return null
        return HolidayArrangement(
            year = year,
            makeupWorkdays = makeup,
            restDays = rest,
            source = HolidaySource.REMOTE
        )
    }

    /** 校验并归一化日期串；不属于 [year] 的一律丢弃（防止接口返回隔壁年份的数据污染本年）。 */
    private fun normalizeDate(raw: String, year: Int): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val date = runCatching { LocalDate.parse(text) }.getOrNull() ?: return null
        return if (date.year == year) date.toString() else null
    }
}
