package com.example.worktimetracker.ui.theme

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 主题模式。默认 [AUTO_TIME]：按当地时间自动切换，白天浅色、夜间深色。
 *
 * 注意：这不是"跟随系统"的替代品——用户可选跟随系统；[AUTO_TIME] 是为
 * 倒班场景准备的（工厂白班/夜班跨昼夜，系统深色往往不跟着时间走）。
 */
enum class ThemeMode(val label: String, val summary: String) {
    AUTO_TIME("自动（按时间）", "白天 07:00–19:00 浅色，夜间深色"),
    SYSTEM("跟随系统", "与手机系统的深色模式保持一致"),
    LIGHT("始终浅色", "不随时间和系统变化"),
    DARK("始终深色", "不随时间和系统变化");

    companion object {
        val default = AUTO_TIME

        /** 兼容历史值与脏数据：无法识别时回落到默认，绝不抛异常。 */
        fun parse(raw: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: default
    }
}

/**
 * 昼夜切换时刻表。切换点取整点，避免"日落时刻"这类需要天文计算的复杂度。
 *
 * 07:00 起浅色、19:00 起深色。倒班用户（如 21:00 上班）在 19:00 后拿到深色，
 * 夜班期间界面不刺眼；白班 09:00–21:00 的绝大部分时间处于浅色。
 */
object DayNightSchedule {
    const val LIGHT_FROM_HOUR = 7
    const val DARK_FROM_HOUR = 19

    val lightStart: LocalTime = LocalTime.of(LIGHT_FROM_HOUR, 0)
    val darkStart: LocalTime = LocalTime.of(DARK_FROM_HOUR, 0)

    /** 该时刻是否应使用深色。 */
    fun isDark(time: LocalTime): Boolean =
        time < lightStart || time >= darkStart

    /** 下一次切换时刻（用于精确定时重组，避免每分钟轮询）。 */
    fun nextSwitchAt(now: LocalDateTime): LocalDateTime = when {
        now.toLocalTime() < lightStart -> now.toLocalDate().atTime(lightStart)
        now.toLocalTime() < darkStart -> now.toLocalDate().atTime(darkStart)
        else -> now.toLocalDate().plusDays(1).atTime(lightStart)
    }

    /** 距离下一次切换的毫秒数，至少 1 秒，避免 0 延迟死循环。 */
    fun millisUntilNextSwitch(now: LocalDateTime): Long =
        Duration.between(now, nextSwitchAt(now)).toMillis().coerceAtLeast(1_000L)

    /**
     * 解析最终是否使用深色主题。
     *
     * @param systemDark 系统是否处于深色（仅 [ThemeMode.SYSTEM] 使用）
     */
    fun resolve(mode: ThemeMode, now: LocalDateTime, systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.AUTO_TIME -> isDark(now.toLocalTime())
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}
