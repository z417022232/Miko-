package com.example.worktimetracker

import com.example.worktimetracker.data.importer.LegacyAttendanceCsvImporter
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyAttendanceCsvRealFileShapeTest {
    @Test
    fun `quoted csv values are accepted`() {
        val csv = """
            "id","eventType","timeMillis","timeLocal","latitude","longitude","shiftName","corrected"
            "1","WORK","1772326800000","2026-03-01 09:00:00","0.0","0.0","手动补录-上班","true"
        """.trimIndent()

        val plan = LegacyAttendanceCsvImporter.createImportPlan(csv, 660)

        assertEquals(1, plan.events.size)
        assertEquals(1, plan.dailyRecords.size)
        assertEquals(660, plan.dailyRecords.single().finalMinutes)
    }
}
