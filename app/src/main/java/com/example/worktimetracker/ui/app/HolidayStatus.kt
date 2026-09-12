package com.example.worktimetracker.ui.app

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 设置页「节假日数据」区块的状态快照。
 *
 * 展示三条信息：**数据来源 / 最近更新时间 / 是否缺当前年份**。
 * 用户最关心的是"明年会不会没数据"，所以缺年份时要显式告警，而不是静默降级。
 */
data class HolidayStatusUi(
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val host: String? = null,
    val coveredYears: Set<Int> = emptySet(),
    val cachedYears: Set<Int> = emptySet(),
    val error: String? = null,
    val updating: Boolean = false,
    val message: String = "",
    /** 最近一次更新的结果：true=全部成功 / false=有失败或异常 / null=还没更新过 */
    val resultOk: Boolean? = null
) {
    /** 本地已有数据的年份并集（远端缓存 + 上次成功同步记录）。 */
    val knownYears: Set<Int> get() = coveredYears + cachedYears

    fun hasDataFor(year: Int): Boolean = year in knownYears
}

/**
 * 更新结果的三档色调。
 *
 * 单独定义而不是在 Composable 里就地比字符串，是因为"部分成功"曾经被误判成成功染成绿色；
 * 抽成纯函数后就能被单测钉死。
 */
enum class HolidayResultTone { SUCCESS, PARTIAL, FAILURE }

/** 把状态翻译成界面文案。纯函数，便于单测。 */
object HolidayStatusPresenter {

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun sourceLabel(status: HolidayStatusUi, currentYear: Int): String = when {
        status.updating -> "更新中…"
        status.lastSuccessAt != null && status.hasDataFor(currentYear) -> "联网获取"
        status.hasDataFor(currentYear) -> "内置公告"
        status.lastSuccessAt != null -> "联网获取"
        else -> "尚未获取"
    }

    fun updatedAtText(status: HolidayStatusUi, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = status.lastSuccessAt ?: return "尚未更新"
        val text = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone).format(timeFormatter)
        return "最近更新：$text"
    }

    fun hostText(status: HolidayStatusUi): String? =
        status.host?.takeIf { it.isNotBlank() }?.let { "数据源：$it" }

    /**
     * 缺数据的告警。返回 null 表示无需提醒。
     *
     * 注意措辞：法定节日当天由算法推算，**不会因为没联网就丢**，
     * 所以告警只说"放假/调休安排待更新"，避免用户误以为整个节假日功能失效。
     */
    fun warning(
        status: HolidayStatusUi,
        currentYear: Int,
        currentMonth: Int = LocalDateTime.now().monthValue,
        nextYearPublishMonth: Int = 10
    ): String? = when {
        !status.hasDataFor(currentYear) ->
            "⚠️ 缺 $currentYear 年放假安排，当前仅按法定节日与周末标记。请点「立即更新」联网获取。"
        currentMonth >= nextYearPublishMonth && !status.hasDataFor(currentYear + 1) ->
            "ℹ️ 国务院通常于 11 月发布次年安排，可点「立即更新」提前获取 ${currentYear + 1} 年数据。"
        else -> null
    }

    /** 手动更新后的结果文案。 */
    fun resultText(status: HolidayStatusUi, succeededYears: Set<Int>, failedYears: Set<Int>, host: String?): String = when {
        succeededYears.isNotEmpty() && failedYears.isEmpty() ->
            "已更新 ${succeededYears.sorted().joinToString("、")} 年" + (host?.let { "（$it）" } ?: "")
        succeededYears.isNotEmpty() ->
            "已更新 ${succeededYears.sorted().joinToString("、")} 年；" +
                "${failedYears.sorted().joinToString("、")} 年失败，已沿用本地数据"
        status.error != null -> "更新失败，已沿用本地数据"
        else -> "更新失败，已沿用本地数据"
    }

    /**
     * 结果卡片该用什么色调。
     *
     * [HolidayStatusUi.resultOk] 为 true 才代表**年份全部更新成功**；
     * 否则只要文案里出现"已更新"，就说明是**部分成功**，用橙色提示而不是绿色。
     */
    fun resultTone(resultOk: Boolean?, message: String): HolidayResultTone = when {
        resultOk == true -> HolidayResultTone.SUCCESS
        message.contains("已更新") -> HolidayResultTone.PARTIAL
        else -> HolidayResultTone.FAILURE
    }
}
