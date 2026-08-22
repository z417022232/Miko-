package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.data.entity.ManualField

object ManualRecordEditor {
    fun apply(
        existing: WorkRecordEntity?,
        workDate: String,
        shift: String,
        finalMinutes: Int,
        note: String,
        now: Long = System.currentTimeMillis()
    ): WorkRecordEntity = (existing ?: WorkRecordEntity(
        workDate = workDate,
        status = "MANUAL",
        createdAt = now
    )).copy(
        status = "MANUAL",
        shift = shift,
        finalMinutes = finalMinutes,
        isManual = true,
        manualFieldsMask = existing?.manualFieldsMask.orZero() or
            ManualField.SHIFT.bit or ManualField.FINAL_MINUTES.bit or ManualField.NOTE.bit,
        needsReview = false,
        note = note,
        updatedAt = now
    )

    private fun Int?.orZero(): Int = this ?: 0
}
