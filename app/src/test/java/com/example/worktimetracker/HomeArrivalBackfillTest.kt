package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.location.service.HomeArrivalBackfill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「迟到到家证据」补写选行的护栏。
 *
 * 真机 09-19 / 09-20 两条记录永久缺 `homeArrivalTime`，根因之一正是选行：
 * 09-21 早晨出现两段会话，第二段 09:18 建的记录 `endTime` 更晚，
 * 只按 `MAX(endTime)` 会把到家时间写进后一条，真正的目标继续缺。
 */
class HomeArrivalBackfillTest {

    /** 时间用「分钟」为单位，便于对照真机时间线。 */
    private fun m(minutes: Long) = minutes * 60_000L

    private fun record(
        id: Long,
        date: String,
        start: Long,
        end: Long,
        status: String = "WORK"
    ) = WorkRecordEntity(
        id = id, workDate = date, status = status,
        startTime = start, endTime = end, homeArrivalTime = null
    )

    /** 09-20 夜班：20:47 到岗 → 次日 09:05 离岗；09-21 早晨折返后的第二段：09:18 到岗 → 09:26 离岗。 */
    private fun twoSessionsSameMorning() = listOf(
        record(197, "2026-09-20", m(20 * 60 + 47), m(33 * 60 + 5)),   // 09-20 20:47 → 09-21 09:05
        record(198, "2026-09-21", m(33 * 60 + 18), m(33 * 60 + 26))   // 09-21 09:18 → 09:21 09:26
    )

    @Test fun `同一早晨两段会话时必须补到离岗那条而不是后建的那条`() {
        // 到家 09:35，本轮会话起点是夜班的 20:47
        val picked = HomeArrivalBackfill.pick(twoSessionsSameMorning(), m(33 * 60 + 35), m(20 * 60 + 47))
        assertEquals(197L, picked?.id)
        assertEquals("2026-09-20", picked?.workDate)
    }

    @Test fun `没有会话起点时退回最近完结兜底`() {
        val picked = HomeArrivalBackfill.pick(twoSessionsSameMorning(), m(33 * 60 + 35), null)
        assertEquals(198L, picked?.id)
    }

    @Test fun `到家早于离岗的记录不在候选内`() {
        // 到家 09:00，两段会话的 endTime 都晚于它 → 一条都不该选
        assertNull(HomeArrivalBackfill.pick(twoSessionsSameMorning(), m(33 * 60), m(20 * 60 + 47)))
    }

    @Test fun `休息日记录不参与补写`() {
        val records = listOf(
            record(1, "2026-09-20", m(100), m(200), status = "REST"),
            record(2, "2026-09-19", m(90), m(150))
        )
        assertEquals(2L, HomeArrivalBackfill.pick(records, m(300), m(100))?.id)
    }

    @Test fun `候选里没有合法行时返回 null 而不是硬选一条`() {
        // 宁可让记录继续挂着 needsReview 等人看，也不能把到家时间写进顺序非法的行
        val records = listOf(record(1, "2026-09-20", m(300), m(400)))
        assertNull(HomeArrivalBackfill.pick(records, m(350), m(300)))
    }
}
