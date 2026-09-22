package com.example.worktimetracker

import com.example.worktimetracker.data.entity.ManualField
import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.engine.WorkSessionEngine
import com.example.worktimetracker.domain.model.FinalMinutesSource
import com.example.worktimetracker.location.service.ConfirmedSession
import com.example.worktimetracker.location.service.MergeMode
import com.example.worktimetracker.location.service.ProtectedRecordMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `work_records.finalMinutesSource` **真的会落库**的护栏。
 *
 * 真机体检：整列全为 NULL，全仓零写入点 —— 于是「只有 ACTUAL / MANUAL 可进训练集」
 * 这条契约在数据上无法执行（训练集退化成「非 null 才收」，实际一条都收不到）。
 *
 * 三处都得通，缺一处就还是死字段：
 *  WorkSessionEngine（判定）→ ConfirmedSession（组装）→ ProtectedRecordMerge（落到已有行）。
 */
class FinalMinutesSourcePersistenceTest {

    private val engine = WorkSessionEngine()

    @Test fun `固定工时短路必须标 DEFAULT 而不是 ACTUAL`() {
        // hasDefaultHours 填出来的 660 分钟不是真实出勤时长，绝不能进训练集
        assertEquals(
            FinalMinutesSource.DEFAULT,
            engine.sourceOf(ruleTrace = listOf("R_DEFAULT_HOURS"), observedStart = true, observedEnd = true)
        )
    }

    @Test fun `两端都观测到才算 ACTUAL`() {
        assertEquals(
            FinalMinutesSource.ACTUAL,
            engine.sourceOf(ruleTrace = listOf("R1", "R2", "R8"), observedStart = true, observedEnd = true)
        )
        assertEquals(
            FinalMinutesSource.INFERRED,
            engine.sourceOf(ruleTrace = emptyList(), observedStart = false, observedEnd = true)
        )
        assertEquals(
            FinalMinutesSource.INFERRED,
            engine.sourceOf(ruleTrace = emptyList(), observedStart = true, observedEnd = false)
        )
    }

    @Test fun `组装时不知道来源就保留原值不回填猜测`() {
        val existing = WorkRecordEntity(workDate = "2026-09-20", status = "WORK", finalMinutesSource = "ACTUAL")
        val merged = ConfirmedSession.merge(
            existing = existing, shift = "NIGHT_SHIFT",
            companyArrival = 1_000L, companyDeparture = 2_000L,
            homeDeparture = null, homeArrival = null,
            actualMinutes = 660, calculatedMinutes = 660, needsReview = false
        )
        assertEquals("ACTUAL", merged.finalMinutesSource)

        val blank = WorkRecordEntity(workDate = "2026-09-21", status = "WORK")
        assertNull(
            ConfirmedSession.merge(
                existing = blank, shift = "NIGHT_SHIFT",
                companyArrival = 1_000L, companyDeparture = 2_000L,
                homeDeparture = null, homeArrival = null,
                actualMinutes = 660, calculatedMinutes = 660, needsReview = false
            ).finalMinutesSource
        )
    }

    @Test fun `手工记录的来源不由算法改写`() {
        val manual = WorkRecordEntity(
            workDate = "2026-09-20", status = "MANUAL", isManual = true, finalMinutes = 600
        )
        val merged = ConfirmedSession.merge(
            existing = manual, shift = "NIGHT_SHIFT",
            companyArrival = 1_000L, companyDeparture = 2_000L,
            homeDeparture = null, homeArrival = null,
            actualMinutes = 660, calculatedMinutes = 660, needsReview = false,
            finalMinutesSource = FinalMinutesSource.ACTUAL
        )
        assertNull("手工记录的来源不许被自动结果覆盖", merged.finalMinutesSource)
    }

    @Test fun `已有记录走 ProtectedRecordMerge 时来源必须跟着一起写`() {
        // 少了这一行，完结路径（existing != null）永远写不进来源
        val existing = WorkRecordEntity(workDate = "2026-09-20", status = "WORK", startTime = 100, endTime = 900)
        val automatic = WorkRecordEntity(
            workDate = "2026-09-20", status = "WORK", startTime = 100, endTime = 900,
            finalMinutes = 660, finalMinutesSource = FinalMinutesSource.ACTUAL.name
        )
        assertEquals(
            "ACTUAL",
            ProtectedRecordMerge.merge(existing, automatic, MergeMode.FINALIZE_SESSION).finalMinutesSource
        )
    }

    @Test fun `人工锁了工时就不许改来源`() {
        val existing = WorkRecordEntity(
            workDate = "2026-09-20", status = "WORK", startTime = 100, endTime = 900,
            finalMinutes = 600, finalMinutesSource = FinalMinutesSource.MANUAL.name,
            manualFieldsMask = ManualField.FINAL_MINUTES.bit
        )
        val automatic = WorkRecordEntity(
            workDate = "2026-09-20", status = "WORK", startTime = 100, endTime = 900,
            finalMinutes = 660, finalMinutesSource = FinalMinutesSource.ACTUAL.name
        )
        assertEquals(
            "MANUAL",
            ProtectedRecordMerge.merge(existing, automatic, MergeMode.FINALIZE_SESSION).finalMinutesSource
        )
    }
}
