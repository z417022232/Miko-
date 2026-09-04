package com.example.worktimetracker.location.evidence

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.worktimetracker.domain.evidence.EvidenceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 蓝牙环境采集器：开启 15 秒低功耗扫描窗口。只返回加盐哈希与 RSSI，
 * 不连接、不配对设备；扫描停止、服务销毁和权限撤销都必须调用 stopScan。
 */
class BluetoothEvidenceCollector(
    private val context: Context,
    private val saltProvider: () -> ByteArray,
    private val scope: CoroutineScope
) : AmbientCollector {

    private val discovered = ConcurrentHashMap<String, Int>()
    private var scanJob: Job? = null

    override suspend fun snapshot(now: Long): CollectorResult = runCatching {
        if (!hasScanPermission()) return CollectorResult.failed(CollectorFailure.PERMISSION, now)
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return CollectorResult.failed(CollectorFailure.DISABLED, now)
        if (!adapter.isEnabled) return CollectorResult.failed(CollectorFailure.DISABLED, now)
        val scanner = adapter.bluetoothLeScanner
            ?: return CollectorResult.failed(CollectorFailure.DISABLED, now)

        discovered.clear()
        val canReadNames = hasConnectPermission()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // 回调运行在 binder 线程，任何异常都会直接杀死进程，
                // 必须在这里兜底（历史教训：BLUETOOTH_CONNECT 缺失时
                // device.name 抛 SecurityException 导致应用整天反复崩溃）。
                runCatching {
                    val record = result.scanRecord ?: return
                    val device = result.device
                    val serviceUuids = record.serviceUuids?.mapNotNull { it.uuid.toString() }?.sorted().orEmpty()
                    val manufacturerKeys = record.manufacturerSpecificData?.let { data ->
                        (0 until data.size()).mapNotNull { index ->
                            data.valueAt(index)?.let { index.toString() }
                        }?.sorted()
                    }.orEmpty()
                    val hash = EnvironmentIdentifierHasher.hash(saltProvider(), listOf(
                        "ble",
                        if (canReadNames) device.name.orEmpty() else "",
                        device.address,
                        serviceUuids.joinToString(","),
                        manufacturerKeys.joinToString(",")
                    ))
                    val previous = discovered[hash]
                    if (previous == null || result.rssi > previous) discovered[hash] = result.rssi
                }
            }

            override fun onScanFailed(errorCode: Int) {
                // 结构化失败在超时后统一汇总
            }
        }
        val started = suspendCancellableCoroutine<Boolean> { cont ->
            scanner.startScan(null, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), callback)
            cont.resume(true)
        }
        if (!started) return CollectorResult.failed(CollectorFailure.SYSTEM, now)
        scanJob = scope.launch {
            runCatching { delay(SCAN_WINDOW_MILLIS) }
            runCatching { scanner.stopScan(callback) }
        }
        delay(SCAN_WINDOW_MILLIS)
        runCatching { scanner.stopScan(callback) }
        scanJob?.cancel()

        val features = discovered.map { CollectorFeature(EvidenceSource.BLUETOOTH, it.key, it.value) }
        if (features.isEmpty()) CollectorResult.failed(CollectorFailure.EMPTY, now)
        else CollectorResult(features, now)
    }.getOrElse { failure ->
        when (failure) {
            is SecurityException -> CollectorResult.failed(CollectorFailure.SECURITY, now)
            else -> CollectorResult.failed(CollectorFailure.SYSTEM, now)
        }
    }

    override fun stop() {
        scanJob?.cancel()
        discovered.clear()
    }

    private fun hasScanPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    /** device.name 需要 BLUETOOTH_CONNECT；缺失时跳过设备名，扫描与哈希照常进行。 */
    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val SCAN_WINDOW_MILLIS = 15_000L
    }
}
