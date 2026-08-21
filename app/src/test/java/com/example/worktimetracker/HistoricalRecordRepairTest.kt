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

    @Test fun augustNineteenthKeepsHomeUnknownAndExplainsDepartureEvidence() {
        val record = WorkRecordEntity(workDate="2026-08-19", status="MANUAL", shift="NIGHT_SHIFT",
            startTime=100, endTime=null, homeArrivalTime=null, finalMinutes=660, isManual=true)
        val marked = HistoricalRecordRepair.markAugustNineteenthIncomplete(record)
        assertTrue(marked.needsReview)
        assertTrue(marked.note!!.contains("08:59"))
        assertTrue(marked.note!!.contains("09:14"))
        assertFalse(marked.note!!.contains("18:55到家"))
        assertTrue(marked.homeArrivalTime == null)
    }
}
