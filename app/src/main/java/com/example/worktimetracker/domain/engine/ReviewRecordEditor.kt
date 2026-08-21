package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.data.entity.ManualField

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
            isManual = true,
            manualFieldsMask = existing.manualFieldsMask or ManualField.SHIFT.bit or
                ManualField.COMPANY_ARRIVAL.bit or ManualField.COMPANY_DEPARTURE.bit or
                ManualField.FINAL_MINUTES.bit or ManualField.NOTE.bit,
            needsReview = false,
            note = note,
            updatedAt = now
        )
    }
}
