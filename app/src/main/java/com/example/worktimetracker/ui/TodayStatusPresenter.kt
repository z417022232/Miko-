package com.example.worktimetracker.ui

import com.example.worktimetracker.domain.evidence.EvidenceSource
import com.example.worktimetracker.domain.evidence.FusedStatusSnapshot
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

/**
 * 「今日」实时页的**纯展示推导**（无 Android 依赖，可单测）。
 *
 * 硬约束：本类只读、只翻译，**绝不写库、绝不参与状态机**。
 * 前台服务的自动判定（TrajectoryAnchorEngine + EvidenceCoordinator）仍是唯一权威，
 * 这里做的事就是把「已有记录 + 已发布的融合快照 + 当前时间」变成界面文案。
 */
object TodayStatusPresenter {

    // ----------------------------------------------------------------- 工时

    /** 展示用分钟数与"是否仍在计时"。 */
    data class TodayMinutes(val minutes: Int, val running: Boolean, val fixed: Boolean)

    /**
     * 今日累计工时的展示值。
     *
     * 取值顺序（与结算口径保持一致，让数字在收工时不会突然跳变）：
     * 1. 已离岗（endMillis != null）→ 用落库的 [finalMinutes]；
     * 2. 没有上班时间（startMillis == null）→ 用落库值（通常 0）。
     *    **必须排在固定工时之前**：固定工时是"这一天按 N 分钟计"的口径，
     *    只有真的上了班才谈得上；没打卡却显示固定工时会让人以为今天已经出勤了
     *    （2026-09-16 复查 P1）；
     * 3. 开了固定工时 → 用固定值（当天无论如何都按这个数计，不显示跳动）；
     * 4. 其余 → 「已持续时长 − 休息扣除」，下限 0，并标记计时中。
     */
    fun displayMinutes(
        finalMinutes: Int,
        startMillis: Long?,
        endMillis: Long?,
        nowMillis: Long,
        restDeductionMinutes: Int,
        fixedMinutes: Int? = null
    ): TodayMinutes = when {
        endMillis != null -> TodayMinutes(finalMinutes.coerceAtLeast(0), running = false, fixed = false)
        startMillis == null -> TodayMinutes(finalMinutes.coerceAtLeast(0), running = false, fixed = false)
        fixedMinutes != null -> TodayMinutes(fixedMinutes.coerceAtLeast(0), running = false, fixed = true)
        else -> {
            val elapsed = ((nowMillis - startMillis) / 60_000L).toInt().coerceAtLeast(0)
            TodayMinutes((elapsed - restDeductionMinutes).coerceAtLeast(0), running = true, fixed = false)
        }
    }

    // ----------------------------------------------------------------- 状态

    enum class TodayTone { WORKING, DONE, OFF, WARN, IDLE }

    data class Headline(val text: String, val tone: TodayTone)

    /**
     * 顶部状态大字。优先级：离岗 > 在岗 > 请假/外出 > 待确认 > 未上班。
     * 注意 [UiDayRecord.status] 是**显示标签**（"白班"/"夜班"/"休息"…），不是枚举名。
     */
    fun headline(record: UiDayRecord?): Headline {
        if (record == null) return Headline("今天还没有记录", TodayTone.IDLE)
        if (record.endMillis != null) {
            return when (record.status) {
                "下早班" -> Headline("已下早班", TodayTone.WARN)
                "到岗异常" -> Headline("已下班 · 到岗异常", TodayTone.WARN)
                "请假" -> Headline("请假", TodayTone.OFF)
                "外出" -> Headline("外出", TodayTone.OFF)
                "休息" -> Headline("休息", TodayTone.OFF)
                else -> Headline("已下班", TodayTone.DONE)
            }
        }
        if (record.startMillis != null) {
            return when (record.status) {
                "外出" -> Headline("外出中", TodayTone.WARN)
                else -> Headline("在岗中", TodayTone.WORKING)
            }
        }
        // 顺序有讲究：请假/外出是用户明确表达过的意图；待确认是"需要你处理"，
        // 比"今天休息"更该被顶到最前面；最后才回落到"还没上班"。
        return when {
            record.status == "请假" -> Headline("今天请假", TodayTone.OFF)
            record.status == "外出" -> Headline("外出中", TodayTone.WARN)
            record.needsReview -> Headline("有待确认的判定", TodayTone.WARN)
            record.status == "休息" -> Headline("今天休息", TodayTone.OFF)
            else -> Headline("今天还没上班", TodayTone.IDLE)
        }
    }

    // ----------------------------------------------------------------- 文案

    /** "9 月 13 日 周日"。 */
    fun dateLabel(date: LocalDate): String {
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)
        return "${date.monthValue} 月 ${date.dayOfMonth} 日 $weekday"
    }

    /** ISO 周次，例如 "第 37 周"（2026-09-13 → 37，与界面稿一致）。 */
    fun weekLabel(date: LocalDate): String = "第 ${date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} 周"

    // ------------------------------------------------------------- 证据来源

    data class EvidenceChip(val label: String, val active: Boolean)

    /** 证据源芯片，顺序固定为 GPS / Wi-Fi / 蓝牙 / 基站 / Motion（与界面稿一致）。 */
    fun evidenceChips(snapshot: FusedStatusSnapshot?): List<EvidenceChip> {
        val present = snapshot?.sources.orEmpty()
        val ordered = listOf(
            EvidenceSource.GNSS to "GPS",
            EvidenceSource.WIFI to "Wi-Fi",
            EvidenceSource.BLUETOOTH to "蓝牙",
            EvidenceSource.CELL to "基站",
            EvidenceSource.MOTION to "Motion"
        )
        val chips = ordered.map { (source, label) -> EvidenceChip(label, present.contains(source)) }.toMutableList()
        // 网络定位 / 班次窗口不在稿子里，但也不能凭空消失——有就追加到末尾
        if (present.contains(EvidenceSource.NETWORK_LOCATION)) chips.add(EvidenceChip("网络定位", true))
        if (present.contains(EvidenceSource.SHIFT_WINDOW)) chips.add(EvidenceChip("班次窗口", true))
        return chips
    }

    /** "共 5 项证据"；没有任何来源时返回 null。 */
    fun evidenceCountText(snapshot: FusedStatusSnapshot?): String? {
        val size = snapshot?.sources?.size ?: 0
        return if (size <= 0) null else "共 $size 项证据"
    }

    /**
     * 冲突提示。只有协调器明确给出 `UNKNOWN_CONFLICT` 时才算"有冲突"，
     * 不凭 breakdown 文本臆测条数——宁可少说，也不报错数字。
     */
    fun conflictText(snapshot: FusedStatusSnapshot?): String? = when {
        snapshot == null -> null
        snapshot.reason.startsWith("UNKNOWN_CONFLICT") -> "1 项冲突"
        else -> null
    }

    /** 底部一行小字，例如 "共 5 项证据 · 1 项冲突"。 */
    fun evidenceLine(snapshot: FusedStatusSnapshot?): String? {
        val parts = listOfNotNull(evidenceCountText(snapshot), conflictText(snapshot))
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }
}
