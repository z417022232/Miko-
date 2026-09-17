package com.example.worktimetracker.domain.journey

/**
 * 行程状态（**13 个，阶段 3 规格 §5.2.4 冻结**）。
 *
 * 分组：
 * - 家：[AT_HOME] / [LEAVING_HOME] / [ARRIVING_HOME]
 * - 通勤：[COMMUTING_TO_WORK] / [COMMUTING_HOME]
 * - 在岗：[AT_WORK] / [LEAVING_WORK] / [ARRIVING_WORK]
 * - 中间：[TEMP_LEAVE] / [OTHER_STOP]
 * - 其他：[AWAY] / [UNKNOWN] / [STALE]
 *
 * 补 [TEMP_LEAVE] / [OTHER_STOP] 的由来：旧机 `TrajectoryAnchorEngine` 本就有 `"TEMP_LEAVE"`
 * 状态，但它实际是**下班确认的候选期** —— 回公司→WORKING、离够久/到家→FINISHED，
 * 「临时离开」和「正式下班」共用同一个状态的两个出口，**从未真正区分**。
 * 新机把它拆成 [LEAVING_WORK]（离岗候选）+ [TEMP_LEAVE]（已确认临时离岗）
 * + [COMMUTING_HOME]（正式下班回家）三条路径。
 *
 * [OTHER_STOP] 与 [AWAY] 的分工只有一条判据：`JourneyObservation.hasActiveWorkSession` ——
 * 工作期间在别处停留 = [OTHER_STOP]（预期回），无会话时在别处 = [AWAY]（休息日外出）。
 *
 * ⚠️ [UNKNOWN] 与 [STALE] 必须分开：前者是"证据矛盾、判不出来"，后者是"压根没有证据"。
 * 混成一个的话，诊断页上看不出是数据缺失还是算法失灵。
 */
enum class JourneyPhase {
    AT_HOME,
    LEAVING_HOME,
    ARRIVING_HOME,

    COMMUTING_TO_WORK,
    COMMUTING_HOME,

    ARRIVING_WORK,
    AT_WORK,
    LEAVING_WORK,

    TEMP_LEAVE,
    OTHER_STOP,

    AWAY,
    UNKNOWN,
    STALE;

    companion object {
        /**
         * 从持久化字符串解析（§5.6 `journey_shadow_state`）。
         *
         * **未知值返回 null（失败），绝不猜测、绝不静默回落** ——
         * 调用方见 null 应按 §5.6 规则 3 重置快照（枚举改名/字段语义变化后旧值解不回来，宁弃勿猜）。
         */
        fun parseOrNull(raw: String?): JourneyPhase? = entries.firstOrNull { it.name == raw }
    }
}
