package com.example.worktimetracker.domain.journey

import com.example.worktimetracker.domain.evidence.EvidenceSource
import com.example.worktimetracker.domain.evidence.FusedDecision

/**
 * 状态变迁候选（**九字段，§5.2.5 + 第 2 步补 `lastUnsupportedAt`**）。
 *
 * 两个时刻的分工（与 [JourneyEvent] 同源，硬约束）：
 * - [firstObservedAt]：**最早**支持该状态的那条可靠证据的时刻 —— **事件确认后取它作为正式时刻**；
 * - [lastSupportedAt]：最近一条仍支持该状态的证据的时刻 —— **只用于判断"支持是否已经消失"**。
 *
 * ⚠️ **拍数与时长各司其职**（一轮 P1-1）：
 * 确认门槛**只**用 [accumulatedStableMillis]，[supportCount] **只**做诊断与解释。
 * 因为 1 分钟采样和 30 秒采样的「三拍」不是同一时长 —— CRITICAL 档三拍 = 90 秒，
 * STABLE 档三拍 = 30 分钟，只用拍数会让同一个门槛横跨两个数量级。
 */
data class JourneyCandidate(
    /** 候选指向的目标状态（v1 曾叫 `phase`，语义模糊，已改名）。 */
    val targetPhase: JourneyPhase,

    /** 最早支持该状态的证据时刻（epoch millis）—— 事件正式时刻取它。 */
    val firstObservedAt: Long,

    /** 最近一条仍支持该状态的证据时刻（epoch millis）。 */
    val lastSupportedAt: Long,

    /** 支持拍数 —— **只做诊断与解释**（"还差几拍"），不参与确认门槛。 */
    val supportCount: Int,

    /** 累计稳定时长（毫秒）—— **确认门槛只认它**。 */
    val accumulatedStableMillis: Long,

    /** 支持链的证据来源（解释用）。 */
    val evidenceSources: Set<EvidenceSource>,

    /** 支持链中出现过的最强决策等级（解释用）。 */
    val strongestDecision: FusedDecision,

    /** 支持链最强置信（0..1，解释用）。 */
    val confidence: Double,

    /**
     * ⭐ 第九字段：最近一次「支持中断」的时刻（epoch millis）；**null = 支持链当前连续**。
     *
     * **为什么必须有它**：纯 Reducer 只拿到上一拍快照，凭上面八个字段**在数学上区分不出**
     * 「连续两拍都支持」与「中间断过一拍又支持」—— 两者的 `firstObservedAt`、
     * `lastSupportedAt`、`supportCount` 可以完全相同。没有这个标记，空窗时长会被当成
     * 稳定时长累计进去（"一次断流把候选泡到门槛"），而规格明确要求
     * **两次支持之间出现空窗时不跨空窗累计**（§5.4 第 10 条）。
     *
     * 这与一轮 P0-1（一拍观测做不了候选累计）是同一类问题：
     * **缺的不是阈值，是能表达"链断过"的状态位**。
     *
     * ⚠️ 它**不参与**过期判定（过期仍看 `lastSupportedAt`），
     * 所以「候选保留最早证据」与「空窗不计入稳定时长」两件事可以同时成立。
     *
     * ⚠️ 第 5 步（DB v17 `journey_shadow_state`）**必须**为它加一列
     * `candidateLastUnsupportedAt`，否则重启后第一拍会把空窗当稳定时长。
     */
    val lastUnsupportedAt: Long? = null
) {
    companion object {
        /**
         * 证据来源序列化（§5.6 `candidateEvidenceSources` 列）。
         * 顺序按枚举 ordinal **稳定排序** —— 恢复后与重启前逐字节一致，
         * 影子对比日志不会因 Set 迭代顺序漂移。
         */
        fun encodeSources(sources: Set<EvidenceSource>): String =
            sources.sortedBy { it.ordinal }.joinToString(SOURCE_SEPARATOR) { it.name }

        /**
         * 证据来源反序列化。**未知 token 返回 null（失败），不静默丢弃**
         * —— 悄悄丢掉一个不认识的来源，等于把"未知"伪装成"当时只有这些来源"，
         * 违反「不把未知伪装成确定值」（§7 第 8 条）。
         *
         * @return 成功为来源集合（空串 = 空集合），**失败为 null**（交给调用方按 §5.6 重置）。
         */
        fun decodeSources(raw: String?): Set<EvidenceSource>? {
            if (raw == null) return null
            if (raw.isBlank()) return emptySet()
            val decoded = LinkedHashSet<EvidenceSource>()
            for (token in raw.split(SOURCE_SEPARATOR)) {
                val trimmed = token.trim()
                if (trimmed.isEmpty()) continue
                val source = EvidenceSource.entries.firstOrNull { it.name == trimmed } ?: return null
                decoded += source
            }
            return decoded
        }

        private const val SOURCE_SEPARATOR = ","
    }
}
