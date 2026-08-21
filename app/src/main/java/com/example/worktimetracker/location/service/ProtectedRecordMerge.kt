package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.ManualField
import com.example.worktimetracker.data.entity.ManualFieldMask
import com.example.worktimetracker.data.entity.WorkRecordEntity

object ProtectedRecordMerge {
    fun merge(existing: WorkRecordEntity, automatic: WorkRecordEntity): WorkRecordEntity {
        val mask = existing.manualFieldsMask
        fun protected(field: ManualField) = ManualFieldMask.contains(mask, field)
        val filled = (!protected(ManualField.COMPANY_DEPARTURE) && existing.endTime == null && automatic.endTime != null) ||
            (!protected(ManualField.HOME_ARRIVAL) && existing.homeArrivalTime == null && automatic.homeArrivalTime != null)
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
            needsReview = existing.needsReview || automatic.needsReview || filled,
            updatedAt = automatic.updatedAt
        )
    }
}
