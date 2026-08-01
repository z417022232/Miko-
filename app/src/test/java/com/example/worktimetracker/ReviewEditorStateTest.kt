package com.example.worktimetracker

import com.example.worktimetracker.ui.ReviewEditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReviewEditorStateTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `night departure earlier clock is moved to next day`() {
        val state = ReviewEditorState.from(LocalDate.of(2026, 8, 1), "NIGHT_SHIFT", 21 * 60, 9 * 60, zone)
        assertEquals(LocalDate.of(2026, 8, 2), state.endDate)
        assertNull(state.validationError)
    }

    @Test
    fun `day departure earlier clock reports invalid sequence`() {
        val state = ReviewEditorState.from(LocalDate.of(2026, 8, 1), "DAY_SHIFT", 21 * 60, 9 * 60, zone)
        assertNotNull(state.validationError)
    }
}
