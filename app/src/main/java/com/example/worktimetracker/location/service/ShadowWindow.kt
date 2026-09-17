package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.EnvironmentFingerprintEntity
import com.example.worktimetracker.data.entity.PlaceAnchorCandidateEntity
import com.example.worktimetracker.domain.location.GeoPoint
import com.example.worktimetracker.domain.location.ShadowObservation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 影子窗口的**重建**：把候选行还原成「一天一条」的观测序列。
 *
 * ## 为什么必须抽成一处
 *
 * `ShadowValidator` 的六个条件全部从这组观测重建，其中「窗口内每一天都必须有有效样本」
 * 依赖一个不显眼的规则：**同一天的多行只算一天**（取当天最后一条）。
 * 学习侧（`AnchorLearningService`）与展示侧（`PlaceLearningReport`）都要用这条规则 ——
 * 各写一份的话，两边迟早对同一个窗口给出不同的天数，
 * 表现为「页面上说已经 7 天，但就是不生效」。这种不一致不会报错，
 * 只会让人反复怀疑算法。所以：**重建只有这一份实现**。
 *
 * ## 与原始行的关系
 *
 * 候选表是观测日志，不改写、不聚合。本对象只做**读取时的投影**，
 * 因此换一个 [ZoneId] 或换个「当天取最后一条」的口径，都能从原始行重新算出全部条件
 * —— 这正是「模型必须能从原始数据全量重建」在候选层的落法。
 */
internal object ShadowWindow {

    /**
     * 候选行 → 一天一条的观测，按日期升序。
     *
     * 同一天多行（历史数据、或状态变化时插入的）取 `lastSeenAt` 最大的那条。
     */
    fun observations(
        rows: List<PlaceAnchorCandidateEntity>,
        zone: ZoneId
    ): List<ShadowObservation> = rows
        .groupBy { dayOf(it.lastSeenAt, zone) }
        .map { (day, sameDay) -> day to sameDay.maxBy { it.lastSeenAt } }
        .map { (day, row) ->
            ShadowObservation(
                day = day,
                center = GeoPoint(row.centerLat, row.centerLng),
                ambientSources = row.ambientSourceCount,
                spreadP90Meters = row.spreadP90Meters
            )
        }
        .sortedBy { it.day }

    /**
     * 窗口内的**指纹冲突**次数：同一个环境标识同时被两个地点支持。
     *
     * 只看窗口内仍在活跃的指纹（`lastObservedAt` 落在窗口内），
     * 否则历史脏数据会把新窗口一票否决。
     */
    fun conflicts(fingerprints: List<EnvironmentFingerprintEntity>, windowStart: Long): Int =
        fingerprints
            .filter { it.lastObservedAt >= windowStart }
            .groupBy { it.identifierHash }
            .count { (_, rows) -> rows.map { it.place }.distinct().size > 1 }

    fun dayOf(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
