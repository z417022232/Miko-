package com.example.worktimetracker

import com.example.worktimetracker.ui.calendarDayLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarDayLabelTest {
    @Test fun nightShiftLabelContainsShiftAndHours() {
        assertEquals("夜 11h", calendarDayLabel("夜班", 11 * 60))
    }

    @Test fun dayShiftLabelKeepsHalfHour() {
        assertEquals("白 10.5h", calendarDayLabel("白班", 10 * 60 + 30))
    }
}
