package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.data.entity.WorkRecordEntity

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
        needsReview = false,
        note = note,
        updatedAt = now
    )
}
