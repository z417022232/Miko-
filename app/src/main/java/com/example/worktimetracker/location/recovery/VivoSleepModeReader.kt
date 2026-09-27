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
     * 打扰用户的统一闸门：定位被系统关闭、服务死亡需要用户介入等场景共用。
     *
     * 睡眠模式激活中（已知正常关闭，自愈链负责恢复）默认静默；**唯一例外是工作会话进行中**：
     * 用户上夜班时人醒着、手机闲置在公司，PEM 会误判睡眠而关定位——那时
     * 静默等于丢工时证据还没人知道，必须提醒。其余任何关闭（用户手关、
     * 白天被关、睡眠标志读取失败）都打扰。
     */
    fun shouldDisturbUser(sleepModeActive: Boolean, hasActiveWorkSession: Boolean): Boolean =
        !sleepModeActive || hasActiveWorkSession
}

/**
 * 工作状态（currentState）的进程级快照，由 WorkTimeApplication 的 Flow 收集器维护。
 *
 * 通知领取的判定需要读工作状态，但领取可能发生在主线程（服务的 providerGlobalCheck），
 * Room 禁止主线程阻塞查询——2026-09-27 夜班实测 6 次定位关闭在领取时静默失败就是这个原因。
 * 读快照 = 零 IO、任意线程安全；仅冷启动未暖机时才落到 [ActiveWorkSessionReader] 的直读兜底。
 */
object WorkSessionSnapshot {
    @Volatile var currentState: String? = null
}

/**
 * 工作会话进行中的读取（与 JourneyObservation.hasActiveWorkSession 同口径）。
 * 优先读 [WorkSessionSnapshot]；快照未暖机（进程刚被广播拉起）时后台线程直读兜底，
 * 主线程直读会抛（Room 限制），按「不在工作」处理——收集器暖机后此窗口只有毫秒级。
 */
object ActiveWorkSessionReader {
    private val ACTIVE_STATES = setOf("WORKING", "TEMP_LEAVE")

    fun hasActive(context: Context): Boolean {
        WorkSessionSnapshot.currentState?.let { return it in ACTIVE_STATES }
        return runCatching {
            val state = (context.applicationContext as? com.example.worktimetracker.WorkTimeApplication)
                ?.database?.workStateDao()?.getStateBlocking()?.currentState
            state != null && state in ACTIVE_STATES
        }.getOrDefault(false)
    }
}
