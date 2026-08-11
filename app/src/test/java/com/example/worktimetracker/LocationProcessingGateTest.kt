package com.example.worktimetracker

import com.example.worktimetracker.location.service.LocationProcessingGate
import com.example.worktimetracker.location.service.RevisionCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationProcessingGateTest {
    @Test
    fun `newer pending fix replaces older unprocessed fix from same provider`() {
        val gate = LocationProcessingGate<String>()
        gate.offer("gps", 100, "old")
        gate.offer("gps", 200, "new")
        val pending = gate.takePending()!!
        assertEquals(200L, pending.time)
        assertEquals("new", pending.value)
        assertNull(gate.takePending())
    }

    @Test
    fun `different providers remain available in timestamp order`() {
        val gate = LocationProcessingGate<String>()
        gate.offer("gps", 200, "gps")
        gate.offer("network", 100, "network")
        assertEquals("network", gate.takePending()!!.value)
        assertEquals("gps", gate.takePending()!!.value)
    }

    @Test
    fun `profile cache invalidates only when revision changes`() {
        val cache = RevisionCache<String>()
        cache.put(7, "profile")
        assertEquals("profile", cache.get(7))
        assertNull(cache.get(8))
    }
}
