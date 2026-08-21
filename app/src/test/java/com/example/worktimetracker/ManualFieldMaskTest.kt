package com.example.worktimetracker

import com.example.worktimetracker.data.entity.ManualField
import com.example.worktimetracker.data.entity.ManualFieldMask
import com.example.worktimetracker.data.entity.WorkRecordEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualFieldMaskTest {
    @Test fun onlySelectedFieldsAreProtected() {
        val mask = ManualFieldMask.add(0, ManualField.FINAL_MINUTES)
        assertTrue(ManualFieldMask.contains(mask, ManualField.FINAL_MINUTES))
        assertFalse(ManualFieldMask.contains(mask, ManualField.HOME_ARRIVAL))
    }

    @Test fun legacyManualMaskProtectsPresentFieldsButNotMissingFields() {
        val record = WorkRecordEntity(
            workDate = "2026-08-19", status = "MANUAL", shift = "NIGHT_SHIFT",
            startTime = 100L, endTime = null, finalMinutes = 660, isManual = true
        )
        val mask = ManualFieldMask.fromLegacy(record)
        assertTrue(ManualFieldMask.contains(mask, ManualField.SHIFT))
        assertTrue(ManualFieldMask.contains(mask, ManualField.COMPANY_ARRIVAL))
        assertTrue(ManualFieldMask.contains(mask, ManualField.FINAL_MINUTES))
        assertFalse(ManualFieldMask.contains(mask, ManualField.COMPANY_DEPARTURE))
        assertFalse(ManualFieldMask.contains(mask, ManualField.HOME_ARRIVAL))
    }
}
