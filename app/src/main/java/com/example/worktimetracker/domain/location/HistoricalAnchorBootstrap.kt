package com.example.worktimetracker.domain.location

/**
 * 旧数据预学习的直接接管门槛。
 * AnchorLearner 已经保证点数、精度、稳定时长、环境来源和离散度；这里仅补充
 * “覆盖完整验证周期”与“偏移仍在自动平滑范围”两项，避免重复等待七天。
 */
object HistoricalAnchorBootstrap {

    /** 只看数据本身：历史样本覆盖够、偏移仍在自动平滑档内。 */
    fun dataSufficient(distinctDays: Int, offsetMeters: Double): Boolean =
        distinctDays >= AnchorUpdatePolicy.SHADOW_VALIDATION_DAYS &&
            offsetMeters.isFinite() &&
            offsetMeters <= AnchorUpdatePolicy.AUTO_SMOOTH_MAX_OFFSET_METERS

    /**
     * 是否允许用历史数据**直接接管**（跳过 [ShadowValidator] 的六条件）。
     *
     * [everApplied] = 该地点**曾经**生效过（`learned_place_models.everApplied`，只增不减）。
     * 它与 `autoApplied`（算法侧当前开关）必须分开看：`autoApplied == false` 有两种完全不同的
     * 含义 ——「从没生效过」与「生效过、但被影子验证否决」。只拿 `autoApplied` 当闸门时，
     * 后者每轮都会被候选自带的 30 天历史样本直接放行，于是影子验证被永久绕过
     * （实测：站点 1 / 站点 2 每天在 SHADOW ↔ AUTO_APPLIED 之间振荡）。
     *
     * ⚠️ 门槛必须**结构上可失败**：`everApplied` 为真时必然 false，不看天数、不看偏移 ——
     * 否则又是一条形同虚设的线。
     */
    fun canApply(everApplied: Boolean, distinctDays: Int, offsetMeters: Double): Boolean =
        !everApplied && dataSufficient(distinctDays, offsetMeters)
}
