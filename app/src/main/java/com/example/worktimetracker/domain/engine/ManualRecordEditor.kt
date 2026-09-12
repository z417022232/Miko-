package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.data.entity.ManualField
import com.example.worktimetracker.domain.model.ShiftType

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
        // 统一归一到枚举名：UI 曾把手动工时对话框的显示标签（"白班"/"夜班"）直接写回库，
        // 使这些记录在 ShiftProfileLearner / 日历标签链路中静默失效（见 ShiftType.normalize）。
        shift = ShiftType.normalize(shift),
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
