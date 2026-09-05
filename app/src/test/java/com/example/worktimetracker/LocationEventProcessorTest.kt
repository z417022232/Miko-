package com.example.worktimetracker

import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.data.entity.WorkStateEntity
import com.example.worktimetracker.domain.model.LocationType
import com.example.worktimetracker.location.service.LocationEventProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationEventProcessorTest {
    private val settings = UserSettingsEntity(leaveCompanyConfirmMinutes = 60)
    private val processor = LocationEventProcessor()

    @Test fun enteringCompanyStartsWorkingFlow() {
        val near = processor.nextState(WorkStateEntity(currentState = "REST"), LocationType.COMPANY, 1_000L, settings)
        assertEquals("NEAR_COMPANY", near.currentState)
        val working = processor.nextState(near, LocationType.COMPANY, 2_000L, settings)
        assertEquals("WORKING", working.currentState)
    }

    @Test fun shortTemporaryLeaveDoesNotFinish() {
        val working = WorkStateEntity(currentState = "WORKING", sessionStart = 1_000L, lastLocationTime = 1_000L)
        val temp = processor.nextState(working, LocationType.OTHER, 2_000L, settings)
        assertEquals("TEMP_LEAVE", temp.currentState)
        val stillTemp = processor.nextState(temp, LocationType.OTHER, 30 * 60_000L, settings)
        assertEquals("TEMP_LEAVE", stillTemp.currentState)
    }

    @Test fun leavingHomeKeepsOriginalStartForOutsideThreshold() {
        val leaving = processor.nextState(WorkStateEntity(currentState = "REST"), LocationType.OTHER, 1_000L, settings)
        assertEquals("LEAVING_HOME", leaving.currentState)
        assertEquals(1_000L, leaving.sessionStart)
        val stillLeaving = processor.nextState(leaving, LocationType.OTHER, 30 * 60_000L, settings)
        assertEquals("LEAVING_HOME", stillLeaving.currentState)
        assertEquals(1_000L, stillLeaving.sessionStart)
    }

    @Test fun companyArrivalDoesNotIncludeCommuteTime() {
        val leaving = processor.nextState(WorkStateEntity(currentState = "REST"), LocationType.OTHER, 1_000L, settings)
        val near = processor.nextState(leaving, LocationType.COMPANY, 2_000L, settings)
        assertEquals("NEAR_COMPANY", near.currentState)
        val working = processor.nextState(near, LocationType.COMPANY, 3_000L, settings)
        assertEquals("WORKING", working.currentState)
        assertEquals(2_000L, working.sessionStart)
        assertEquals(1_000L, working.homeDepartureTime)
    }

    @Test fun leaveLongerThanThresholdFinishes() {
        val working = WorkStateEntity(currentState = "WORKING", sessionStart = 1_000L, lastLocationTime = 1_000L)
        val temp = processor.nextState(working, LocationType.OTHER, 2_000L, settings)
        val updatedOnce = processor.nextState(temp, LocationType.OTHER, 30 * 60_000L, settings)
        val updatedAgain = processor.nextState(updatedOnce, LocationType.OTHER, 59 * 60_000L, settings)
        val finished = processor.nextState(updatedAgain, LocationType.OTHER, 61 * 60_000L, settings, 500.0, true)
        assertEquals("FINISHED", finished.currentState)
    }

    @Test fun finishedWithLateHomeEvidenceFillsArrivalTime() {
        // 场景复现 2026-09-05：离岗计时器先确认 FINISHED（firstHome=null），
        // 随后 CONFIRMED HOME 到达触发 FINISHED → REST，必须补齐 homeArrivalTime
        val finished = WorkStateEntity(
            currentState = "FINISHED", sessionStart = 1_000L,
            candidateCompanyDepartureTime = 2_000L, confirmedDepartureTime = 2_000L
        )
        val rest = processor.nextState(finished, LocationType.HOME, 362_000L, settings)
        assertEquals("REST", rest.currentState)
        assertEquals(362_000L, rest.homeArrivalTime)
        assertEquals(362_000L, rest.homeArrivalConfirmedAt)
    }

    @Test fun finishedWithPriorCandidateKeepsFirstHomeTimeOnRest() {
        val finished = WorkStateEntity(
            currentState = "FINISHED", sessionStart = 1_000L,
            candidateHomeArrivalTime = 300_000L, homeArrivalTime = null
        )
        val rest = processor.nextState(finished, LocationType.HOME, 362_000L, settings)
        assertEquals("REST", rest.currentState)
        assertEquals(300_000L, rest.homeArrivalTime)
    }

    @Test fun finishedWithExistingArrivalTimeIsNotOverwritten() {
        val finished = WorkStateEntity(
            currentState = "FINISHED", sessionStart = 1_000L,
            homeArrivalTime = 300_000L, homeArrivalConfirmedAt = 310_000L
        )
        val rest = processor.nextState(finished, LocationType.HOME, 362_000L, settings)
        assertEquals("REST", rest.currentState)
        assertEquals(300_000L, rest.homeArrivalTime)
        assertEquals(310_000L, rest.homeArrivalConfirmedAt)
    }

    @Test fun inaccurateLocationIsUnknownAndDoesNotStartTemporaryLeave() {
        val type = processor.classify(
            lat = 30.002,
            lng = 120.0005,
            accuracyMeters = 239.88745f,
            settings = UserSettingsEntity(
                companyLat = 30.0,
                companyLng = 120.0,
                companyRadiusMeters = 200
            )
        )
        assertEquals(LocationType.UNKNOWN, type)

        val working = WorkStateEntity(currentState = "WORKING", sessionStart = 1_000L)
        val unchanged = processor.nextState(working, type, 2_000L, settings)
        assertEquals("WORKING", unchanged.currentState)
    }

    @Test fun stationaryOutsideSamplesDoNotConfirmDepartureWithoutMovementEvidence() {
        val working = WorkStateEntity(currentState = "WORKING", sessionStart = 1_000L)
        val candidate = processor.nextState(working, LocationType.OTHER, 2_000L, settings)
        val afterTimeout = processor.nextState(candidate, LocationType.OTHER, 61 * 60_000L, settings, 260.0, false)
        assertEquals("TEMP_LEAVE", afterTimeout.currentState)
    }

    @Test fun homeEvidenceConfirmsDepartureAndPreservesFirstExitTime() {
        val working = WorkStateEntity(currentState = "WORKING", sessionStart = 1_000L)
        val candidate = processor.nextState(working, LocationType.OTHER, 2_000L, settings)
        val finished = processor.nextState(candidate, LocationType.HOME, 61 * 60_000L, settings, 2_000.0, true)
        assertEquals("FINISHED", finished.currentState)
        assertEquals(2_000L, finished.confirmedDepartureTime)
        assertEquals(61 * 60_000L, finished.homeArrivalTime)
    }

    @Test fun returningToCompanyCancelsCandidateDeparture() {
        val working = WorkStateEntity(currentState = "WORKING", sessionStart = 1_000L)
        val candidate = processor.nextState(working, LocationType.OTHER, 2_000L, settings)
        val returned = processor.nextState(candidate, LocationType.COMPANY, 3_000L, settings)
        assertEquals("WORKING", returned.currentState)
        assertEquals(null, returned.tempLeaveStart)
        assertEquals(null, returned.confirmedDepartureTime)
    }

    @Test fun newCommuteClearsPreviousSessionArrivalAndDeparture() {
        val stale = WorkStateEntity(currentState = "REST", homeArrivalTime = 100L,
            confirmedDepartureTime = 90L, tempLeaveStart = 80L)
        val next = processor.nextState(stale, LocationType.OTHER, 200L, settings)
        assertEquals(null, next.homeArrivalTime)
        assertEquals(null, next.confirmedDepartureTime)
        assertEquals(null, next.tempLeaveStart)
    }

    @Test fun firstReliableHomeTimeIsKeptUntilDepartureConfirmation() {
        val working = WorkStateEntity(currentState = "WORKING", sessionStart = 1_000L)
        val candidate = processor.nextState(working, LocationType.OTHER, 2_000L, settings)
        val firstHome = processor.nextState(candidate, LocationType.HOME, 30 * 60_000L, settings, 2_000.0, true)
        assertEquals(30 * 60_000L, firstHome.candidateHomeArrivalTime)
        val confirmed = processor.nextState(firstHome, LocationType.HOME, 61 * 60_000L, settings, 2_000.0, true)
        assertEquals(30 * 60_000L, confirmed.homeArrivalTime)
    }
}

