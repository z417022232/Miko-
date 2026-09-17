package com.example.worktimetracker.domain.learning

/**
 * 全项目统一的**可信等级**（方案 §十 `Confidence.kt`）。
 *
 * 之前可信度只在 UI 侧有一个（`FusedStatusFormatter.ConfidenceLevel`）——
 * 那是**展示档位**，分档线复用融合引擎常量。这里的是**模型置信度**，
 * 服务对象不同（模型要不要自动生效、要不要请用户确认），所以独立存在，
 * 但两者共用同一套档位名，避免用户看到两套「高中低」。
 *
 * ⚠️ 档位线一律**引用已有门槛**，不另立魔数（同 v7.2 位置三段式的做法）。
 */
enum class ConfidenceLevel(val label: String) {
    HIGH("高"),
    MEDIUM("中"),
    LOW("低"),
    NONE("—");

    /** 只有高中两档允许模型自动生效；低置信度只提示，不自动改变工时状态。 */
    val allowsAutoApply: Boolean get() = this == HIGH || this == MEDIUM
}

object Confidence {

    /** 自动生效的置信度下限（与地点模型同一条线，避免两处各定一个）。 */
    const val AUTO_APPLY_FLOOR = 0.70

    /** 由 0..1 的连续分数分档。 */
    fun fromScore(score: Double): ConfidenceLevel = when {
        score <= 0.0 -> ConfidenceLevel.NONE
        score >= 0.85 -> ConfidenceLevel.HIGH
        score >= AUTO_APPLY_FLOOR -> ConfidenceLevel.MEDIUM
        else -> ConfidenceLevel.LOW
    }

    /**
     * 由样本量分档 —— 「样本足够多」本身就能兜住一部分不确定性。
     *
     * 分档线取自方案自己给的门槛：地点锚点要求 ≥10 个有效点、跨 ≥5 天；
     * 这里用「天数」当主口径，因为跨天比堆同一天的样本更能说明稳定性。
     */
    fun fromSampleDays(distinctDays: Int): ConfidenceLevel = when {
        distinctDays <= 0 -> ConfidenceLevel.NONE
        distinctDays >= 10 -> ConfidenceLevel.HIGH
        distinctDays >= 5 -> ConfidenceLevel.MEDIUM
        else -> ConfidenceLevel.LOW
    }

    /**
     * 取两者中较保守的一档。
     *
     * 「分数高但只观察过 2 天」不该算出高可信 —— 交集而非并集，是这套学习系统
     * 防止「一次侥幸」变成「长期结论」的核心手段。
     *
     * ⚠️ 枚举顺序是 `HIGH, MEDIUM, LOW, NONE`，即 **ordinal 越大档位越低**，
     * 所以「较保守」= **取 ordinal 较大**的那个。这里曾经写反过一次
     * （取成 ordinal 较小 = 偏高那档），是靠 `ConfidenceTest` 逮住的 ——
     * 这类方向性错误不会报错，只会静默地把门槛全部放松，必须有测试钉住。
     */
    fun conservative(a: ConfidenceLevel, b: ConfidenceLevel): ConfidenceLevel =
        if (a.ordinal >= b.ordinal) a else b

    /** 把分数 + 样本天数折算成最终档位（先各自分档再取保守）。 */
    fun combine(score: Double, distinctDays: Int): ConfidenceLevel =
        conservative(fromScore(score), fromSampleDays(distinctDays))
}
