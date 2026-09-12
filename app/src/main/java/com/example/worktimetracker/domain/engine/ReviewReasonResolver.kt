package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.data.entity.WorkRecordEntity
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * A7: 复核原因推导。
 *
 * 背景：A2 起自动流程会写 `reviewReason`（如 "R3 21:00-21:29 灰区"），但**A2 之前**产生的记录
 * `reviewReason` 为 NULL。A6 的复核横幅对这类记录只能显示通用兜底文案，等于没告诉用户问题在哪。
 *
 * 本对象不动任何数据、不改 needsReview，**纯展示用推导**：
 * 1. `reviewReason` 非空 → 原样返回（自动流程的规则原因优先级最高，权威）
 * 2. `reviewReason` 为空且 `needsReview` → 从记录自身的**数据合法性**推导原因
 *
 * ⚠️ 这里只做**数据自检**（时间顺序、时长、班次与时刻是否自洽），
 * 不涉及任何计薪业务规则，因此不需要新的规则确认。
 * 计薪口径仍以 `verification/工时计薪规则.md` 与 `WorkHourCalculator` 为唯一来源。
 */
object ReviewReasonResolver {

    /** 到家时间距下班超过该值即视为异常（正常应在几十分钟内）。 */
    private const val HOME_ARRIVAL_MAX_DELAY_MINUTES = 8 * 60L

    /** 在岗时长低于该值即便是"识别异常"而非真实出勤。 */
    private const val SUSPICIOUS_MIN_DURATION_MINUTES = 60L

    /** 夜班的上班时刻若落在这个小时区间（白天），说明 shift 与时间戳不自洽。 */
    private val NIGHT_SHIFT_DAYTIME_HOURS = 5..17

    fun resolve(record: WorkRecordEntity, zoneId: ZoneId = ZoneId.systemDefault()): String? {
        // 1. 自动流程给的规则原因优先，直接采用
        record.reviewReason?.takeIf { it.isNotBlank() }?.let { return it }
        if (!record.needsReview) return null

        val issues = mutableListOf<String>()
        val start = record.startTime
        val end = record.endTime

        if (start == null) issues.add("缺上班时间")
        if (end == null) issues.add("缺下班时间")

        if (start != null && end != null) {
            if (end <= start) {
                issues.add("下班时间不晚于上班时间")
            } else {
                val durationMin = TimeUnit.MILLISECONDS.toMinutes(end - start)
                if (durationMin < SUSPICIOUS_MIN_DURATION_MINUTES) {
                    issues.add("在岗时长仅 ${durationMin}min，疑似识别异常")
                }
            }

            // 班次与上班时刻是否自洽
            if (record.shift == "NIGHT_SHIFT") {
                val local = Instant.ofEpochMilli(start).atZone(zoneId)
                if (local.hour in NIGHT_SHIFT_DAYTIME_HOURS) {
                    issues.add("记录为夜班但上班时刻是 ${clock(local.hour, local.minute)}，班次与时间不符")
                }
            }

            // 到家时间应晚于下班，且不应离下班过远
            record.homeArrivalTime?.let { arrival ->
                val delayMin = TimeUnit.MILLISECONDS.toMinutes(arrival - end)
                when {
                    delayMin < 0 -> issues.add("到家时间早于下班时间")
                    delayMin > HOME_ARRIVAL_MAX_DELAY_MINUTES ->
                        issues.add("到家时间距下班约 ${delayMin / 60}h，疑似异常")
                }
            }

            // 离家时间应早于到公司
            record.homeDepartureTime?.let { departure ->
                if (departure > start) issues.add("离家时间晚于到公司时间")
            }
        }

        return issues.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    private fun clock(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)
}
