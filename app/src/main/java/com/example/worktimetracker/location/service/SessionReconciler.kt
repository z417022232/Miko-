package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.data.entity.WorkStateEntity

object SessionReconciler {
    sealed interface Plan {
        data object None : Plan
        data class Fill(val companyDeparture: Long?, val homeArrival: Long?, val needsReview: Boolean) : Plan
    }

    fun plan(state: WorkStateEntity, record: WorkRecordEntity, expectedSessionId: String?): Plan {
        if (state.sessionId == null || state.sessionId != expectedSessionId || record.startTime == null) return Plan.None
        if (record.endTime != null && (record.homeArrivalTime != null || state.candidateHomeArrivalTime == null)) return Plan.None
        val departure = state.candidateCompanyDepartureTime?.takeIf { it >= record.startTime }
        val home = state.candidateHomeArrivalTime?.takeIf { departure != null && it >= departure }
        if (departure == null) return Plan.None
        return Plan.Fill(departure, home, home == null)
    }
}
