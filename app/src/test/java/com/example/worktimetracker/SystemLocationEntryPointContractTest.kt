package com.example.worktimetracker

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLocationEntryPointContractTest {
    @Test
    fun `activity checks and records system location before starting recovery`() {
        val body = functionBody(source("app/src/main/java/com/example/worktimetracker/MainActivity.kt"), "override fun onStart()")
        val check = body.indexOf("SystemLocationStateChecker.checkAndRecord")
        val start = body.indexOf("ServiceRecovery.start")
        assertTrue("MainActivity.onStart 必须主动检查系统定位", check >= 0)
        assertTrue("系统定位检查必须早于服务拉起", start > check)
        assertTrue("定位关闭时不得拉起服务", body.contains("systemLocationEnabled") && body.contains("if (hasLocationPermission && systemLocationEnabled)"))
    }

    @Test
    fun `alarm watchdog checks system location before starting recovery`() {
        val source = source("app/src/main/java/com/example/worktimetracker/location/recovery/AlarmWatchdog.kt")
        val check = source.indexOf("SystemLocationStateChecker.checkAndRecord")
        val start = source.indexOf("ServiceRecovery.start(", check.coerceAtLeast(0))
        assertTrue("闹钟兜底必须主动检查系统定位", check >= 0)
        assertTrue("系统定位检查必须早于服务拉起", start > check)
        assertTrue("关闭时必须跳过拉起", source.contains("if (!systemLocationEnabled)"))
    }

    @Test
    fun `location switch receiver is manifest declared for both system location actions`() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        val receiver = manifest.indexOf(".location.recovery.LocationSwitchReceiver")
        assertTrue("必须声明系统定位开关接收器", receiver >= 0)
        val filterBody = manifest.substring(receiver, manifest.indexOf("</receiver>", receiver))
        assertTrue("必须监听 PROVIDERS_CHANGED", filterBody.contains("android.location.PROVIDERS_CHANGED"))
        assertTrue("必须监听 MODE_CHANGED", filterBody.contains("android.location.MODE_CHANGED"))
        assertTrue("接收系统广播必须 exported", filterBody.contains("android:exported=\"true\""))
    }

    @Test
    fun `location switch receiver filters actions and kicks alarm only when heartbeat stale`() {
        val source = source("app/src/main/java/com/example/worktimetracker/location/recovery/LocationSwitchReceiver.kt")
        assertTrue("必须按 action 白名单过滤", source.contains("isSwitchAction(action)"))
        val check = source.indexOf("SystemLocationStateChecker.checkAndRecord")
        val kick = source.indexOf("AlarmWatchdog.scheduleKick")
        assertTrue("必须先记录状态转移再决定是否踢看门狗", check >= 0 && kick > check)
        assertTrue("踢看门狗必须由纯策略判定心跳失效", source.contains("LocationSwitchRecoveryPolicy.evaluate"))
    }

    @Test
    fun `notification claim is gated by vivo sleep mode and stale alert is cancelled on recovery`() {
        val claim = source("app/src/main/java/com/example/worktimetracker/location/recovery/ServiceRecovery.kt")
        val body = functionBody(claim, "fun claimSystemLocationNotification(")
        val gate = body.indexOf("SystemLocationNotifyGate.shouldDisturbUser")
        val mark = body.indexOf("putLong(LOCATION_ALERT_NOTIFIED")
        assertTrue("通知领取必须先过睡眠模式豁免", gate in 0 until mark)
        assertTrue("豁免判定必须基于 vivo 睡眠标志读取", body.contains("VivoSleepModeReader.isActive(context)"))
        val sleepCheck = body.indexOf("VivoSleepModeReader.isActive(context)")
        val sessionCheck = body.indexOf("ActiveWorkSessionReader.hasActive")
        assertTrue("睡眠模式激活时必须复核工作会话（夜班场景）", sleepCheck in 0 until sessionCheck && sessionCheck < gate)
        assertTrue("工作时段的提醒必须限流", body.contains("KEY_WORKING_LOCATION_OFF"))
        val notifier = source("app/src/main/java/com/example/worktimetracker/location/recovery/RecoveryNotifier.kt")
        assertTrue("必须提供撤旧通知入口", notifier.contains("cancelSystemLocationDisabled"))
        assertTrue("通知文案必须区分工作时段", notifier.contains("ActiveWorkSessionReader.hasActive"))
        val service = source("app/src/main/java/com/example/worktimetracker/location/service/ForegroundLocationService.kt")
        assertTrue("服务恢复路径必须撤旧通知", service.contains("RecoveryNotifier.cancelSystemLocationDisabled"))
        val receiver = source("app/src/main/java/com/example/worktimetracker/location/recovery/LocationSwitchReceiver.kt")
        assertTrue("开关广播恢复路径必须撤旧通知（覆盖服务已死场景）", receiver.contains("RecoveryNotifier.cancelSystemLocationDisabled"))
    }

    @Test
    fun `work session is read from snapshot so main thread claim cannot hit room`() {
        val reader = source("app/src/main/java/com/example/worktimetracker/location/recovery/VivoSleepModeReader.kt")
        assertTrue("必须有进程级快照对象", reader.contains("object WorkSessionSnapshot"))
        val hasActive = functionBody(reader, "fun hasActive(context: Context)")
        val cache = hasActive.indexOf("WorkSessionSnapshot.currentState")
        val blocking = hasActive.indexOf("getStateBlocking")
        assertTrue("必须优先读快照（主线程不能碰 Room）", cache in 0 until blocking)
        assertTrue("兜底直读必须吞异常", hasActive.contains("runCatching"))
        val app = source("app/src/main/java/com/example/worktimetracker/WorkTimeApplication.kt")
        assertTrue("快照必须由 Flow 收集器维护", app.contains("WorkSessionSnapshot.currentState = it?.currentState"))
        assertTrue("收集器必须吞异常不影响启动链", functionBody(app, "override fun onCreate()").contains("runCatching"))
    }

    @Test
    fun `health worker notification branches respect the same disturb gate`() {
        val worker = source("app/src/main/java/com/example/worktimetracker/location/recovery/LocationHealthWorker.kt")
        val disturb = worker.indexOf("SystemLocationNotifyGate.shouldDisturbUser")
        assertTrue("巡检必须先算打扰闸门", disturb >= 0)
        assertTrue("服务死亡通知必须过闸门", worker.contains("if (disturb) sendRecoveryNotification(\"工时记录服务已停止\""))
        assertTrue("Provider不可用通知必须过闸门", worker.contains("if (disturb) sendRecoveryNotification(\"定位记录可能中断\""))
    }

    @Test
    fun `location switch receiver logs a diagnostic line for every delivered broadcast`() {
        val receiver = source("app/src/main/java/com/example/worktimetracker/location/recovery/LocationSwitchReceiver.kt")
        assertTrue("必须落诊断日志", receiver.contains("type = \"LOCATION_SWITCH\""))
        assertTrue("诊断行必须含送达判定与领取结果", receiver.contains("claimed="))
    }

    private fun source(relative: String): String {
        var dir = File(System.getProperty("user.dir"))
        repeat(5) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到源码：$relative")
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "找不到函数：$signature" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, index)
            }
        }
        error("函数括号未闭合：$signature")
    }
}
