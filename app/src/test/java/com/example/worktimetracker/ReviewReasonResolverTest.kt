package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.engine.ReviewReasonResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A7: 复核原因推导。
 *
 * 其中两条 **真实设备记录** 作为回归 fixture（2026-07-28 / 07-29）——它们正是用户设备上
 * 仅有的 2 条待确认记录，且 reviewReason 为 NULL，必须能推出可读原因。
 */
class ReviewReasonResolverTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun ms(d: Int, h: Int, m: Int) =
        LocalDateTime.of(2026, 7, d, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun record(
        start: Long? = ms(28, 21, 0),
        end: Long? = ms(29, 9, 0),
        shift: String? = "NIGHT_SHIFT",
        needsReview: Boolean = true,
        reviewReason: String? = null,
        homeDeparture: Long? = null,
        homeArrival: Long? = null
    ) = WorkRecordEntity(
        id = 1,
        workDate = "2026-07-28",
        status = "WORK",
        shift = shift,
        startTime = start,
        endTime = end,
        homeDepartureTime = homeDeparture,
        homeArrivalTime = homeArrival,
        needsReview = needsReview,
        reviewReason = reviewReason
    )

    // ---------- 真实设备记录（回归 fixture） ----------

    @Test fun realRecord_20260728_nightShiftOnlyTwoMinutes() {
        // 设备 id=145：09:22 → 09:25（3 分钟），却记为夜班
        val reason = ReviewReasonResolver.resolve(
            record(start = ms(28, 9, 22), end = ms(28, 9, 25), shift = "NIGHT_SHIFT"),
            zone
        )
        assertNotNull("必须能推导出原因，不能返回 null（否则横幅只有通用兜底文案）", reason)
        assertTrue("应指出在岗时长异常：$reason", reason!!.contains("在岗时长仅 3min"))
        assertTrue("应指出夜班与时刻不符：$reason", reason.contains("夜班但上班时刻是 09:22"))
    }

    @Test fun realRecord_20260729_homeArrivalFourDaysLate() {
        // 设备 id=146：下班 7/29 22:35，到家 8/2 09:42（晚 4 天）
        val homeArrival = LocalDateTime.of(2026, 8, 2, 9, 42).atZone(zone).toInstant().toEpochMilli()
        val reason = ReviewReasonResolver.resolve(
            record(
                start = ms(29, 7, 35),
                end = ms(29, 22, 35),
                shift = "DAY_SHIFT",
                homeArrival = homeArrival
            ),
            zone
        )
        assertNotNull(reason)
        assertTrue("应指出到家时间异常：$reason", reason!!.contains("到家时间距下班约"))
    }

    // ---------- 优先级 ----------

    @Test fun storedRuleReasonWinsOverDerivation() {
        val reason = ReviewReasonResolver.resolve(
            record(start = ms(28, 9, 22), end = ms(28, 9, 25), reviewReason = "R3 21:00-21:29 灰区"),
            zone
        )
        assertEquals("自动流程的规则原因优先，不得被覆盖", "R3 21:00-21:29 灰区", reason)
    }

    @Test fun returnsNullWhenNoReviewNeeded() {
        assertNull(ReviewReasonResolver.resolve(record(needsReview = false), zone))
    }

    // ---------- 各数据自检分支 ----------

    @Test fun detectsEndNotAfterStart() {
        val reason = ReviewReasonResolver.resolve(record(start = ms(29, 9, 0), end = ms(29, 9, 0), shift = "DAY_SHIFT"), zone)
        assertTrue(reason!!.contains("下班时间不晚于上班时间"))
    }

    @Test fun detectsMissingBounds() {
        val noStart = ReviewReasonResolver.resolve(record(start = null), zone)
        assertTrue(noStart!!.contains("缺上班时间"))
        val noEnd = ReviewReasonResolver.resolve(record(end = null), zone)
        assertTrue(noEnd!!.contains("缺下班时间"))
    }

    @Test fun detectsHomeArrivalBeforeDeparture() {
        val reason = ReviewReasonResolver.resolve(
            record(start = ms(28, 21, 0), end = ms(29, 9, 0), homeArrival = ms(29, 8, 0)),
            zone
        )
        assertTrue(reason!!.contains("到家时间早于下班时间"))
    }

    @Test fun detectsHomeDepartureAfterArrivalAtCompany() {
        val reason = ReviewReasonResolver.resolve(
            record(start = ms(28, 21, 0), end = ms(29, 9, 0), homeDeparture = ms(29, 1, 0)),
            zone
        )
        assertTrue(reason!!.contains("离家时间晚于到公司时间"))
    }

    // ---------- 不得误报 ----------

    @Test fun normalNightShiftIsNotFlaggedForShiftMismatch() {
        // 真实夜班记录上班时刻 20:43 / 20:54 / 21:00 → 不应被判"班次与时间不符"
        val reason = ReviewReasonResolver.resolve(
            record(start = ms(28, 20, 43), end = ms(29, 9, 16), homeArrival = ms(29, 9, 53)),
            zone
        )
        assertNull("正常夜班不应推导出任何异常：$reason", reason)
    }

    @Test fun normalDayShiftIsNotFlagged() {
        val reason = ReviewReasonResolver.resolve(
            record(
                start = ms(28, 9, 0),
                end = ms(28, 21, 0),
                shift = "DAY_SHIFT",
                homeArrival = ms(28, 21, 20)
            ),
            zone
        )
        assertNull("正常白班不应推导出异常：$reason", reason)
    }
}
