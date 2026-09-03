package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkStateEntity
import com.example.worktimetracker.domain.model.LocationType
import com.example.worktimetracker.location.service.TrajectoryAnchorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryAnchorEngineTest {
    private val engine = TrajectoryAnchorEngine()
    private val config = TrajectoryAnchorEngine.Config(250, 300, 100, 100, 20)

    @Test fun candidateRadiusDoesNotConfirmCompanyArrival() {
        val state = WorkStateEntity(currentState = "LEAVING_HOME", sessionId = "s")
        val decision = engine.next(state, fix(100, LocationType.COMPANY, company=220.0, companyAnchor=180.0), config)
        assertTrue(decision.events.isEmpty())
        assertEquals("LEAVING_HOME", decision.nextState.currentState)
    }

    @Test fun twoStablePointsConfirmFirstAnchorTime() {
        val state = WorkStateEntity(currentState = "LEAVING_HOME", sessionId = "s")
        val first = engine.next(state, fix(100, LocationType.COMPANY, company=80.0, companyAnchor=70.0), config)
        val second = engine.next(first.nextState, fix(160, LocationType.COMPANY, company=70.0, companyAnchor=60.0), config)
        val event = second.events.single() as TrajectoryAnchorEngine.Event.CompanyArrival
        assertEquals(100L, event.occurredAt)
        assertEquals(160L, event.confirmedAt)
    }

    @Test fun edgeReturnDoesNotCancelDepartureButDeepReturnDoes() {
        val working = WorkStateEntity(currentState = "WORKING", sessionId = "s", sessionStart = 1L)
        val first = engine.next(working, fix(100, LocationType.OTHER, company=281.0, companyAnchor=181.0, moving=true), config)
        val edge = engine.next(first.nextState, fix(160, LocationType.COMPANY, company=211.0, companyAnchor=111.0), config)
        assertEquals(100L, edge.nextState.candidateCompanyDepartureTime)
        val deep1 = engine.next(edge.nextState, fix(220, LocationType.COMPANY, company=70.0, companyAnchor=60.0), config)
        val deep2 = engine.next(deep1.nextState, fix(280, LocationType.COMPANY, company=60.0, companyAnchor=50.0), config)
        assertNull(deep2.nextState.candidateCompanyDepartureTime)
        assertEquals("WORKING", deep2.nextState.currentState)
    }

    @Test fun firstHomeTimeSurvivesLaterConfirmation() {
        val state = WorkStateEntity(currentState = "TEMP_LEAVE", sessionId = "s", sessionStart = 1L,
            candidateCompanyDepartureTime = 100L, movingAwayCount = 2)
        val firstHome = engine.next(state, fix(200, LocationType.HOME, company=2000.0, companyAnchor=1900.0, homeAnchor=40.0), config)
        val confirmed = engine.next(firstHome.nextState, fix(1_300_000, LocationType.HOME, company=2000.0, companyAnchor=1900.0, homeAnchor=20.0), config)
        val home = confirmed.events.filterIsInstance<TrajectoryAnchorEngine.Event.HomeArrival>().single()
        assertEquals(200L, home.occurredAt)
        assertEquals(1_300_000L, home.confirmedAt)
    }

    @Test fun newSessionClearsPreviousSessionFields() {
        val old = WorkStateEntity(currentState = "REST", sessionId = "old", confirmedDepartureTime = 50L,
            homeArrivalTime = 60L, candidateHomeArrivalTime = 60L)
        val next = engine.next(old, fix(500, LocationType.OTHER, homeAnchor=180.0, moving=true), config)
        assertNotEquals("old", next.nextState.sessionId)
        assertNull(next.nextState.confirmedDepartureTime)
        assertNull(next.nextState.homeArrivalTime)
        assertNull(next.nextState.candidateHomeArrivalTime)
    }

    @Test fun homeWhileRestDoesNotStartCommute() {
        val decision = engine.next(
            WorkStateEntity(currentState = "REST"),
            fix(1_000L, LocationType.HOME, homeAnchor = 20.0), config
        )
        assertEquals("REST", decision.nextState.currentState)
        assertTrue(decision.events.isEmpty())
    }

    @Test fun nearCompanyCandidateExpiresAcrossFifteenHourGap() {
        val state = WorkStateEntity(currentState = "NEAR_COMPANY", sessionId = "s",
            candidateCompanyArrivalTime = 100L, stableCompanyCount = 1, lastLocationTime = 100L)
        val decision = engine.next(state,
            fix(54_000_100L, LocationType.COMPANY, company = 70.0, companyAnchor = 60.0), config)
        assertTrue(decision.events.filterIsInstance<TrajectoryAnchorEngine.Event.CompanyArrival>().isEmpty())
        assertEquals(54_000_100L, decision.nextState.candidateCompanyArrivalTime)
        assertEquals(1, decision.nextState.stableCompanyCount)
    }

    @Test fun lateHomeAfterFinishedCompletesExistingSession() {
        val state = WorkStateEntity(currentState = "FINISHED", sessionId = "s", sessionStart = 1_000L,
            confirmedDepartureTime = 2_000L, homeArrivalTime = null)
        val decision = engine.next(state,
            fix(3_000L, LocationType.HOME, company = 2_000.0, companyAnchor = 1_900.0, homeAnchor = 20.0), config)
        assertEquals("REST", decision.nextState.currentState)
        assertEquals(3_000L, decision.nextState.homeArrivalTime)
        assertEquals(3_000L,
            decision.events.filterIsInstance<TrajectoryAnchorEngine.Event.HomeArrival>().single().occurredAt)
    }

    @Test fun fiveMinuteUserSettingIsUsedExactly() {
        val fiveMinutes = config.copy(leaveConfirmMinutes = 5)
        val state = WorkStateEntity(currentState = "TEMP_LEAVE", sessionId = "s", sessionStart = 100L,
            candidateCompanyDepartureTime = 1_000L, movingAwayCount = 2)
        val before = engine.next(state,
            fix(300_999L, LocationType.OTHER, company = 500.0, companyAnchor = 500.0, moving = true), fiveMinutes)
        val due = engine.next(before.nextState,
            fix(301_000L, LocationType.OTHER, company = 500.0, companyAnchor = 500.0, moving = true), fiveMinutes)
        assertEquals("TEMP_LEAVE", before.nextState.currentState)
        assertEquals(1_000L,
            due.events.filterIsInstance<TrajectoryAnchorEngine.Event.CompanyDeparture>().single().occurredAt)
    }

    @Test fun homeBeforeCompanyDepartureIsRejected() {
        val state = WorkStateEntity(currentState = "TEMP_LEAVE", sessionId = "s", sessionStart = 100L,
            candidateCompanyDepartureTime = 2_000L, candidateHomeArrivalTime = 1_000L, movingAwayCount = 2)
        val decision = engine.next(state,
            fix(1_300_000L, LocationType.HOME, company = 2_000.0, companyAnchor = 1_900.0,
                homeAnchor = 20.0, moving = true), config)
        assertTrue(decision.events.filterIsInstance<TrajectoryAnchorEngine.Event.HomeArrival>().isEmpty())
    }

    @Test fun environmentDisappearanceAloneDoesNotLeaveCompany() {
        val state = WorkStateEntity(currentState = "WORKING", sessionId = "s", sessionStart = 100L)
        val decision = engine.next(state, fix(1_000L, LocationType.UNKNOWN), config)
        assertEquals("WORKING", decision.nextState.currentState)
        assertTrue(decision.events.isEmpty())
    }

    @Test fun confirmedWorkingSessionSurvivesContinuityBreak() {
        val state = WorkStateEntity(currentState = "WORKING", sessionId = "s", sessionStart = 100L,
            lastLocationTime = 100L)
        val decision = engine.next(state,
            fix(54_000_000L, LocationType.COMPANY, company = 70.0, companyAnchor = 60.0), config)
        assertEquals("WORKING", decision.nextState.currentState)
        assertEquals(100L, decision.nextState.sessionStart)
        assertEquals("s", decision.nextState.sessionId)
        assertTrue(decision.events.isEmpty())
    }

    private fun fix(time: Long, type: LocationType, company: Double? = null, companyAnchor: Double? = null,
        homeAnchor: Double? = null, moving: Boolean = false) = TrajectoryAnchorEngine.Fix(
        time, type, 10f, "gps", company, companyAnchor, null, homeAnchor, 0f, moving
    )
}
