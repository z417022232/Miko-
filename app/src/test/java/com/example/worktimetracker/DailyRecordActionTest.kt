package com.example.worktimetracker

import com.example.worktimetracker.ui.DailyRecordAction
import com.example.worktimetracker.ui.EditorMode
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyRecordActionTest {
    @Test
    fun reviewRowOpensConfirmationEditor() {
        assertEquals(EditorMode.CONFIRM_REVIEW, DailyRecordAction.forRecord(true))
    }

    @Test
    fun normalRowOpensEditEditor() {
        assertEquals(EditorMode.EDIT_CONFIRMED, DailyRecordAction.forRecord(false))
    }
}
