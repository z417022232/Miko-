package com.example.worktimetracker.location.service

import com.example.worktimetracker.domain.journey.JourneyPhase
import com.example.worktimetracker.domain.journey.SamplingTier

/** 影子对照的差异分类；EXPECTED_SPLIT不计入错误。 */
enum class JourneyDifferenceType { NONE, EXPECTED_SPLIT, STATE, TIMING, TIER, MISSING_OLD, MISSING_NEW }

data class LegacyNormalization(
    val primary: JourneyPhase,
    val expectedAlternatives: Set<JourneyPhase> = emptySet()
)

/** 旧 WorkState 字符串到新状态语义的唯一归一入口。未知值返回 null，不猜测。 */
object LegacyJourneyNormalizer {
    fun normalize(state: String, homeStable: Boolean, moving: Boolean): LegacyNormalization? = when (state) {
        "REST" -> LegacyNormalization(if (homeStable) JourneyPhase.AT_HOME else JourneyPhase.AWAY)
        "LEAVING_HOME" -> LegacyNormalization(
            JourneyPhase.LEAVING_HOME,
            if (moving) setOf(JourneyPhase.COMMUTING_TO_WORK) else emptySet()
        )
        "NEAR_COMPANY" -> LegacyNormalization(JourneyPhase.ARRIVING_WORK)
        "WORKING" -> LegacyNormalization(JourneyPhase.AT_WORK)
        "TEMP_LEAVE" -> LegacyNormalization(
            JourneyPhase.LEAVING_WORK,
            setOf(
                JourneyPhase.TEMP_LEAVE,
                JourneyPhase.OTHER_STOP,
                JourneyPhase.ARRIVING_WORK,
                JourneyPhase.COMMUTING_HOME,
                JourneyPhase.ARRIVING_HOME
            )
        )
        "FINISHED" -> LegacyNormalization(
            if (homeStable) JourneyPhase.AT_HOME else JourneyPhase.COMMUTING_HOME
        )
        else -> null
    }
}

object JourneyShadowComparator {
    fun comparePhase(old: LegacyNormalization, new: JourneyPhase): JourneyDifferenceType = when {
        new == old.primary -> JourneyDifferenceType.NONE
        new in old.expectedAlternatives -> JourneyDifferenceType.EXPECTED_SPLIT
        else -> JourneyDifferenceType.STATE
    }
}

/** 将旧机实际毫秒间隔归一成新机档位；旧机没有 CRITICAL 档，绝不凭空映射出来。 */
object LegacySamplingTierMapper {
    fun fromInterval(intervalMillis: Long): SamplingTier = when {
        intervalMillis <= LocationSamplingPolicy.FAST_INTERVAL_MILLIS -> SamplingTier.TRANSITION
        intervalMillis <= LocationSamplingPolicy.WORK_WINDOW_INTERVAL_MILLIS -> SamplingTier.WATCH
        intervalMillis <= LocationSamplingPolicy.DEFAULT_INTERVAL_MILLIS -> SamplingTier.NORMAL
        else -> SamplingTier.STABLE
    }
}
