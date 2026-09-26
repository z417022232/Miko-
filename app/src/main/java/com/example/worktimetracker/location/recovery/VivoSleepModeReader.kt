package com.example.worktimetracker.location.recovery

import android.content.Context
import android.provider.Settings

/**
 * vivo 省电引擎（com.vivo.pem）的睡眠模式读取器。
 *
 * 已实锤（2026-09-24/25 过夜取证，见 mode.log）：睡眠待机优化激活期间会把系统定位
 * 主开关整个关闭，一夜反复多次，恢复时同时退出——`location_mode=0` 与
 * `pem_in_sleepmode=1` 一一对应。方案一定型后这是**已知正常行为**，不应再惊扰用户。
 *
 * 直接读系统设置里的睡眠模式标志位，而不是猜时间窗口：用户上夜班时夜里醒着，
 * 时间窗口会误伤；标志位由系统自己维护， schedule 漂移也天然正确。
 * 读取失败按「未激活」处理，回退到原有通知行为（宁可多通知）。
 */
object VivoSleepModeReader {
    private const val KEY_PEM_SLEEP = "pem_in_sleepmode"

    fun isActive(context: Context): Boolean = runCatching {
        Settings.System.getInt(context.contentResolver, KEY_PEM_SLEEP, 0) == 1
    }.getOrDefault(false)
}

object SystemLocationNotifyGate {
    /**
     * 系统定位被关闭时是否应通知用户。
     *
     * 睡眠模式激活中（已知正常关闭）默认静默；**唯一例外是工作会话进行中**：
     * 用户上夜班时人醒着、手机闲置在公司，PEM 会误判睡眠而关定位——那时
     * 静默等于丢工时证据还没人知道，必须提醒。其余任何关闭（用户手关、
     * 白天被关、睡眠标志读取失败）都通知。
     */
    fun shouldNotifyLocationOff(sleepModeActive: Boolean, hasActiveWorkSession: Boolean): Boolean =
        !sleepModeActive || hasActiveWorkSession
}

/**
 * 工作会话进行中的同步读取（与 JourneyObservation.hasActiveWorkSession 同口径）。
 *
 * 只在睡眠模式激活这一罕见分支被调用。读不到状态行（从未记录过会话）视为不在工作；
 * 读取异常同样视为不在工作——此时通知本身也发不出去，不必再放大。
 */
object ActiveWorkSessionReader {
    private val ACTIVE_STATES = setOf("WORKING", "TEMP_LEAVE")

    fun hasActive(context: Context): Boolean = runCatching {
        val app = context.applicationContext as? com.example.worktimetracker.WorkTimeApplication
        val state = app?.database?.workStateDao()?.getStateBlocking()
        state != null && state.currentState in ACTIVE_STATES
    }.getOrDefault(false)
}
