package com.example.worktimetracker

import com.example.worktimetracker.domain.journey.JourneyPhase
import com.example.worktimetracker.domain.journey.SamplingTier
import com.example.worktimetracker.location.service.JourneyDifferenceType
import com.example.worktimetracker.location.service.JourneyShadowComparator
import com.example.worktimetracker.location.service.LegacyJourneyNormalizer
import com.example.worktimetracker.location.service.LegacySamplingTierMapper
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

    @Test fun mapsLegacyIntervalsWithoutInventingCritical() {
        assertEquals(SamplingTier.TRANSITION, LegacySamplingTierMapper.fromInterval(60_000L))
        assertEquals(SamplingTier.WATCH, LegacySamplingTierMapper.fromInterval(5 * 60_000L))
        assertEquals(SamplingTier.NORMAL, LegacySamplingTierMapper.fromInterval(10 * 60_000L))
        assertEquals(SamplingTier.STABLE, LegacySamplingTierMapper.fromInterval(30 * 60_000L))
    }
}
