package com.example.worktimetracker

import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.domain.evidence.EvidenceFusionEngine
import com.example.worktimetracker.location.service.EvidenceContinuityPolicy
import com.example.worktimetracker.location.service.JourneyRuntimeConfigFactory
import com.example.worktimetracker.location.service.LocationSamplingPolicy
import com.example.worktimetracker.location.service.TrajectoryAnchorEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyRuntimeConfigFactoryTest {
    @Test fun everyRuntimeThresholdComesFromAnExistingProductionContract() {
        val settings = UserSettingsEntity(leaveCompanyConfirmMinutes = 73)
        val config = JourneyRuntimeConfigFactory.create(settings)

        assertEquals(EvidenceContinuityPolicy.DEFAULT_MAX_GAP_MILLIS / 1_000L, config.staleAfterSeconds)
        assertEquals(0L, config.strongArrivalRequiredMillis)
        assertEquals(LocationSamplingPolicy.FAST_INTERVAL_MILLIS, config.ambientArrivalRequiredMillis)
        assertEquals(0L, config.strongDepartureRequiredMillis)
        assertEquals(LocationSamplingPolicy.FAST_INTERVAL_MILLIS, config.ambientDepartureRequiredMillis)
        assertEquals(TrajectoryAnchorEngine.CANDIDATE_EXPIRE_MILLIS, config.candidateExpiryMillis)
        assertEquals(73 * 60_000L, config.tempLeaveMaxMillis)
        assertEquals(EvidenceFusionEngine.GNSS_MAX_AGE_MILLIS / 1_000L, config.motionExpirySeconds)
    }

    @Test fun invalidLeaveConfirmationIsClampedBeforeMultiplication() {
        assertEquals(0L, JourneyRuntimeConfigFactory.create(UserSettingsEntity(leaveCompanyConfirmMinutes = -1)).tempLeaveMaxMillis)
        assertEquals(
            Int.MAX_VALUE.toLong() * 60_000L,
            JourneyRuntimeConfigFactory.create(UserSettingsEntity(leaveCompanyConfirmMinutes = Int.MAX_VALUE)).tempLeaveMaxMillis
        )
    }
}
