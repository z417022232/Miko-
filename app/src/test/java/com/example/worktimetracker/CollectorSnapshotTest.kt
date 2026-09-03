package com.example.worktimetracker

import com.example.worktimetracker.domain.evidence.EvidenceSource
import com.example.worktimetracker.location.evidence.CollectorFeature
import com.example.worktimetracker.location.evidence.CollectorResult
import com.example.worktimetracker.location.evidence.CollectorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CollectorSnapshotTest {
    @Test fun mergesDuplicateIdentifiersAndKeepsStrongestSignal() {
        val merged = CollectorSnapshot.merge(listOf(
            CollectorResult(listOf(
                CollectorFeature(EvidenceSource.WIFI, "hash-a", -80),
                CollectorFeature(EvidenceSource.WIFI, "hash-a", -55),
                CollectorFeature(EvidenceSource.WIFI, "hash-b", -70)
            ), 1_000L)
        ), limit = 20)
        assertEquals(2, merged.size)
        assertEquals(-55, merged.first { it.identifierHash == "hash-a" }.signal)
    }

    @Test fun snapshotNeverContainsRawName() {
        val feature = CollectorFeature(EvidenceSource.BLUETOOTH, "f".repeat(64), -60)
        assertEquals(64, feature.identifierHash.length)
        assertFalse(feature.identifierHash.contains("Headset"))
    }

    @Test fun perSourceLimitIsEnforced() {
        val features = (1..30).map { CollectorFeature(EvidenceSource.WIFI, "hash-$it", -100 + it) }
        val merged = CollectorSnapshot.merge(listOf(CollectorResult(features, 1_000L)), limit = 20)
        assertEquals(20, merged.size)
        // 保留信号最强（值最大）的 20 个：-89 .. -70
        assertEquals(-89, merged.minOf { it.signal })
        assertEquals(-70, merged.maxOf { it.signal })
    }

    @Test fun failedResultContributesNoFeatures() {
        val merged = CollectorSnapshot.merge(listOf(
            CollectorResult.failed(com.example.worktimetracker.location.evidence.CollectorFailure.PERMISSION, 1_000L),
            CollectorResult(listOf(CollectorFeature(EvidenceSource.CELL, "cell-a", -90)), 1_000L)
        ))
        assertEquals(1, merged.size)
        assertEquals(EvidenceSource.CELL, merged.single().source)
    }
}
