package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.data.entity.WorkStateEntity
import com.example.worktimetracker.domain.model.WorkSettings
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「当前工作日」判定（2026-09-16）。
 *
 * **自然日 ≠ 工作日。** 夜班 21:00 开班、次日 09:00 下班，凌晨 00:30 打开 App 时人还在班上，
 * 界面上的「今天」必须仍然指向上班那一天 —— 否则：
 * - 日历高亮与「今日」标题跳到次日；
 * - 当日卡片读到次日那条还不存在的记录，看起来像"这一班白上了"；
 * - 实时计时与当日工资一起断掉。
 *
 * 判定只看一件事：**状态机里是否还有未结束的在岗会话**。
 * 有 → 取该会话的**开班日**（与 [ShiftDetector.assignedDate] 同一口径，都走
 * [ShiftDetector.anchorFor] 吸附锚点）；没有 → 就是自然日。
 *
 * ⚠️ 注意不能直接用「会话开始时刻的日历日」：凌晨 00:30 才到岗的夜班，
 * 其开班日是**前一天**，两者会差一天（2026-09-16 复查 P0）。
 *
 * 纯函数，不做任何 IO，可单测。
 */
object WorkdayClock {

    /** 会话仍未结束的状态：在岗 / 临时外出（外出也算班没下）。 */
    private val ONGOING_STATES = setOf("WORKING", "TEMP_LEAVE")

    /**
     * @param state 状态机当前快照，null 表示读不到（未初始化）
     * @param naturalToday 自然日 `LocalDate.now(zone)`
     * @param settings 用户排班设置（决定锚点落在 09:00 / 21:00 哪一侧）
     */
    fun today(
        state: WorkStateEntity?,
        naturalToday: LocalDate,
        zoneId: ZoneId,
        settings: WorkSettings = WorkSettings()
    ): LocalDate {
        if (state == null || state.currentState !in ONGOING_STATES) return naturalToday
        val sessionStart = state.sessionStart ?: return naturalToday
        val assigned = ShiftDetector(zoneId).anchorFor(sessionStart, settings).date
        // 夜班最多跨一天。更早的会话说明状态卡住了（进程被杀、权限被回收等），
        // 这时把几个月前那天当成"今天"只会更糟，直接回落到自然日。
        return if (assigned >= naturalToday.minusDays(1)) assigned else naturalToday
    }
}
