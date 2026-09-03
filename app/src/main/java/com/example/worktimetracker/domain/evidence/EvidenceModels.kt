package com.example.worktimetracker.domain.evidence

enum class EvidenceSource { GNSS, CELL, WIFI, BLUETOOTH, MOTION, SHIFT_WINDOW }

enum class ResolvedPlace { HOME, COMPANY, OTHER, MOVING, UNKNOWN }

data class EvidenceObservation(
    val eventTime: Long,
    val receivedAt: Long,
    val source: EvidenceSource,
    val quality: Double,
    val placeHint: ResolvedPlace,
    val identifierHash: String?,
    val signal: Int?
)

data class FusedEvidence(
    val place: ResolvedPlace,
    val confidence: Double,
    val firstReliableAt: Long?,
    val sources: Set<EvidenceSource>
)
