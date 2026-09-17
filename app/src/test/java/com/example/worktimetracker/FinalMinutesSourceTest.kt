package com.example.worktimetracker

import com.example.worktimetracker.domain.learning.LocationEvidence
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.domain.model.FinalMinutesSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学习输入的两道准入闸门（方案 §十一 阶段1 + 原则 2）。
 *
 * 这两组测试守的是同一条原则：**「事实」与「推算」在库里的区分必须是结构性的**。
 * 一旦把固定工时（R9 填的 660）当成真实出勤时长喂进学习，模型会立刻学歪，
 * 而歪掉的模型不会报错，只会悄悄把结论带偏。
 */
class FinalMinutesSourceTest {

    @Test fun parseAcceptsEveryDocumentedName() {
        assertEquals(FinalMinutesSource.ACTUAL, FinalMinutesSource.parse("ACTUAL"))
        assertEquals(FinalMinutesSource.DEFAULT, FinalMinutesSource.parse("DEFAULT"))
        assertEquals(FinalMinutesSource.MANUAL, FinalMinutesSource.parse("MANUAL"))
        assertEquals(FinalMinutesSource.INFERRED, FinalMinutesSource.parse("INFERRED"))
    }

    @Test fun parseIsCaseInsensitiveBecauseLegacyRowsMayVary() {
        assertEquals(FinalMinutesSource.ACTUAL, FinalMinutesSource.parse("actual"))
        assertEquals(FinalMinutesSource.DEFAULT, FinalMinutesSource.parse("Default"))
    }

    @Test fun unknownOrMissingSourceParsesToNullInsteadOfThrowing() {
        assertNull(FinalMinutesSource.parse(null))
        assertNull(FinalMinutesSource.parse(""))
        assertNull(FinalMinutesSource.parse("SOMETHING_ELSE"))
    }

    @Test fun onlyRealAttendanceAndManualCorrectionsAreLearnable() {
        assertTrue("实算出来的时长是唯一权威的训练样本", FinalMinutesSource.isLearnable(FinalMinutesSource.ACTUAL))
        assertTrue("用户手工改过的值等同事实", FinalMinutesSource.isLearnable(FinalMinutesSource.MANUAL))
        assertFalse("R9 短路的固定工时不是真实出勤时长", FinalMinutesSource.isLearnable(FinalMinutesSource.DEFAULT))
        assertFalse("推算补全的值待确认，不进训练集", FinalMinutesSource.isLearnable(FinalMinutesSource.INFERRED))
        assertFalse("老记录来源未知，不许猜", FinalMinutesSource.isLearnable(null))
    }

    // ---------------------------------------------------------------- 定位证据

    @Test fun cleanCoreObservationIsLearnable() {
        val evidence = evidence()
        assertTrue(evidence.learnable)
    }

    @Test fun inferredObservationIsRejected() {
        assertFalse(evidence(inferred = true).learnable)
    }

    @Test fun manuallyReplayedObservationIsRejected() {
        assertFalse(evidence(manualReplay = true).learnable)
    }

    @Test fun observationOutsideTheCoreAreaIsRejected() {
        assertFalse(evidence(inCore = false).learnable)
    }

    @Test fun observationWithoutAStableAnchorIsRejected() {
        assertFalse(evidence(stableSince = null).learnable)
    }

    @Test fun coarseObservationIsRejected() {
        assertFalse(evidence(accuracyMeters = 0f).learnable)
        assertFalse(evidence(accuracyMeters = 80f).learnable)
    }

    @Test fun onlyHomeAndCompanyFeedTheAnchorModels() {
        // OTHER / MOVING / UNKNOWN 没有需要学习的锚点，落进来只会污染
        assertTrue(evidence(place = ResolvedPlace.HOME).learnable)
        assertTrue(evidence(place = ResolvedPlace.COMPANY).learnable)
        assertFalse(evidence(place = ResolvedPlace.OTHER).learnable)
        assertFalse(evidence(place = ResolvedPlace.MOVING).learnable)
        assertFalse(evidence(place = ResolvedPlace.UNKNOWN).learnable)
    }

    private fun evidence(
        place: ResolvedPlace = ResolvedPlace.COMPANY,
        accuracyMeters: Float = 12f,
        inCore: Boolean = true,
        stableSince: Long? = 1_000L,
        inferred: Boolean = false,
        manualReplay: Boolean = false
    ) = LocationEvidence(
        eventTime = 2_000L,
        latitude = 31.0,
        longitude = 121.0,
        accuracyMeters = accuracyMeters,
        place = place,
        inCore = inCore,
        stableSince = stableSince,
        ambientSourceCount = 2,
        inferred = inferred,
        manualReplay = manualReplay
    )
}
