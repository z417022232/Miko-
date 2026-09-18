package com.example.worktimetracker

import com.example.worktimetracker.location.recovery.SystemLocationAlertPolicy
import com.example.worktimetracker.location.recovery.SystemLocationAlertState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLocationAlertPolicyTest {
    private val now = 10_000L

    @Test fun sameDisabledEpisodeNotifiesOnlyOnceAcrossCallers() {
        val first = SystemLocationAlertPolicy.onObserved(
            SystemLocationAlertState(), enabled = false, now = now
        )
        assertTrue(first.notifyUser)
        val worker = SystemLocationAlertPolicy.onObserved(first.next, enabled = false, now = now + 60_000L)
        assertFalse(worker.notifyUser)
        assertTrue(worker.next.disabledEpisodeStartedAt == now)
    }

    @Test fun recoveryThenSecondDisableCreatesNewNotificationEpisode() {
        val first = SystemLocationAlertPolicy.onObserved(SystemLocationAlertState(), false, now)
        val recovered = SystemLocationAlertPolicy.onObserved(first.next, true, now + 1_000L)
        assertFalse(recovered.notifyUser)
        val second = SystemLocationAlertPolicy.onObserved(recovered.next, false, now + 2_000L)
        assertTrue(second.notifyUser)
        assertTrue(second.next.disabledEpisodeStartedAt == now + 2_000L)
    }
}
