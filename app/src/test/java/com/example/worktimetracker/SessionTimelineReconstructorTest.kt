package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.SessionTimelineReconstructor
import com.example.worktimetracker.domain.model.LocationType
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTimelineReconstructorTest {
    @Test fun ignoresDriftThatReturnsToCompanyAndUsesTripHome() {
        val hour = 60 * 60_000L
        val start = 1_000L
        val points = listOf(
            SessionTimelineReconstructor.Point(start, LocationType.COMPANY),
            SessionTimelineReconstructor.Point(start + 8 * hour, LocationType.OTHER),
            SessionTimelineReconstructor.Point(start + 9 * hour, LocationType.COMPANY),
            SessionTimelineReconstructor.Point(start + 12 * hour, LocationType.OTHER),
            SessionTimelineReconstructor.Point(start + 12 * hour + 31 * 60_000L, LocationType.HOME)
        )
        val result = SessionTimelineReconstructor().reconstruct(start, points, 18 * 60)
        assertEquals(start + 12 * hour, result?.companyDeparture)
        assertEquals(start + 12 * hour + 31 * 60_000L, result?.homeArrival)
    }

    @Test fun returnsNullWhenThereIsNoHomeOrSustainedAwayEvidence() {
        val start = 1_000L
        val points = listOf(
            SessionTimelineReconstructor.Point(start, LocationType.COMPANY),
            SessionTimelineReconstructor.Point(start + 60_000L, LocationType.OTHER),
            SessionTimelineReconstructor.Point(start + 2 * 60_000L, LocationType.COMPANY)
        )
        assertEquals(null, SessionTimelineReconstructor().reconstruct(start, points, 18 * 60))
    }
}
