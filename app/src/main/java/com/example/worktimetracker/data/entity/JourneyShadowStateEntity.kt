package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 新行程状态机的单行影子快照（DB v17）。
 *
 * 影子期绝不复用正式 [WorkStateEntity]：新机只在这里保存自己的全部记忆，既不改变正式工时，
 * 也不会因为进程重启丢失候选起点、空窗标记或 CRITICAL 冷却。
 */
@Entity(tableName = "journey_shadow_state")
data class JourneyShadowStateEntity(
    @PrimaryKey val id: Int = 1,
    val phase: String,
    val candidatePhase: String?,
    val firstObservedAt: Long?,
    val lastSupportedAt: Long?,
    val candidateLastUnsupportedAt: Long?,
    val supportCount: Int,
    val accumulatedStableMillis: Long,
    val candidateEvidenceSources: String?,
    val candidateStrongestDecision: String?,
    val candidateConfidence: Double?,
    val lastConfirmedPhase: String?,
    val lastTransitionAt: Long,
    val samplingAttempt: Int,
    val samplingLastAttemptAt: Long?,
    val samplingCriticalStartedAt: Long?,
    val samplingLastCriticalEndedAt: Long?,
    val modelVersion: Long,
    val updatedAt: Long
)
