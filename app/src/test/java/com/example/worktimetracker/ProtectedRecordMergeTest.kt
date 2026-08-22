package com.example.worktimetracker

import com.example.worktimetracker.data.entity.ManualField
import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.location.service.ProtectedRecordMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedRecordMergeTest {
    @Test fun fillsUnlockedNullsWithoutChangingManualHours() {
        val existing = record(end=null, home=null, minutes=660, mask=ManualField.SHIFT.bit or ManualField.COMPANY_ARRIVAL.bit or ManualField.FINAL_MINUTES.bit)
        val merged = ProtectedRecordMerge.merge(existing, record(end=900, home=1000, minutes=720))
        assertEquals(660, merged.finalMinutes)
        assertEquals(900L, merged.endTime)
        assertEquals(1000L, merged.homeArrivalTime)
        assertTrue(merged.needsReview)
    }

    @Test fun neverChangesProtectedDeparture() {
        val existing = record(end=900, home=null, minutes=660, mask=ManualField.COMPANY_DEPARTURE.bit)
        assertEquals(900L, ProtectedRecordMerge.merge(existing, record(end=1000, home=1100, minutes=720)).endTime)
    }

    private fun record(end: Long?, home: Long?, minutes: Int, mask: Int = 0) = WorkRecordEntity(
        workDate="2026-08-19", status="MANUAL", shift="NIGHT_SHIFT", startTime=100,
        endTime=end, homeArrivalTime=home, finalMinutes=minutes, isManual=mask!=0,
        manualFieldsMask=mask
    )
}
