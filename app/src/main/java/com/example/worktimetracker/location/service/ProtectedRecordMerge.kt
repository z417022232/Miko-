package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.ManualField
import com.example.worktimetracker.data.entity.ManualFieldMask
import com.example.worktimetracker.data.entity.WorkRecordEntity

/**
 * 记录合并语义：
 * - FINALIZE_SESSION：正常下班完结。自动补齐空字段（如 endTime）是会话正常收尾，不触发人工审核；
 *   审核结果以最终计算为准，仅人工记录/带人工保护字段时保留原有标记。
 * - REPAIR_FILL：服务恢复补全/历史数据修复。自动补缺失字段代表证据曾中断，必须人工核对；原有审核标记一律保留。
 */
enum class MergeMode { FINALIZE_SESSION, REPAIR_FILL }

object ProtectedRecordMerge {
    fun merge(
        existing: WorkRecordEntity,
        automatic: WorkRecordEntity,
        mode: MergeMode = MergeMode.REPAIR_FILL
    ): WorkRecordEntity {
        // A3: 只有"人工保护位"才阻止自动覆盖；自动痕迹位（AUTO_*）不参与保护判断。
        val humanMask = ManualFieldMask.humanOnly(existing.manualFieldsMask)
        fun protected(field: ManualField) = ManualFieldMask.contains(humanMask, field)
        val filled = (!protected(ManualField.COMPANY_DEPARTURE) && existing.endTime == null && automatic.endTime != null) ||
            (!protected(ManualField.HOME_ARRIVAL) && existing.homeArrivalTime == null && automatic.homeArrivalTime != null)
        val preserveExistingReview = existing.isManual || humanMask != 0
        return existing.copy(
            status = if (existing.isManual) existing.status else automatic.status,
            shift = if (protected(ManualField.SHIFT)) existing.shift else automatic.shift ?: existing.shift,
            startTime = if (protected(ManualField.COMPANY_ARRIVAL)) existing.startTime else automatic.startTime ?: existing.startTime,
            endTime = if (protected(ManualField.COMPANY_DEPARTURE)) existing.endTime else automatic.endTime ?: existing.endTime,
            homeDepartureTime = if (protected(ManualField.HOME_DEPARTURE)) existing.homeDepartureTime else automatic.homeDepartureTime ?: existing.homeDepartureTime,
            homeArrivalTime = if (protected(ManualField.HOME_ARRIVAL)) existing.homeArrivalTime else automatic.homeArrivalTime ?: existing.homeArrivalTime,
            actualMinutes = automatic.actualMinutes ?: existing.actualMinutes,
            finalMinutes = if (protected(ManualField.FINAL_MINUTES)) existing.finalMinutes else automatic.finalMinutes,
            note = if (protected(ManualField.NOTE)) existing.note else automatic.note ?: existing.note,
            // A2: 复核原因以自动计算为准；无新原因时保留原值
            reviewReason = automatic.reviewReason ?: existing.reviewReason,
            // 合并 mask：草稿的 manualFieldsMask (人工保护) + automatic 的 mask (v1 自动算标记)
            manualFieldsMask = existing.manualFieldsMask or automatic.manualFieldsMask,
            needsReview = when (mode) {
                MergeMode.FINALIZE_SESSION -> automatic.needsReview || (preserveExistingReview && existing.needsReview)
                MergeMode.REPAIR_FILL -> existing.needsReview || automatic.needsReview || filled
            },
            updatedAt = automatic.updatedAt
        )
    }
}
