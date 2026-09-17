package com.example.worktimetracker

import com.example.worktimetracker.data.entity.LocationLogEntity
import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.journey.JourneyPhase
import com.example.worktimetracker.location.service.JourneyHistoryPrelearner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyHistoryPrelearnerTest {
    @Test fun chronologicalReplayUsesExistingLocationsAndSessionFacts() {
        val t = 1_800_000_000_000L
        val logs = listOf(
            log(t, "HOME", "gps"),
            log(t + 60_000, "OTHER", "gps"),
            log(t + 120_000, "COMPANY", "gps")
        )
        val records = listOf(WorkRecordEntity(workDate = "2027-01-15", status = "WORK", startTime = t + 120_000, endTime = null))

        val result = requireNotNull(JourneyHistoryPrelearner.replay(logs, records, UserSettingsEntity()))

        assertEquals(JourneyPhase.AT_WORK, result.snapshot.phase)
        assertEquals(JourneyPhase.AT_WORK, result.snapshot.lastConfirmedPhase)
        assertTrue(result.processedCount == 3)
    }

    @Test fun emptyHistoryProducesNoSeedInsteadOfInventingState() {
        assertEquals(null, JourneyHistoryPrelearner.replay(emptyList(), emptyList(), UserSettingsEntity()))
    }

    private fun log(time: Long, type: String, provider: String) = LocationLogEntity(
        time = time,
        latitude = 0.0,
        longitude = 0.0,
        accuracyMeters = 20f,
        locationType = type,
        provider = provider
    )
}
