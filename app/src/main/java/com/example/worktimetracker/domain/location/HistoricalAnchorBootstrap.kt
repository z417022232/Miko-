package com.example.worktimetracker.domain.location

/**
 * 旧数据预学习的直接接管门槛。
 * AnchorLearner 已经保证点数、精度、稳定时长、环境来源和离散度；这里仅补充
 * “覆盖完整验证周期”与“偏移仍在自动平滑范围”两项，避免重复等待七天。
 */
object HistoricalAnchorBootstrap {
    fun canApply(distinctDays: Int, offsetMeters: Double): Boolean =
        distinctDays >= AnchorUpdatePolicy.SHADOW_VALIDATION_DAYS &&
            offsetMeters.isFinite() &&
            offsetMeters <= AnchorUpdatePolicy.AUTO_SMOOTH_MAX_OFFSET_METERS
}
