package com.example.worktimetracker

import com.example.worktimetracker.location.service.SafeLocationProcessor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SafeLocationProcessorTest {
    @Test fun oneFailureDoesNotPreventNextItem() = runTest {
        val processor = SafeLocationProcessor<Int>()
        val handled = mutableListOf<Int>()
        assertEquals(null, processor.process(1) { handled += it })
        val error = processor.process(2) { throw IllegalArgumentException("bad state") }
        assertNotNull(error)
        assertEquals(null, processor.process(3) { handled += it })
        assertEquals(listOf(1, 3), handled)
    }
}
