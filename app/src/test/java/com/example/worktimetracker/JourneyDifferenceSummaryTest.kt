package com.example.worktimetracker

import com.example.worktimetracker.ui.JourneyDifferenceSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyDifferenceSummaryTest {
    @Test fun classifiesStructuredJourneyLogs() {
        val result = JourneyDifferenceSummary.fromContents(listOf(
            "x | differenceType=NONE | y",
            "x | differenceType=EXPECTED_SPLIT | y",
            "x | differenceType=STATE | y",
            "unrelated"
        ))
        assertEquals(3, result.total)
        assertEquals(1, result.matched)
        assertEquals(1, result.expectedSplit)
        assertEquals(1, result.needsReview)
    }
}
