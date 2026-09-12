package com.example.worktimetracker.data.remote

import com.example.worktimetracker.domain.engine.HolidayArrangement
import java.net.HttpURLConnection
import java.net.URL

/**
 * 节假日远端数据源。**零新增依赖**（Android 框架自带 `HttpURLConnection` + `org.json`）。
 *
 * 多镜像故障转移：按国内可达性排序依次尝试，任一成功即返回；
 * 全部失败则返回 [Result.failure]，由上层降级到内嵌表 —— 绝不因为断网而让界面空窗。
 *
 * 数据源头指向 [NateScarlet/holiday-cn](https://github.com/NateScarlet/holiday-cn)：
 * 该仓库由国务院办公厅公告机器生成，每年 11 月公告发布后自动更新，
 * 响应里的 `papers` 字段即 gov.cn 原文链接，可追溯、可核对。
 */
class HolidayRemoteSource(
    private val timeoutMs: Int = 8_000,
    private val maxBytes: Int = 256 * 1024,
    private val endpoints: List<String> = DEFAULT_ENDPOINTS
) {

    data class FetchResult(val arrangement: HolidayArrangement, val endpoint: String) {
        /** 供设置页展示的数据来源域名。 */
        val host: String get() = runCatching { URL(endpoint).host }.getOrDefault(endpoint)
    }

    fun fetch(year: Int): Result<FetchResult> {
        val failures = mutableListOf<String>()
        for (template in endpoints) {
            val url = template.replace("{year}", year.toString())
            runCatching { download(url) }
                .onFailure { failures += "${hostOf(url)}: ${it.javaClass.simpleName}" }
                .getOrNull()
                ?.let { body ->
                    val parsed = HolidayPayloadParser.parse(year, body)
                    if (parsed != null) return Result.success(FetchResult(parsed, url))
                    failures += "${hostOf(url)}: 响应格式无法识别"
                }
        }
        return Result.failure(
            IllegalStateException(failures.joinToString("；").ifBlank { "无可用数据源" })
        )
    }

    private fun download(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WorkTimeTracker/Android")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP $code")
            }
            return conn.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) break
                    out.write(buffer, 0, read)
                }
                out.toString("UTF-8")
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    companion object {
        /**
         * 按"国内可达性 + 权威性"排序：
         * 1. jsDelivr CDN（国内通常可直连，且内容与 GitHub 同源）
         * 2. GitHub raw（最权威，但国内可能超时）
         * 3. timor.tech（国内接口，返回形态不同，作为最后兜底）
         */
        val DEFAULT_ENDPOINTS = listOf(
            "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/{year}.json",
            "https://fastly.jsdelivr.net/gh/NateScarlet/holiday-cn@master/{year}.json",
            "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/{year}.json",
            "https://timor.tech/api/holiday/year/{year}"
        )
    }
}
