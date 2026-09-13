package com.example.worktimetracker.ui

import com.example.worktimetracker.domain.engine.DayKind
import java.time.LocalDate

data class UiDayRecord(
    val date: LocalDate,
    val status: String,
    val shift: String? = null,
    val startMillis: Long? = null,
    val endMillis: Long? = null,
    val startText: String? = null,
    val endText: String? = null,
    val actualMinutes: Int? = null,
    val finalMinutes: Int = 0,
    val needsReview: Boolean = false,
    /** A6: 系统判定的复核原因（如 "R3 21:00-21:29 灰区"）。needsReview=true 时展示。 */
    val reviewReason: String? = null,
    /** A6: 用户是否已点过"认可"（NEEDS_REVIEW_ACK 位），用于区分"未处理"与"同一原因再次出现"。 */
    val reviewAcknowledged: Boolean = false,
    val note: String? = null,
    /** **仅法定节日当天**有值（中秋节 / 国庆节…）。周末/假期休息日/调休 均为 null。 */
    val holidayName: String? = null,
    /** 这天本来的公休性质（工作日 / 周末 / 假期休息日 / 节日 / 调休上班）。决定格子底色。 */
    val dayKind: DayKind = DayKind.WORKDAY,
    /** 格子第一行的公休标签：节日名 / "休" / "班"；普通工作日为 null。 */
    val dayBadge: String? = null,
    val companyArrivalText: String? = null,
    val companyDepartureText: String? = null,
    val homeArrivalText: String? = null,
    val homeDepartureText: String? = null
)

fun calendarDayLabel(shift: String?, minutes: Int): String {
    if (minutes <= 0) return when (shift) { "白班" -> "白"; "夜班" -> "夜"; else -> "" }
    val hours = if (minutes % 60 == 0) "${minutes / 60}" else "%.1f".format(java.util.Locale.US, minutes / 60.0)
    val prefix = when (shift) { "白班" -> "白 "; "夜班" -> "夜 "; else -> "" }
    return "${prefix}${hours}h"
}

/**
 * 日期卡片 / 详情弹窗用的完整公休说明（日历格子只有一格位置，放的是短标签 "休" / "班" / 节日名）。
 *
 * 「休」必须结合**当天到底有没有出工**来判断：同样是周六，没上班是"周末休息"，
 * 上了班就是"周末出勤"——否则会出现「白 11h」配一句「周末休息」的自相矛盾。
 * 节日当天例外：无论出勤与否都显示节日名（用户更关心"今天是中秋"）。
 */
fun dayKindText(kind: DayKind, festivalName: String?, worked: Boolean): String? = when (kind) {
    DayKind.FESTIVAL -> festivalName
    DayKind.HOLIDAY_REST -> if (worked) "假期出勤" else "假期休息"
    DayKind.MAKEUP_WORKDAY -> "调休上班"
    DayKind.WEEKEND -> if (worked) "周末出勤" else "周末休息"
    DayKind.WORKDAY -> null
}
