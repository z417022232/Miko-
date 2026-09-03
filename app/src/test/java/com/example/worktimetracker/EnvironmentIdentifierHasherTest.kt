package com.example.worktimetracker

import com.example.worktimetracker.location.evidence.EnvironmentIdentifierHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EnvironmentIdentifierHasherTest {
    @Test fun hashIsStableAndDoesNotContainRawIdentifier() {
        val salt = ByteArray(32) { it.toByte() }
        val first = EnvironmentIdentifierHasher.hash(salt, listOf("wifi", "WorkGuest", "aa:bb:cc:dd:ee:ff"))
        val second = EnvironmentIdentifierHasher.hash(salt, listOf("wifi", "WorkGuest", "aa:bb:cc:dd:ee:ff"))
        assertEquals(first, second)
        assertFalse(first.contains("WorkGuest"))
        assertEquals(64, first.length)
    }

    @Test fun differentSaltProducesDifferentHash() {
        val saltA = ByteArray(32) { 1 }
        val saltB = ByteArray(32) { 2 }
        val a = EnvironmentIdentifierHasher.hash(saltA, listOf("wifi", "Home", "11:22:33:44:55:66"))
        val b = EnvironmentIdentifierHasher.hash(saltB, listOf("wifi", "Home", "11:22:33:44:55:66"))
        assertNotEquals(a, b)
    }

    @Test fun fieldSeparatorPreventsAmbiguity() {
        val salt = ByteArray(32) { 3 }
        val a = EnvironmentIdentifierHasher.hash(salt, listOf("ab", "c"))
        val b = EnvironmentIdentifierHasher.hash(salt, listOf("a", "bc"))
        assertNotEquals(a, b)
    }
}
