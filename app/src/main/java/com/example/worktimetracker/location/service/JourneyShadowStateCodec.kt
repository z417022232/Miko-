package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.JourneyShadowStateEntity
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.journey.JourneyCandidate
import com.example.worktimetracker.domain.journey.JourneyPhase
import com.example.worktimetracker.domain.journey.JourneySnapshot
import com.example.worktimetracker.domain.journey.RetryState

/** Room 行与纯 domain 快照之间的唯一转换点。未知枚举或不完整候选一律拒绝恢复。 */
object JourneyShadowStateCodec {
    const val MODEL_VERSION: Long = 1L

    data class Restored(val snapshot: JourneySnapshot, val retry: RetryState)

    fun encode(
        snapshot: JourneySnapshot,
        retry: RetryState,
        now: Long
    ): JourneyShadowStateEntity {
        val candidate = snapshot.candidate
        return JourneyShadowStateEntity(
            phase = snapshot.phase.name,
            candidatePhase = candidate?.targetPhase?.name,
            firstObservedAt = candidate?.firstObservedAt,
            lastSupportedAt = candidate?.lastSupportedAt,
            candidateLastUnsupportedAt = candidate?.lastUnsupportedAt,
            supportCount = candidate?.supportCount ?: 0,
            accumulatedStableMillis = candidate?.accumulatedStableMillis ?: 0L,
            candidateEvidenceSources = candidate?.let { JourneyCandidate.encodeSources(it.evidenceSources) },
            candidateStrongestDecision = candidate?.strongestDecision?.name,
            candidateConfidence = candidate?.confidence,
            lastConfirmedPhase = snapshot.lastConfirmedPhase?.name,
            lastTransitionAt = snapshot.lastTransitionAt,
            samplingAttempt = retry.attempt,
            samplingLastAttemptAt = retry.lastAttemptAt,
            samplingCriticalStartedAt = retry.currentCriticalStartedAt,
            samplingLastCriticalEndedAt = retry.lastCriticalEndedAt,
            modelVersion = MODEL_VERSION,
            updatedAt = now
        )
    }

    /**
     * @return 可恢复状态；null 表示版本、枚举或字段组合不可解释，调用方应使用初始快照。
     * 时间回拨不是解析失败：它保留最后确认态，但丢弃候选与重试记忆后返回 UNKNOWN。
     */
    fun decode(row: JourneyShadowStateEntity, now: Long): Restored? {
        if (row.modelVersion != MODEL_VERSION) return null

        val lastConfirmed = when {
            row.lastConfirmedPhase == null -> null
            else -> JourneyPhase.parseOrNull(row.lastConfirmedPhase) ?: return null
        }

        if (row.updatedAt > now) {
            return Restored(
                snapshot = JourneySnapshot(
                    phase = JourneyPhase.UNKNOWN,
                    candidate = null,
                    lastConfirmedPhase = lastConfirmed,
                    lastTransitionAt = now
                ),
                retry = RetryState()
            )
        }

        val phase = JourneyPhase.parseOrNull(row.phase) ?: return null
        val candidate = decodeCandidate(row) ?: if (row.candidatePhase == null && candidateFieldsAreEmpty(row)) {
            null
        } else {
            return null
        }
        if (row.samplingAttempt < 0) return null

        return Restored(
            snapshot = JourneySnapshot(
                phase = phase,
                candidate = candidate,
                lastConfirmedPhase = lastConfirmed,
                lastTransitionAt = row.lastTransitionAt
            ),
            retry = RetryState(
                attempt = row.samplingAttempt,
                lastAttemptAt = row.samplingLastAttemptAt,
                currentCriticalStartedAt = row.samplingCriticalStartedAt,
                lastCriticalEndedAt = row.samplingLastCriticalEndedAt
            )
        )
    }

    private fun decodeCandidate(row: JourneyShadowStateEntity): JourneyCandidate? {
        val phaseName = row.candidatePhase ?: return null
        val phase = JourneyPhase.parseOrNull(phaseName) ?: return null
        val first = row.firstObservedAt ?: return null
        val last = row.lastSupportedAt ?: return null
        val sources = JourneyCandidate.decodeSources(row.candidateEvidenceSources) ?: return null
        val decision = FusedDecision.entries.firstOrNull { it.name == row.candidateStrongestDecision } ?: return null
        val confidence = row.candidateConfidence ?: return null
        if (!confidence.isFinite() || confidence !in 0.0..1.0) return null
        if (row.supportCount < 0 || row.accumulatedStableMillis < 0L || last < first) return null

        return JourneyCandidate(
            targetPhase = phase,
            firstObservedAt = first,
            lastSupportedAt = last,
            supportCount = row.supportCount,
            accumulatedStableMillis = row.accumulatedStableMillis,
            evidenceSources = sources,
            strongestDecision = decision,
            confidence = confidence,
            lastUnsupportedAt = row.candidateLastUnsupportedAt
        )
    }

    private fun candidateFieldsAreEmpty(row: JourneyShadowStateEntity): Boolean =
        row.firstObservedAt == null &&
            row.lastSupportedAt == null &&
            row.candidateLastUnsupportedAt == null &&
            row.supportCount == 0 &&
            row.accumulatedStableMillis == 0L &&
            row.candidateEvidenceSources == null &&
            row.candidateStrongestDecision == null &&
            row.candidateConfidence == null
}
