package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.data.entity.ManualField
import com.example.worktimetracker.domain.model.FinalMinutesSource

object ReviewRecordEditor {
    fun confirm(
        existing: WorkRecordEntity,
        shift: String,
        startMillis: Long?,
        endMillis: Long?,
        finalMinutes: Int,
        note: String,
        now: Long = System.currentTimeMillis()
    ): Result<WorkRecordEntity> = runCatching {
        require(shift == "DAY_SHIFT" || shift == "NIGHT_SHIFT") { "请选择白班或夜班" }
        require(finalMinutes >= 0) { "工时不能小于零" }
        require(startMillis == null || endMillis == null || endMillis > startMillis) { "离岗时间必须晚于到岗时间" }
        existing.copy(
            status = "MANUAL",
            shift = shift,
            startTime = startMillis,
            endTime = endMillis,
            finalMinutes = finalMinutes,
            // 用户手改的工时 = 人工事实，可进训练集（与「算法实算」同列，与「固定工时」区分）
            finalMinutesSource = FinalMinutesSource.MANUAL.name,
            isManual = true,
            manualFieldsMask = existing.manualFieldsMask or ManualField.SHIFT.bit or
                ManualField.COMPANY_ARRIVAL.bit or ManualField.COMPANY_DEPARTURE.bit or
                ManualField.FINAL_MINUTES.bit or ManualField.NOTE.bit or
                // A6: 走过编辑确认 = 用户已复核过，记 ACK 位
                ManualField.NEEDS_REVIEW_ACK.bit,
            needsReview = false,
            note = note,
            updatedAt = now
        )
    }
}
