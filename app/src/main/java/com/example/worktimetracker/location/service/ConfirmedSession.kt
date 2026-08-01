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
        require(companyDeparture == null || companyDeparture >= companyArrival)
        require(homeDeparture == null || homeDeparture <= companyArrival)
        require(homeArrival == null || (companyDeparture != null && homeArrival >= companyDeparture))
        val base = existing ?: WorkRecordEntity(workDate = "", status = "WORK", finalMinutes = calculatedMinutes)
        return base.copy(
            status = if (base.isManual) base.status else "WORK",
            shift = shift,
            startTime = companyArrival,
            endTime = companyDeparture,
            homeDepartureTime = homeDeparture,
            homeArrivalTime = homeArrival,
            actualMinutes = actualMinutes,
            finalMinutes = if (base.isManual) base.finalMinutes else calculatedMinutes,
            needsReview = base.needsReview || needsReview,
            updatedAt = System.currentTimeMillis()
        )
    }
}
