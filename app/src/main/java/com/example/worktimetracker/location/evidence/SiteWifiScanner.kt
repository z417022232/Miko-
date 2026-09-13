package com.example.worktimetracker.location.evidence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** 扫描到的 Wi-Fi：`label`（SSID）只在内存里用于让用户认出是哪一台，不落库。 */
data class ScannedWifi(
    val identifierHash: String,
    val label: String,
    val signal: Int,
    val secure: Boolean
)

/** Wi-Fi 扫描结果（界面稿 11「选取 Wi-Fi 网络」）。 */
sealed interface WifiScanOutcome {
    data class Ok(val items: List<ScannedWifi>) : WifiScanOutcome
    data class Error(val reason: Reason, val message: String) : WifiScanOutcome

    enum class Reason { PERMISSION, DISABLED, EMPTY, FAILED }
}

/**
 * 地点管理专用的 Wi-Fi 扫描器（v4.3）。
 *
 * 与后台采集器 [WifiEvidenceCollector] 的区别：
 * - 只在前台、用户手动点「扫描」时运行，可以放心 startScan()；
 * - 返回可直接展示的候选列表（含 SSID 供识别），但**哈希口径与后台完全一致**
 *   （`hash(salt, ["wifi", ssid, bssid])`），所以用户在这里选的 Wi-Fi
 *   与后台采到的特征可以逐位比对，不需要额外映射表；
 * - SSID 只存在于返回值的内存对象里，落库由 ViewModel 剥离。
 */
class SiteWifiScanner(
    private val context: Context,
    private val saltProvider: () -> ByteArray
) {

    @Suppress("DEPRECATION")
    fun scan(): WifiScanOutcome {
        if (!hasWifiPermission()) {
            return WifiScanOutcome.Error(WifiScanOutcome.Reason.PERMISSION, "缺少 Wi-Fi 扫描权限，请先到权限页授予")
        }
        if (!hasLocationPermission()) {
            return WifiScanOutcome.Error(WifiScanOutcome.Reason.PERMISSION, "缺少定位权限：系统要求扫 Wi-Fi 必须同时有定位权限")
        }
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return WifiScanOutcome.Error(WifiScanOutcome.Reason.FAILED, "系统 Wi-Fi 服务不可用")
        return runCatching {
            if (!manager.isWifiEnabled) {
                return@runCatching WifiScanOutcome.Error(
                    WifiScanOutcome.Reason.DISABLED, "工作地点的 Wi-Fi 处于关闭状态，请先打开 Wi-Fi"
                )
            }
            // 主动触发一次扫描；被系统限流时退回读取已有快照，不报错
            runCatching { manager.startScan() }
            val salt = saltProvider()
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val items = manager.scanResults.orEmpty().mapNotNull { result ->
                val bssid = result.BSSID ?: return@mapNotNull null
                val observedElapsedMs = result.timestamp / 1000
                if (observedElapsedMs <= 0L) return@mapNotNull null
                if (nowElapsedMs - observedElapsedMs > MAX_RESULT_AGE_MILLIS) return@mapNotNull null
                ScannedWifi(
                    identifierHash = EnvironmentIdentifierHasher.hash(
                        salt, listOf("wifi", result.SSID.orEmpty(), bssid)
                    ),
                    label = result.SSID?.takeIf { it.isNotBlank() } ?: "隐藏网络",
                    signal = result.level,
                    secure = !result.capabilities.isNullOrBlank()
                )
            }.distinctBy { it.identifierHash }.sortedByDescending { it.signal }

            if (items.isEmpty()) {
                WifiScanOutcome.Error(
                    WifiScanOutcome.Reason.EMPTY,
                    "没扫到 Wi-Fi。请站到工作地点、确认 Wi-Fi 已开启后重试（系统约 2 分钟允许一次真实扫描）"
                )
            } else {
                WifiScanOutcome.Ok(items)
            }
        }.getOrElse { failure ->
            when (failure) {
                is SecurityException -> WifiScanOutcome.Error(
                    WifiScanOutcome.Reason.PERMISSION, "系统拒绝了 Wi-Fi 扫描请求（权限不完整）"
                )
                else -> WifiScanOutcome.Error(
                    WifiScanOutcome.Reason.FAILED, failure.message ?: "扫描失败，请稍后重试"
                )
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Android 13+ 用 NEARBY_WIFI_DEVICES；低版本回落到定位权限（与后台采集器同口径）。 */
    private fun hasWifiPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            hasLocationPermission()
        }

    companion object {
        /**
         * 只接受 2 分钟内的扫描结果。用户点「扫描」时人就站在工作地点，
         * 拿几分钟前的旧快照选出来的网络可能已经不在原地了。
         */
        const val MAX_RESULT_AGE_MILLIS = 2 * 60_000L
    }
}
