package com.example.worktimetracker

import com.example.worktimetracker.domain.journey.JourneyPhase
import com.example.worktimetracker.location.service.JourneyDifferenceType
import com.example.worktimetracker.location.service.JourneyShadowComparator
import com.example.worktimetracker.location.service.LegacyJourneyNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JourneyShadowComparisonTest {
    @Test fun mapsLegacyStableStatesExactly() {
        assertEquals(JourneyPhase.AT_HOME, LegacyJourneyNormalizer.normalize("REST", homeStable = true, moving = false)?.primary)
        assertEquals(JourneyPhase.AWAY, LegacyJourneyNormalizer.normalize("REST", homeStable = false, moving = false)?.primary)
        assertEquals(JourneyPhase.AT_WORK, LegacyJourneyNormalizer.normalize("WORKING", false, false)?.primary)
        assertEquals(JourneyPhase.AT_HOME, LegacyJourneyNormalizer.normalize("FINISHED", true, false)?.primary)
    }

    @Test fun tempLeaveSplitIsExpectedRatherThanCountedAsError() {
        val old = requireNotNull(LegacyJourneyNormalizer.normalize("TEMP_LEAVE", false, true))
        assertEquals(
            JourneyDifferenceType.EXPECTED_SPLIT,
            JourneyShadowComparator.comparePhase(old, JourneyPhase.COMMUTING_HOME)
        )
        assertEquals(
            JourneyDifferenceType.EXPECTED_SPLIT,
            JourneyShadowComparator.comparePhase(old, JourneyPhase.TEMP_LEAVE)
        )
    }

    @Test fun unexplainedStateMismatchIsReported() {
        val old = requireNotNull(LegacyJourneyNormalizer.normalize("WORKING", false, false))
        assertEquals(JourneyDifferenceType.STATE, JourneyShadowComparator.comparePhase(old, JourneyPhase.AT_HOME))
    }

    @Test fun unknownLegacyStateIsNotGuessed() {
        assertNull(LegacyJourneyNormalizer.normalize("FUTURE_STATE", false, false))
    }
}
