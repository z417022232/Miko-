package com.example.worktimetracker

import com.example.worktimetracker.location.recovery.SystemLocationLogPolicy
import com.example.worktimetracker.location.recovery.SystemLocationTransition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLocationLogPolicyTest {
    @Test fun disabledEpisodeIsLoggedOnlyOnTransition() {
        assertTrue(SystemLocationLogPolicy.shouldLogDisabled(SystemLocationTransition.DISABLED))
        assertFalse(SystemLocationLogPolicy.shouldLogDisabled(SystemLocationTransition.NONE))
        assertFalse(SystemLocationLogPolicy.shouldLogDisabled(SystemLocationTransition.RECOVERED))
    }
}
