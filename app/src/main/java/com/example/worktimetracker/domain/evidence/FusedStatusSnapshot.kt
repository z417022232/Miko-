package com.example.worktimetracker.domain.evidence

/**
 * 融合状态快照（方案十 UI 部分）：把 FusedEvidence 实时暴露给界面。
 *
 * 只做状态通道与解释翻译，不参与任何决策；每次融合（GPS 或环境路径）都会覆盖。
 */
data class FusedStatusSnapshot(
    val place: ResolvedPlace,
    val decision: FusedDecision,
    /** 融合原因码，如 CONFIRMED_GNSS / MAINTAIN_WEAK_EVIDENCE / UNKNOWN_CONFLICT */
    val reason: String,
    val confidence: Double,
    val sources: Set<EvidenceSource>,
    /** 各来源最新证据明细（由协调器生成），如 "wifi=HOME q0.90 30s前(cell -85m)" */
    val sourceBreakdown: String? = null,
    val updatedAt: Long = 0L
)

/** 把快照翻译成人话：标题、决策档位、原因解释。纯 Kotlin，可单元测试。 */
object FusedStatusFormatter {

    fun placeLabel(place: ResolvedPlace): String = when (place) {
        ResolvedPlace.HOME -> "家"
        ResolvedPlace.COMPANY -> "公司"
        ResolvedPlace.UNKNOWN -> "暂不确定"
        else -> "其他地点"
    }

    fun decisionLabel(decision: FusedDecision): String = when (decision) {
        FusedDecision.CONFIRMED -> "已确认"
        FusedDecision.MAINTAINED -> "暂时维持"
        FusedDecision.UNKNOWN -> "位置不确定"
    }

    /** 卡片标题：UNKNOWN 不指认地点，只说明暂不确定。 */
    fun headline(snapshot: FusedStatusSnapshot?): String {
        if (snapshot == null) return "暂无位置判断"
        return when (snapshot.decision) {
            FusedDecision.CONFIRMED, FusedDecision.MAINTAINED ->
                "当前判断：${placeLabel(snapshot.place)}"
            FusedDecision.UNKNOWN -> "当前位置暂不确定"
        }
    }

    /** 原因码 → 人话解释。未知原因码原样返回，保证新原因不会显示为空。 */
    fun reasonLabel(reason: String): String = when {
        reason.startsWith("CONFIRMED_GNSS") -> "GPS 定位确认"
        reason.startsWith("CONFIRMED_NETWORK_LOCATION") -> "网络定位确认"
        reason.startsWith("CONFIRMED_AMBIENT") -> "Wi-Fi/蓝牙/基站环境证据确认"
        reason.startsWith("MAINTAIN_WEAK_EVIDENCE") -> "当前只有单一环境来源，等待更多证据"
        reason.startsWith("MAINTAIN_CONTINUITY") -> "上一判断的证据仍在有效期内，维持当前判断"
        reason.startsWith("UNKNOWN_CONFLICT") -> "公司和家庭的环境证据发生冲突，正在等待下一轮定位确认"
        reason.startsWith("UNKNOWN_STALE") -> "最近的有效位置证据已经过期，自动记录不会因此修改工时"
        reason.startsWith("UNKNOWN_NO_DATA") -> "当前没有可用的位置证据"
        reason.startsWith("UNKNOWN_LOW_CONFIDENCE") -> "证据不足，无法确认位置"
        else -> reason
    }

    /** 置信度百分比文案；无有效置信度时返回 null。 */
    fun confidenceLabel(snapshot: FusedStatusSnapshot): String? {
        if (snapshot.confidence <= 0.0) return null
        return "${(snapshot.confidence * 100).toInt().coerceIn(0, 100)}%"
    }

    /** 来源列表文案：GPS、网络定位、Wi-Fi、蓝牙、基站；为空返回 null。 */
    fun sourcesLabel(snapshot: FusedStatusSnapshot): String? {
        if (snapshot.sources.isEmpty()) return null
        val labels = snapshot.sources.map {
            when (it) {
                EvidenceSource.GNSS -> "GPS"
                EvidenceSource.NETWORK_LOCATION -> "网络定位"
                EvidenceSource.WIFI -> "Wi-Fi"
                EvidenceSource.BLUETOOTH -> "蓝牙"
                EvidenceSource.CELL -> "基站"
                EvidenceSource.MOTION -> "运动"
                EvidenceSource.SHIFT_WINDOW -> "班次窗口"
            }
        }.distinct()
        return labels.joinToString(" · ")
    }
}
