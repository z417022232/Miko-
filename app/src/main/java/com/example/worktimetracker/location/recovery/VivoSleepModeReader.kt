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
     * 唯一豁免是 vivo 睡眠模式激活中（恢复由 App 自愈链负责，见 LocationSwitchReceiver）；
     * 其余任何关闭（用户手关、白天被关、睡眠标志读取失败）都通知。
     */
    fun shouldNotifyLocationOff(sleepModeActive: Boolean): Boolean = !sleepModeActive
}
