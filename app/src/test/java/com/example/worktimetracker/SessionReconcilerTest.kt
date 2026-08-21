package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.data.entity.WorkStateEntity
import com.example.worktimetracker.location.service.SessionReconciler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReconcilerTest {
    @Test fun fillsSameSessionDepartureAndHome() {
        val state = WorkStateEntity(currentState="FINISHED", sessionId="s", sessionStart=100,
            candidateCompanyDepartureTime=900, candidateHomeArrivalTime=1000)
        val plan = SessionReconciler.plan(state, record(), "s") as SessionReconciler.Plan.Fill
        assertEquals(900L, plan.companyDeparture)
        assertEquals(1000L, plan.homeArrival)
    }

    @Test fun missingHomeEvidenceStaysNullAndRequiresReview() {
        val state = WorkStateEntity(currentState="FINISHED", sessionId="s", sessionStart=100,
            candidateCompanyDepartureTime=900)
        val plan = SessionReconciler.plan(state, record(), "s") as SessionReconciler.Plan.Fill
        assertNull(plan.homeArrival)
        assertTrue(plan.needsReview)
    }

    @Test fun differentSessionCannotUseStaleEvidence() {
        val state = WorkStateEntity(currentState="FINISHED", sessionId="old", sessionStart=100,
            candidateCompanyDepartureTime=900, candidateHomeArrivalTime=1000)
        assertTrue(SessionReconciler.plan(state, record(), "new") is SessionReconciler.Plan.None)
    }

    private fun record() = WorkRecordEntity(workDate="2026-08-20", status="WORK", startTime=100, finalMinutes=0)
}
