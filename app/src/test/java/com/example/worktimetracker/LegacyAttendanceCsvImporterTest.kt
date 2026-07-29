package com.example.worktimetracker

import com.example.worktimetracker.data.importer.LegacyAttendanceCsvImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class LegacyAttendanceCsvImporterTest {
    @Test
    fun `manual work and rest rows are preserved`() {
        val plan = LegacyAttendanceCsvImporter.createImportPlan(
            csv(
                "1,WORK,1772326800000,2026-03-01 09:00:00,0.0,0.0,手动补录-上班,true",
                "2,HOME,1772413200000,2026-03-02 09:00:00,0.0,0.0,手动补录-休息,true"
            ),
            defaultWorkMinutes = 660
        )

        assertEquals("WORK", plan.dailyRecords[0].status)
        assertEquals(660, plan.dailyRecords[0].finalMinutes)
        assertEquals("DAY_SHIFT", plan.dailyRecords[0].shift)
        assertEquals("REST", plan.dailyRecords[1].status)
        assertEquals(0, plan.dailyRecords[1].finalMinutes)
        assertNull(plan.dailyRecords[1].shift)
    }

    @Test
    fun `early morning night event belongs to previous date`() {
        val plan = LegacyAttendanceCsvImporter.createImportPlan(
            csv(
                "1,WORK,1776690000000,2026-04-20 21:00:00,31.0,121.0,夜班,false",
                "2,WORK,1776733200000,2026-04-21 09:00:00,31.0,121.0,夜班,false"
            ),
            defaultWorkMinutes = 660
        )

        val night = plan.dailyRecords.first { it.date == LocalDate.of(2026, 4, 20) }
        assertEquals("WORK", night.status)
        assertEquals("NIGHT_SHIFT", night.shift)
        assertEquals(2, night.sourceEventCount)
    }

    @Test
    fun `strong daytime evidence wins over late noisy night labels`() {
        val plan = LegacyAttendanceCsvImporter.createImportPlan(
            csv(
                "1,WORK,1,2026-06-02 08:36:00,31.0,121.0,白班,false",
                "2,WORK,2,2026-06-02 12:00:00,31.0,121.0,白班,false",
                "3,WORK,3,2026-06-02 18:30:00,31.0,121.0,夜班,false"
            ),
            defaultWorkMinutes = 660
        )

        assertEquals("DAY_SHIFT", plan.dailyRecords.single().shift)
    }

    private fun csv(vararg rows: String): String =
        (
            listOf("id,eventType,timeMillis,timeLocal,latitude,longitude,shiftName,corrected") +
                rows
            ).joinToString("\n")
}
