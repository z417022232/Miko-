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

    /**
     * 位置主句：一眼看懂「现在人在哪」。
     *
     * 为什么不用「位置：家」：那是把内部字段直译给用户看。用户要的是结论 ——
     * 「现在在家 / 现在在公司 / 位置暂时判断不出来」，而且 UNKNOWN 时**绝不指认地点**
     * （说"位置：暂不确定"等于没说，还不如直接讲清楚是判断不出来）。
     */
    fun placeSentence(snapshot: FusedStatusSnapshot?): String {
        if (snapshot == null) return "还没有位置判断"
        if (snapshot.decision == FusedDecision.UNKNOWN) return "位置暂时判断不出来"
        return when (snapshot.place) {
            ResolvedPlace.HOME -> "现在在家"
            ResolvedPlace.COMPANY -> "现在在公司"
            ResolvedPlace.MOVING -> "正在路上"
            ResolvedPlace.UNKNOWN -> "位置暂时判断不出来"
            ResolvedPlace.OTHER -> "在别的地方"
        }
    }

    /**
     * 置信度的**人话档位**。光一个「80%」用户没法判断该不该信，配一个词才直观。
     *
     * 分档线直接**复用融合引擎自己的门槛**，不另立魔数：
     * - `CONFIRMED` 且 ≥ [EvidenceFusionEngine.GNSS_RELIABLE_QUALITY] → 高
     *   （这就是引擎认定"可靠、可以改变工时状态"的那条线，UI 不能比它更保守）；
     * - `CONFIRMED` 且 ≥ 0.70 → 中（环境证据刚好越过 1.40 双源门槛，
     *   confidence = 质量和/来源类数，两源各 0.70 时正好是 0.70）；
     * - `MAINTAINED` 最高只能给「中」——单源弱证据只能维持上一判断，**不允许**显示成高可信；
     * - `UNKNOWN` / 置信度为 0 → 无（配合主句「位置暂时判断不出来」）。
     */
    enum class ConfidenceLevel(val label: String) {
        HIGH("高"),
        MEDIUM("中"),
        LOW("低"),
        NONE("—")
    }

    /** 判「中」的下限：环境证据双源门槛 1.40 摊到两类的值。 */
    private const val AMBIENT_MEDIUM_FLOOR = 0.70

    fun confidenceLevel(snapshot: FusedStatusSnapshot?): ConfidenceLevel {
        if (snapshot == null) return ConfidenceLevel.NONE
        if (snapshot.decision == FusedDecision.UNKNOWN || snapshot.confidence <= 0.0) {
            return ConfidenceLevel.NONE
        }
        val raw = when {
            snapshot.confidence >= EvidenceFusionEngine.GNSS_RELIABLE_QUALITY -> ConfidenceLevel.HIGH
            snapshot.confidence >= AMBIENT_MEDIUM_FLOOR -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
        // 弱证据只能维持，不给「高」
        return if (snapshot.decision == FusedDecision.MAINTAINED && raw == ConfidenceLevel.HIGH) {
            ConfidenceLevel.MEDIUM
        } else {
            raw
        }
    }

    /**
     * 判断依据：为什么这么判。直接复用 [reasonLabel]，保证与融合详情页同一套人话。
     * 无快照时返回 null（页面显示「还没有位置判断」即可）。
     */
    fun basisLabel(snapshot: FusedStatusSnapshot?): String? =
        snapshot?.let { reasonLabel(it.reason) }

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
