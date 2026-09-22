package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkStateEntity
import com.example.worktimetracker.domain.journey.*
import com.example.worktimetracker.location.service.JourneyAuthorityAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JourneyAuthorityAdapterTest {
    @Test fun companyArrivalCreatesWorkingSessionFromOccurredTime() {
        val event = JourneyEvent.CompanyArrival(100, 130)
        val next = JourneyAuthorityAdapter.apply(
            WorkStateEntity(currentState = "LEAVING_HOME", sessionId = "s"),
            transition(JourneyPhase.AT_WORK, event), "new", 130
        )
        assertEquals("WORKING", next.currentState)
        assertEquals(100L, next.sessionStart)
        assertEquals(130L, next.companyArrivalConfirmedAt)
    }

    @Test fun departureAndHomeArrivalInOneBeatCloseThenRestTheSession() {
        val next = JourneyAuthorityAdapter.apply(
            WorkStateEntity(currentState = "WORKING", sessionId = "s", sessionStart = 10),
            transition(
                JourneyPhase.AT_HOME,
                JourneyEvent.CompanyDeparture(200, 260),
                JourneyEvent.HomeArrival(250, 260)
            ), "new", 260
        )
        assertEquals("REST", next.currentState)
        assertEquals(200L, next.confirmedDepartureTime)
        assertEquals(250L, next.homeArrivalTime)
        assertNull(next.sessionStart)
    }

    @Test fun tempLeaveRoundTripDoesNotEndSession() {
        val start = JourneyAuthorityAdapter.apply(
            WorkStateEntity(currentState = "WORKING", sessionId = "s", sessionStart = 10),
            transition(JourneyPhase.TEMP_LEAVE, JourneyEvent.TempLeaveStart(50, 70)), "new", 70
        )
        val end = JourneyAuthorityAdapter.apply(
            start,
            transition(JourneyPhase.AT_WORK, JourneyEvent.TempLeaveEnd(90, 100)), "new", 100
        )
        assertEquals("WORKING", end.currentState)
        assertEquals(10L, end.sessionStart)
        assertNull(end.tempLeaveStart)
    }

    @Test fun homeDepartureClearsPreviousHomeArrivalTraces() {
        // 新一轮通勤开始时必须清掉上一班的到家痕迹：留着会跨班次残留，
        // 被下一班的「离岗计时确认」当成到家证据（实测 09-20 因此把下班路上判成已到家）
        val next = JourneyAuthorityAdapter.apply(
            WorkStateEntity(currentState = "REST", sessionId = "old", sessionStart = 10,
                homeArrivalTime = 500, candidateHomeArrivalTime = 500, confirmedDepartureTime = 400),
            transition(JourneyPhase.COMMUTING_TO_WORK, JourneyEvent.HomeDeparture(600, 610)), "new", 610
        )
        assertEquals("LEAVING_HOME", next.currentState)
        assertNull(next.homeArrivalTime)
        assertNull(next.candidateHomeArrivalTime)
        assertNull(next.confirmedDepartureTime)
    }

    private fun transition(phase: JourneyPhase, vararg events: JourneyEvent) = JourneyTransition(
        JourneySnapshot(phase, null, phase, 0), events.toList(), setOf(JourneyReason.PLACE_CONFIRMED), "test"
    )
}
