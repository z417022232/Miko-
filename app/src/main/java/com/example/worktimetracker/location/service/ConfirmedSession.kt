package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.WorkRecordEntity

object ConfirmedSession {
    fun merge(
        existing: WorkRecordEntity?,
        shift: String,
        companyArrival: Long,
        companyDeparture: Long?,
        homeDeparture: Long?,
        homeArrival: Long?,
        actualMinutes: Int?,
        calculatedMinutes: Int,
        needsReview: Boolean,
        status: String = "WORK",
        mode: MergeMode = MergeMode.REPAIR_FILL
    ): WorkRecordEntity {
        val validDeparture = companyDeparture?.takeIf { it >= companyArrival }
        val validHomeDeparture = homeDeparture?.takeIf { it <= companyArrival }
        val validHomeArrival = homeArrival?.takeIf { validDeparture != null && it >= validDeparture }
        val invalidOrder = validDeparture != companyDeparture || validHomeDeparture != homeDeparture || validHomeArrival != homeArrival
        val base = existing ?: WorkRecordEntity(workDate = "", status = status, finalMinutes = calculatedMinutes)
        // 人工记录/带人工保护字段的记录：原审核标记必须保留，避免自动计算把人工确认状态清掉
        val preserveExistingReview = base.isManual || base.manualFieldsMask != 0
        val review = when (mode) {
            // 正常下班完结：审核结果以最终计算为准，自动草稿阶段的临时标记不再粘住
            MergeMode.FINALIZE_SESSION -> needsReview || invalidOrder || (preserveExistingReview && base.needsReview)
            // 历史修复/恢复补全：补全本身就是异常信号，原有标记一律保留
            MergeMode.REPAIR_FILL -> base.needsReview || needsReview || invalidOrder
        }
        return base.copy(
            status = if (base.isManual) base.status else status,
            shift = shift,
            startTime = companyArrival,
            endTime = validDeparture,
            homeDepartureTime = validHomeDeparture,
            homeArrivalTime = validHomeArrival,
            actualMinutes = actualMinutes,
            finalMinutes = if (base.isManual) base.finalMinutes else calculatedMinutes,
            needsReview = review,
            updatedAt = System.currentTimeMillis()
        )
    }
}
