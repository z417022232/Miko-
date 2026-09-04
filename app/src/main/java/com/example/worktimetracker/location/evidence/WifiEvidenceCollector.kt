package com.example.worktimetracker.location.evidence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.worktimetracker.domain.evidence.EvidenceSource

/**
 * Wi-Fi 环境采集器：读取系统已有扫描快照；只有扫描策略决定扫描时才尝试 startScan()。
 * 哈希字段使用 SSID + BSSID，原始值不进入日志或数据库。
 *
 * 系统缓存的扫描结果带真实观察时间（自启动起的微秒时间戳）：超过可信年龄的
 * 缓存结果不参与证据，防止离开公司后旧环境被每次读取反复「续期」而延误离岗记录。
 * 主动 startScan() 后系统未完成新扫描时返回的仍是旧缓存，同样会被此过滤拦截。
 */
class WifiEvidenceCollector(
    private val context: Context,
    private val saltProvider: () -> ByteArray,
    private val mayStartScan: () -> Boolean
) : AmbientCollector {

    override suspend fun snapshot(now: Long): CollectorResult = runCatching {
        if (!hasLocationPermission()) return CollectorResult.failed(CollectorFailure.PERMISSION, now)
        if (!isLocationEnabled()) return CollectorResult.failed(CollectorFailure.DISABLED, now)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
            as? WifiManager ?: return CollectorResult.failed(CollectorFailure.SYSTEM, now)

        var throttled = false
        if (mayStartScan() && wifiManager.isWifiEnabled) {
            val started = wifiManager.startScan()
            if (!started) throttled = true
        }
        if (!wifiManager.isWifiEnabled) return CollectorResult.failed(CollectorFailure.DISABLED, now)

        val salt = saltProvider()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val features = wifiManager.scanResults.orEmpty().mapNotNull { it.toFeature(salt, nowElapsedMs) }
        when {
            features.isNotEmpty() -> CollectorResult(features, now)
            throttled -> CollectorResult.failed(CollectorFailure.THROTTLED, now)
            else -> CollectorResult.failed(CollectorFailure.EMPTY, now)
        }
    }.getOrElse { failure ->
        when (failure) {
            is SecurityException -> CollectorResult.failed(CollectorFailure.SECURITY, now)
            else -> CollectorResult.failed(CollectorFailure.SYSTEM, now)
        }
    }

    override fun stop() = Unit

    private fun ScanResult.toFeature(salt: ByteArray, nowElapsedMs: Long): CollectorFeature? {
        val bssid = BSSID ?: return null
        // timestamp 为自启动起的微秒；无法判定观察时间（<=0）或过旧的缓存一律丢弃
        val observedElapsedMs = timestamp / 1000
        if (observedElapsedMs <= 0L) return null
        val ageMs = nowElapsedMs - observedElapsedMs
        if (ageMs > STALE_RESULT_MAX_AGE_MILLIS) return null
        val hash = EnvironmentIdentifierHasher.hash(salt, listOf("wifi", SSID.orEmpty(), bssid))
        return CollectorFeature(EvidenceSource.WIFI, hash, level)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    companion object {
        /** 系统缓存扫描结果的最大可信年龄：正常系统 15~30 秒一轮扫描，5 分钟足以覆盖空闲场景 */
        const val STALE_RESULT_MAX_AGE_MILLIS = 5 * 60_000L
    }
}
