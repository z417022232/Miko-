package com.example.worktimetracker.domain.evidence

/**
 * 证据来源。
 * GNSS 与 NETWORK_LOCATION 区分 GPS_PROVIDER 与 NETWORK_PROVIDER 的绝对定位，
 * 便于以后排查误判时区分「真正 GPS 15m」和「Network Location 25m」。
 */
enum class EvidenceSource { GNSS, NETWORK_LOCATION, CELL, WIFI, BLUETOOTH, MOTION, SHIFT_WINDOW }

enum class ResolvedPlace { HOME, COMPANY, OTHER, MOVING, UNKNOWN }

/**
 * 融合决策分层：
 * - CONFIRMED：强证据（可靠绝对定位，或至少两类环境来源一致）——可以改变工时状态；
 * - MAINTAINED：弱证据只够维持上一地点（或 20 分钟连续性窗口内保持）——不能触发状态转换；
 * - UNKNOWN：无法判定，reason 给出细分原因（NO_DATA/STALE/CONFLICT/LOW_CONFIDENCE）。
 */
enum class FusedDecision { CONFIRMED, MAINTAINED, UNKNOWN }

data class EvidenceObservation(
    val eventTime: Long,
    val receivedAt: Long,
    val source: EvidenceSource,
    val quality: Double,
    val placeHint: ResolvedPlace,
    val identifierHash: String?,
    val signal: Int?,
    /** 绝对定位来源（gps/network/passive），环境证据为空 */
    val provider: String? = null,
    /** 原始精度（米），环境证据为空 */
    val accuracyMeters: Float? = null
)

data class FusedEvidence(
    val place: ResolvedPlace,
    val confidence: Double,
    val firstReliableAt: Long?,
    val sources: Set<EvidenceSource>,
    val decision: FusedDecision = FusedDecision.CONFIRMED,
    /**
     * 决策原因：CONFIRMED_GNSS / CONFIRMED_AMBIENT / MAINTAIN_WEAK_EVIDENCE /
     * MAINTAIN_CONTINUITY / UNKNOWN_NO_DATA / UNKNOWN_STALE / UNKNOWN_CONFLICT /
     * UNKNOWN_LOW_CONFIDENCE 等
     */
    val reason: String = ""
)
