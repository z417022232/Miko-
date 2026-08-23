package com.example.worktimetracker.location.service

object DepartureConfirmationPolicy {
    enum class Action { CANCEL, WAIT, WAIT_FOR_EVIDENCE, CONFIRM }

    fun evaluate(
        state: String,
        candidateAt: Long?,
        firstHomeAt: Long?,
        movingAwayCount: Int,
        lastCompanyDistance: Double?,
        companyRadius: Int,
        confirmMinutes: Int,
        now: Long
    ): Action {
        if (state != "TEMP_LEAVE" || candidateAt == null) return Action.CANCEL
        if (now < candidateAt + confirmMinutes.coerceAtLeast(5) * 60_000L) return Action.WAIT
        val movingEvidence = movingAwayCount >= 2 ||
            lastCompanyDistance?.let { it >= companyRadius + 100.0 } == true
        return if (firstHomeAt != null || movingEvidence) Action.CONFIRM else Action.WAIT_FOR_EVIDENCE
    }
}
