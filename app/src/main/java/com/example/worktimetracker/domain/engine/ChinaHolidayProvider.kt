package com.example.worktimetracker.domain.engine

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 一天的公休性质。**只描述"这天本来的日历身份"，与用户是否上班无关。**
 *
 * 与 `WorkRecordEntity.status`（实际发生了什么）正交，两者组合出最终展示：
 * - 节假日本身上班 ⇒ `FESTIVAL` + 白班 ⇒ 格子显示 `白 11h` + `中秋节`
 * - 节假日期间的休息日上班 ⇒ `HOLIDAY_REST` + 白班 ⇒ `白 11h` + `休`
 * - 调休上班日上班 ⇒ `MAKEUP_WORKDAY` + 白班 ⇒ `白 11h` + `班`
 *
 * 背景色见 `CalendarScreen.dayCellBackground()`；标签见 [ChinaHolidayProvider.badge]。
 */
enum class DayKind {
    /** 普通工作日（周一~周五，非节假日、非调休） */
    WORKDAY,

    /** 周末休息（周六/周日，且未被调休为上班） */
    WEEKEND,

    /** 假期内的休息日（节日当天之外的放假天，含补休） */
    HOLIDAY_REST,

    /** 法定节假日当天（元旦 / 春节 / 清明 / 劳动节 / 端午 / 中秋 / 国庆） */
    FESTIVAL,

    /** 调休上班日（周末被调整为上班） */
    MAKEUP_WORKDAY;

    /** 这天是否"本该休息"（周末，或假期里的非节日天）。 */
    val isRest: Boolean get() = this == WEEKEND || this == HOLIDAY_REST

    /** 非节日天的公休短标签：休 / 班；节日名由 [DayInfo.festivalName] 提供。 */
    val shortLabel: String?
        get() = when (this) {
            WEEKEND, HOLIDAY_REST -> "休"
            MAKEUP_WORKDAY -> "班"
            else -> null
        }
}

/** [ChinaHolidayProvider.info] 的返回值。 */
data class DayInfo(
    val kind: DayKind,
    /** 仅 [DayKind.FESTIVAL] 有值，如 "中秋节"。 */
    val festivalName: String? = null
)

/**
 * 中国法定节假日 / 调休 / 周末判定。
 *
 * 数据来源：国务院办公厅《关于 2026 年部分节假日安排的通知》
 * （国办发明电〔2025〕7 号，2025-11-04）。法定假日天数依 2024 年修订的
 * 《全国年节及纪念日放假办法》：元旦 1 天、春节 4 天（除夕~初三）、清明 1 天、
 * 劳动节 2 天、端午 1 天、中秋 1 天、国庆 3 天。
 *
 * ⚠️ 关键口径（用户 2026-09-13 确认）：**只有法定节日"当天"才带节日名**，
 * 假期里的其余天（含补休日）一律算"休"。例：中秋假期 9/25–9/27 ⇒ 9/25 中秋节，
 * 9/26、9/27 是休（旧实现把三天都写成"中秋节"，是缺陷）。
 */
object ChinaHolidayProvider {
    /** 2026 法定节假日当天 → 节日名。 */
    private val festival2026 = mapOf(
        "2026-01-01" to "元旦",
        "2026-02-16" to "春节", // 除夕（腊月二十九）
        "2026-02-17" to "春节", // 正月初一
        "2026-02-18" to "春节",
        "2026-02-19" to "春节",
        "2026-04-05" to "清明节", // 清明节气当日（周日）
        "2026-05-01" to "劳动节",
        "2026-05-02" to "劳动节",
        "2026-06-19" to "端午节",
        "2026-09-25" to "中秋节",
        "2026-10-01" to "国庆节",
        "2026-10-02" to "国庆节",
        "2026-10-03" to "国庆节"
    )

    /** 2026 假期内的休息日（节日当天之外，含补休）。 */
    private val holidayRest2026 = setOf(
        "2026-01-02", "2026-01-03",
        "2026-02-15", "2026-02-20", "2026-02-21", "2026-02-22", "2026-02-23",
        "2026-04-04", "2026-04-06",
        "2026-05-03", "2026-05-04", "2026-05-05",
        "2026-06-20", "2026-06-21",
        "2026-09-26", "2026-09-27",
        "2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07"
    )

    /** 2026 调休上班日（周末被调为上班）。 */
    private val makeup2026 = setOf(
        "2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10"
    )

    /** 未收录年份的兜底：只认公历固定的节日当天（春节/清明/端午/中秋随农历，无法兜底）。 */
    private val fixedFestival = mapOf(
        "01-01" to "元旦",
        "05-01" to "劳动节", "05-02" to "劳动节",
        "10-01" to "国庆节", "10-02" to "国庆节", "10-03" to "国庆节"
    )

    fun info(date: LocalDate): DayInfo {
        val key = date.toString()
        festival2026[key]?.let { return DayInfo(DayKind.FESTIVAL, it) }
        if (key in makeup2026) return DayInfo(DayKind.MAKEUP_WORKDAY)
        if (key in holidayRest2026) return DayInfo(DayKind.HOLIDAY_REST)
        fixedFestival["%02d-%02d".format(date.monthValue, date.dayOfMonth)]
            ?.let { return DayInfo(DayKind.FESTIVAL, it) }
        val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        return DayInfo(if (weekend) DayKind.WEEKEND else DayKind.WORKDAY)
    }

    /**
     * 节日名。**只有法定节日当天才返回名字**；周末 / 假期休息日 / 调休上班日一律返回 null。
     * 导出（Excel / CSV / PDF）沿用此口径，避免把整个假期都标成节日。
     */
    fun name(date: LocalDate): String? = info(date).festivalName

    /**
     * 日历格子第一行的公休标签：节日名（中秋节…）/ "休" / "班"；普通工作日返回 null。
     */
    fun badge(date: LocalDate): String? = info(date).let { it.festivalName ?: it.kind.shortLabel }
}
