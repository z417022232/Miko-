package com.example.worktimetracker.domain.journey

import com.example.worktimetracker.domain.evidence.EvidenceSource
import com.example.worktimetracker.domain.evidence.FusedDecision

/**
 * 状态变迁候选（**八字段，§5.2.5**）。
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
    val confidence: Double
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
