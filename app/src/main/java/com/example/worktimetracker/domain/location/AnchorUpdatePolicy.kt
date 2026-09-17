package com.example.worktimetracker.domain.location

/**
 * 候选锚点的生命周期状态（方案 §三.2 表格的落地形式）。
 *
 * 这张枚举是「自动校准敢不敢动用户配置」的**唯一裁决表**：
 * 只有 [AUTO_APPLIED] 的候选才会写进 `learned_place_models.autoApplied = true`，
 * 也只有那种行才会被 [PlaceModelResolver] 取用。
 */
enum class AnchorCandidateStatus {
    /** 影子运行：已观察、已落库，但**不参与任何判定** */
    SHADOW,

    /** 已自动小幅平滑生效（偏移在自动档内且通过影子验证期） */
    AUTO_APPLIED,

    /** 偏移过大，必须由用户确认（是否搬家 / 换公司）——不确认就永远不生效 */
    NEEDS_USER_CONFIRM,

    /** 门槛不达标，本次观察不足以形成候选 */
    REJECTED,

    /** 用户已确认采纳（人工确认的锚点等同配置锚点，优先级最高） */
    USER_ACCEPTED,

    /** 用户已否决，该候选不再重复提示 */
    USER_REJECTED
}

/** 一次候选评估的结论。 */
enum class AnchorUpdateAction { AUTO_SMOOTH, SHADOW, NEEDS_USER_CONFIRM, REJECTED }

/**
 * 锚点自动校准的判定与平滑策略（方案 §三.2）。
 *
 * 设计要点：**阈值全部来自方案原文**，一个都不现编；并且把「自动」缩到最小 ——
 * 漏判的代价是「用户被自己改的位置打脸」，误判的代价是「位置永远飘」，
 * 两者不对称，所以宁可多走影子。
 */
object AnchorUpdatePolicy {

    /** ≤ 该偏移：候选与配置锚点差别不大，允许自动平滑。 */
    const val AUTO_SMOOTH_MAX_OFFSET_METERS = 30.0

    /** 30~100m：影子验证，**不立即生效**；> 该值：必须请用户确认。 */
    const val SHADOW_MAX_OFFSET_METERS = 100.0

    /** 影子验证期：候选中心必须连续稳定这么多天，才允许自动生效。 */
    const val SHADOW_VALIDATION_DAYS = 7L

    /** 指数平滑权重：新锚点只吃 20%，**绝不直接替换**（方案原文 0.8 / 0.2）。 */
    const val SMOOTH_OLD_WEIGHT = 0.8
    const val SMOOTH_NEW_WEIGHT = 0.2

    /**
     * 判定动作。
     *
     * 顺序不能调：**先看偏移**（超过 100m 的"候选"根本不是校准问题，是搬家），
     * 再看**影子验证是否通过**（不通过一律不许生效，无论偏移多小）。
     *
     * ⚠️ 影子验证自 v9.1 起不是「等够 7 天」而是 [ShadowValidator] 的**六个条件**同时成立。
     * 原因：只看时间的话，一个已经漂了 200 米的候选熬够 7 天照样会生效 ——
     * 那影子期就只是等待，不是验证。
     */
    fun decide(offsetMeters: Double, shadow: ShadowValidator.Result): AnchorUpdateAction = when {
        offsetMeters > SHADOW_MAX_OFFSET_METERS -> AnchorUpdateAction.NEEDS_USER_CONFIRM
        !shadow.passed -> AnchorUpdateAction.SHADOW
        offsetMeters <= AUTO_SMOOTH_MAX_OFFSET_METERS -> AnchorUpdateAction.AUTO_SMOOTH
        else -> AnchorUpdateAction.SHADOW
    }

    /** 动作 → 落库状态。 */
    fun statusOf(action: AnchorUpdateAction): AnchorCandidateStatus = when (action) {
        AnchorUpdateAction.AUTO_SMOOTH -> AnchorCandidateStatus.AUTO_APPLIED
        AnchorUpdateAction.SHADOW -> AnchorCandidateStatus.SHADOW
        AnchorUpdateAction.NEEDS_USER_CONFIRM -> AnchorCandidateStatus.NEEDS_USER_CONFIRM
        AnchorUpdateAction.REJECTED -> AnchorCandidateStatus.REJECTED
    }

    /**
     * 指数平滑：`new = old × 0.8 + candidate × 0.2`。
     *
     * 为什么不直接替换：替换会让锚点在两次观察之间跳变，边界判定跟着抖；
     * 平滑把单次观察的影响压到 20%，一天挪几米，肉眼无感但长期收敛。
     */
    fun smooth(
        old: GeoPoint,
        candidate: GeoPoint,
        newWeight: Double = SMOOTH_NEW_WEIGHT
    ): GeoPoint {
        val w = newWeight.coerceIn(0.0, 1.0)
        return GeoPoint(
            latitude = old.latitude * (1 - w) + candidate.latitude * w,
            longitude = old.longitude * (1 - w) + candidate.longitude * w
        )
    }

    /**
     * 候选中心的置信度 0..1。
     *
     * 由「样本量、跨天数、环境支持、偏移大小」四件事共同决定 ——
     * 偏移越大越**不能**给高置信（否则大偏移会被误当成可信锚点）。
     */
    fun confidence(
        sampleCount: Int,
        distinctDays: Int,
        ambientSourceCount: Int,
        offsetMeters: Double
    ): Double {
        val samplePart = (sampleCount / 30.0).coerceIn(0.0, 1.0)
        val dayPart = (distinctDays / 10.0).coerceIn(0.0, 1.0)
        val ambientPart = (ambientSourceCount / 3.0).coerceIn(0.0, 1.0)
        val offsetPenalty = (offsetMeters / SHADOW_MAX_OFFSET_METERS).coerceIn(0.0, 1.0)
        val raw = 0.35 * samplePart + 0.35 * dayPart + 0.20 * ambientPart + 0.10 * (1.0 - offsetPenalty)
        return raw.coerceIn(0.0, 1.0)
    }

    /**
     * 人话解释（原则 6：每次判定必须能解释依据）。
     *
     * 文案刻意写成「对用户说」而不是「对日志说」—— 它会直接出现在地点管理页上。
     * 影子档的文案交给 [ShadowValidator.explain]：那里才看得到「还差什么」。
     */
    fun explain(action: AnchorUpdateAction, offsetMeters: Double, shadow: ShadowValidator.Result): String {
        val offsetText = "${offsetMeters.toInt()} 米"
        return when (action) {
            AnchorUpdateAction.SHADOW -> ShadowValidator.explain(shadow, offsetMeters)
            AnchorUpdateAction.AUTO_SMOOTH ->
                "已前向观察 ${shadow.validation.elapsedDays} 天且始终稳定，" +
                    "与设置位置相差 $offsetText，自动小幅校准"
            AnchorUpdateAction.NEEDS_USER_CONFIRM ->
                "与设置位置相差 $offsetText，已超过 ${SHADOW_MAX_OFFSET_METERS.toInt()} 米。" +
                    "如果确实搬家或换了公司，请手动更新地点"
            AnchorUpdateAction.REJECTED ->
                "本次观察未达到学习门槛（样本或稳定时长不足），不形成候选"
        }
    }
}
