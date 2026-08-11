package com.example.worktimetracker

import com.example.worktimetracker.data.HistoricalRecordRepair
import com.example.worktimetracker.data.entity.WorkRecordEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalRecordRepairTest {
    @Test fun reviewAndManualRecordsAreNotAutomaticallyRebuilt() {
        val normal = WorkRecordEntity(workDate = "2026-07-01", status = "WORK", startTime = 1, endTime = 2)
        assertTrue(HistoricalRecordRepair.shouldRepair(normal))
        assertFalse(HistoricalRecordRepair.shouldRepair(normal.copy(needsReview = true)))
        assertFalse(HistoricalRecordRepair.shouldRepair(normal.copy(isManual = true)))
    }
}
