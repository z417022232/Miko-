package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.WorkRecordEntity

/**
 * 「迟到到家证据」补写的选行规则（纯函数，无平台依赖）。
 *
 * 到家时间往往在工时记录落库之后才拿到（FINISHED 时才落库，REST 时才确认到家），
 * 所以必须回头把 `homeArrivalTime` 补进**本次会话**那条记录。选错行的后果很隐蔽：
 * 记录不会报错，只是永远缺一个到家时间，或者把到家时间写进另一天。
 *
 * 两条硬规则（都是真机踩出来的）：
 *
 * 1. **目标行必须是本次会话**：`startTime <= sessionStart`。
 *    09-21 早晨实测出现两段会话（09:17 折返回公司后二次离岗），第二段 09:18 建的记录
 *    `endTime` 更晚，只按 `MAX(endTime)` 会把到家时间写进第二段那条，
 *    真正的目标（夜班那条）继续缺到家时间。
 * 2. **到家不得早于离岗**：与 [ConfirmedSession.merge] 的 `validHomeArrival` 同口径，
 *    顺序非法的到家证据宁可不写（写了就是脏数据，且会盖掉人工值）。
 */
object HomeArrivalBackfill {

    /**
     * @param arrival 本次确认的到家时刻。
     * @param sessionStart 本轮会话的到岗时刻（[com.example.worktimetracker.data.entity.WorkStateEntity.sessionStart]）。
     *   null = 拿不到会话起点（previous 不是 FINISHED），此时只按时间顺序兜底。
     */
    fun pick(
        records: List<WorkRecordEntity>,
        arrival: Long,
        sessionStart: Long?
    ): WorkRecordEntity? = records
        .asSequence()
        // 与 ConfirmedSession 一致：休息日记录不参与，且只补「已完结」的记录
        .filter { it.status != "REST" }
        .filter { val end = it.endTime; end != null && end <= arrival }
        .filter { sessionStart == null || (it.startTime ?: Long.MAX_VALUE) <= sessionStart }
        // 同一早晨两段会话时，被 sessionStart 挡掉的正是后建的那条；剩下的里面取最近完结的
        .maxWithOrNull(compareBy<WorkRecordEntity> { it.endTime ?: 0L }.thenBy { it.id })
}
