package com.example.worktimetracker

import com.example.worktimetracker.data.entity.WorkStateEntity
import com.example.worktimetracker.domain.engine.WorkdayClock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WorkdayClockTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val night = LocalDate.of(2026, 9, 15)
    private val afterMidnight = LocalDate.of(2026, 9, 16)

    private fun millisOf(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun nightShiftStillRunningAfterMidnightKeepsWorkdayOnStartDate() {
        // 用户报的场景：9/15 21:16 上班，9/16 凌晨打开 App 时班还没下
        val state = WorkStateEntity(currentState = "WORKING", sessionStart = millisOf(night, 21, 16))
        assertEquals(night, WorkdayClock.today(state, afterMidnight, zone))
    }

    @Test fun tempLeaveStillCountsAsSameWorkday() {
        val state = WorkStateEntity(currentState = "TEMP_LEAVE", sessionStart = millisOf(night, 21, 16))
        assertEquals(night, WorkdayClock.today(state, afterMidnight, zone))
    }

    @Test fun afterShiftEndedWorkdayFollowsCalendar() {
        // 9/16 09:04 下班、状态机回到 REST 之后，就该显示 9/16
        val state = WorkStateEntity(currentState = "REST", sessionStart = null,
            confirmedDepartureTime = millisOf(afterMidnight, 9, 4))
        assertEquals(afterMidnight, WorkdayClock.today(state, afterMidnight, zone))
    }

    @Test fun finishedStateDoesNotExtendWorkday() {
        // FINISHED = 已离岗未到家，班已经下完，不该再拖住工作日
        val state = WorkStateEntity(currentState = "FINISHED",
            sessionStart = millisOf(night, 21, 16),
            confirmedDepartureTime = millisOf(afterMidnight, 9, 4))
        assertEquals(afterMidnight, WorkdayClock.today(state, afterMidnight, zone))
    }

    @Test fun dayShiftKeepsNaturalDay() {
        val state = WorkStateEntity(currentState = "WORKING", sessionStart = millisOf(afterMidnight, 9, 0))
        assertEquals(afterMidnight, WorkdayClock.today(state, afterMidnight, zone))
    }

    @Test fun noSessionFallsBackToNaturalDay() {
        assertEquals(afterMidnight,
            WorkdayClock.today(WorkStateEntity(currentState = "LEAVING_HOME"), afterMidnight, zone))
        assertEquals(afterMidnight, WorkdayClock.today(null, afterMidnight, zone))
    }

    @Test fun staleSessionOlderThanOneDayDoesNotWin() {
        // 状态卡死（进程被杀、权限被回收）时不能把几天前那天当成「今天」
        val state = WorkStateEntity(currentState = "WORKING",
            sessionStart = millisOf(night.minusDays(3), 21, 0))
        assertEquals(afterMidnight, WorkdayClock.today(state, afterMidnight, zone))
    }
}
