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
    /** 仅 [DayKind.FESTIVAL] 有值，如 "中秋节"；重合日形如 "中秋节·国庆节"。 */
    val festivalName: String? = null
)

/** 放假安排的数据来源，决定可信度与展示文案。 */
enum class HolidaySource(val label: String) {
    /** 随 App 版本内置的官方公告表 */
    EMBEDDED("内置公告"),
    /** 联网获取（源自国务院公告） */
    REMOTE("联网获取"),
    /** 用户手动导入 */
    IMPORTED("手动导入")
}

/**
 * 一年的放假安排：**"哪几天休、哪几天调休上班"**。
 *
 * 这是唯一必须依赖国务院公告的部分；"哪天是法定节日当天"由 [ChineseCalendar]
 * 纯计算得出，不依赖公告，所以哪怕某年公告还没发布/没联网，节日标记也不会丢。
 */
data class HolidayArrangement(
    val year: Int,
    /** 调休上班日（周末被调为上班），如 2026-09-20 */
    val makeupWorkdays: Set<String> = emptySet(),
    /** 假期内的休息日（法定节日当天之外），如 2026-09-26 */
    val restDays: Set<String> = emptySet(),
    /** 公告特别指定的节日名（如一次性纪念日放假）。远端数据**不**写入此处，避免把整段假期都标成节日名。 */
    val festivalNames: Map<String, String> = emptyMap(),
    val source: HolidaySource = HolidaySource.EMBEDDED
)

/**
 * 中国法定节假日 / 调休 / 周末判定（内嵌兜底层）。
 *
 * 判定优先级：
 *   1. [HolidayArrangement.festivalNames]（公告／导入特别指定）
 *   2. [ChineseCalendar.festivalName] —— 算法推导的 13 个法定节日当天，**任意年份可用**
 *   3. 放假安排里的调休上班日 → `MAKEUP_WORKDAY`；假期休息日 → `HOLIDAY_REST`
 *   4. 周六/周日 → `WEEKEND`，否则 `WORKDAY`
 *
 * 2026 的放假安排来源：国务院办公厅《关于 2026 年部分节假日安排的通知》
 * （国办发明电〔2025〕7 号，2025-11-04，共 33 天放假调休）。
 *
 * ⚠️ 核心口径（用户 2026-09-13 确认）：**只有法定节日"当天"才带节日名**，
 * 假期里的其余天（含补休日）一律算"休"。例：中秋假期 9/25–9/27 ⇒ 9/25 中秋节，
 * 9/26、9/27 是休。
 */
object ChinaHolidayProvider {

    /** 内嵌的官方放假安排表。新增年份时在此追加一项即可（结构与远端数据完全一致）。 */
    val embedded: Map<Int, HolidayArrangement> = mapOf(
        2026 to HolidayArrangement(
            year = 2026,
            makeupWorkdays = setOf(
                "2026-01-04", "2026-02-14", "2026-02-28",
                "2026-05-09", "2026-09-20", "2026-10-10"
            ),
            restDays = setOf(
                "2026-01-02", "2026-01-03",
                "2026-02-15", "2026-02-20", "2026-02-21", "2026-02-22", "2026-02-23",
                "2026-04-04", "2026-04-06",
                "2026-05-03", "2026-05-04", "2026-05-05",
                "2026-06-20", "2026-06-21",
                "2026-09-26", "2026-09-27",
                "2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07"
            ),
            source = HolidaySource.EMBEDDED
        )
    )

    /** 该年的内嵌放假安排（无则为 null，此时只剩法定节日 + 周末可用）。 */
    fun embeddedArrangement(year: Int): HolidayArrangement? = embedded[year]

    /**
     * 判定某天公休性质。
     * @param arrangement 该年的放假安排；传 null 表示"该年没有公告数据"，退化为「法定节日 + 周末」。
     */
    fun info(
        date: LocalDate,
        arrangement: HolidayArrangement? = embeddedArrangement(date.year)
    ): DayInfo {
        val key = date.toString()
        arrangement?.festivalNames?.get(key)?.let { return DayInfo(DayKind.FESTIVAL, it) }
        ChineseCalendar.festivalName(date)?.let { return DayInfo(DayKind.FESTIVAL, it) }
        if (arrangement != null) {
            if (key in arrangement.makeupWorkdays) return DayInfo(DayKind.MAKEUP_WORKDAY)
            if (key in arrangement.restDays) return DayInfo(DayKind.HOLIDAY_REST)
        }
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
    fun badge(date: LocalDate): String? =
        info(date).let { it.festivalName ?: it.kind.shortLabel }
}
