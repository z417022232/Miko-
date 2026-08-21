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
        needsReview: Boolean
    ): WorkRecordEntity {
        val validDeparture = companyDeparture?.takeIf { it >= companyArrival }
        val validHomeDeparture = homeDeparture?.takeIf { it <= companyArrival }
        val validHomeArrival = homeArrival?.takeIf { validDeparture != null && it >= validDeparture }
        val invalidOrder = validDeparture != companyDeparture || validHomeDeparture != homeDeparture || validHomeArrival != homeArrival
        val base = existing ?: WorkRecordEntity(workDate = "", status = "WORK", finalMinutes = calculatedMinutes)
        return base.copy(
            status = if (base.isManual) base.status else "WORK",
            shift = shift,
            startTime = companyArrival,
            endTime = validDeparture,
            homeDepartureTime = validHomeDeparture,
            homeArrivalTime = validHomeArrival,
            actualMinutes = actualMinutes,
            finalMinutes = if (base.isManual) base.finalMinutes else calculatedMinutes,
            needsReview = base.needsReview || needsReview || invalidOrder,
            updatedAt = System.currentTimeMillis()
        )
    }
}
