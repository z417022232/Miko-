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
