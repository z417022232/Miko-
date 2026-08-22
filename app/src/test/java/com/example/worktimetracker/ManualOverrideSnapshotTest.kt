package com.example.worktimetracker

import com.example.worktimetracker.data.ManualOverrideSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualOverrideSnapshotTest {
    @Test fun parsesConfirmedRecordSnapshot() {
        val value = ManualOverrideSnapshot.parse("NIGHT_SHIFT:100:200:660")!!
        assertEquals("NIGHT_SHIFT", value.shift)
        assertEquals(100L, value.startTime)
        assertEquals(200L, value.endTime)
        assertEquals(660, value.finalMinutes)
    }
    @Test fun ignoresHoursOnlyOverride() {
        assertNull(ManualOverrideSnapshot.parse("660"))
    }
}
