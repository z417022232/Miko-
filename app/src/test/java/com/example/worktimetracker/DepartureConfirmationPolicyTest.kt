package com.example.worktimetracker

import com.example.worktimetracker.location.service.DepartureConfirmationPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class DepartureConfirmationPolicyTest {
    @Test fun changedToFiveMinutesConfirmsExistingCandidateImmediately() {
        val action = DepartureConfirmationPolicy.evaluate(
            state = "TEMP_LEAVE", candidateAt = 0L, firstHomeAt = 60_000L,
            movingAwayCount = 2, lastCompanyDistance = 2_000.0,
            companyRadius = 250, confirmMinutes = 5, now = 10 * 60_000L
        )
        assertEquals(DepartureConfirmationPolicy.Action.CONFIRM, action)
    }

    @Test fun waitsUntilConfiguredDeadline() {
        assertEquals(DepartureConfirmationPolicy.Action.WAIT,
            DepartureConfirmationPolicy.evaluate("TEMP_LEAVE", 0L, 60_000L, 2, 2_000.0, 250, 5, 4 * 60_000L))
    }

    @Test fun returnToCompanyCancelsTimer() {
        assertEquals(DepartureConfirmationPolicy.Action.CANCEL,
            DepartureConfirmationPolicy.evaluate("WORKING", 0L, null, 0, 20.0, 250, 5, 10 * 60_000L))
    }

    @Test fun timeoutWithoutMovementOrHomeEvidenceKeepsWaitingForEvidence() {
        assertEquals(DepartureConfirmationPolicy.Action.WAIT_FOR_EVIDENCE,
            DepartureConfirmationPolicy.evaluate("TEMP_LEAVE", 0L, null, 0, 260.0, 250, 5, 10 * 60_000L))
    }
}
