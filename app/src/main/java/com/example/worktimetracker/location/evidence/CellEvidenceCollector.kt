package com.example.worktimetracker.location.evidence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.worktimetracker.domain.evidence.EvidenceSource

/**
 * 基站采集器：读取服务小区与相邻小区快照，规范化 LTE/NR/WCDMA/GSM 标识后
 * 哈希 MCC、MNC、区域码与小区标识；信号使用对应 CellSignalStrength 的 dBm。
 */
class CellEvidenceCollector(
    private val context: Context,
    private val saltProvider: () -> ByteArray
) : AmbientCollector {

    override suspend fun snapshot(now: Long): CollectorResult = runCatching {
        if (!hasPermission()) return CollectorResult.failed(CollectorFailure.PERMISSION, now)
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return CollectorResult.failed(CollectorFailure.SYSTEM, now)
        val cells: List<CellInfo> = try {
            telephony.allCellInfo.orEmpty()
        } catch (security: SecurityException) {
            return CollectorResult.failed(CollectorFailure.PERMISSION, now)
        }
        val salt = saltProvider()
        val features = cells.mapNotNull { it.toFeature(salt) }
        if (features.isEmpty()) CollectorResult.failed(CollectorFailure.EMPTY, now)
        else CollectorResult(features, now)
    }.getOrElse { failure ->
        when (failure) {
            is SecurityException -> CollectorResult.failed(CollectorFailure.PERMISSION, now)
            else -> CollectorResult.failed(CollectorFailure.SYSTEM, now)
        }
    }

    override fun stop() = Unit

    private fun CellInfo.toFeature(salt: ByteArray): CollectorFeature? {
        val (fields, dbm) = when (this) {
            is CellInfoLte -> identityFields(
                "lte", cellIdentity.mccString, cellIdentity.mncString,
                cellIdentity.tac.toString(), cellIdentity.ci.toString()
            ) to cellSignalStrength.dbm
            is CellInfoNr -> {
                val nr = cellIdentity as? CellIdentityNr
                    ?: return null
                identityFields(
                    "nr", nr.mccString, nr.mncString,
                    nr.tac.toString(), nr.nci.toString()
                ) to cellSignalStrength.dbm
            }
            is CellInfoWcdma -> identityFields(
                "wcdma", cellIdentity.mccString, cellIdentity.mncString,
                cellIdentity.lac.toString(), cellIdentity.cid.toString()
            ) to cellSignalStrength.dbm
            is CellInfoGsm -> identityFields(
                "gsm", cellIdentity.mccString, cellIdentity.mncString,
                cellIdentity.lac.toString(), cellIdentity.cid.toString()
            ) to cellSignalStrength.dbm
            else -> return null
        }
        if (dbm == Int.MAX_VALUE || dbm == Int.MIN_VALUE) return null
        val hash = EnvironmentIdentifierHasher.hash(salt, fields)
        return CollectorFeature(EvidenceSource.CELL, hash, dbm)
    }

    private fun identityFields(rat: String, mcc: String?, mnc: String?, area: String, cell: String): List<String> =
        listOf("cell", rat, mcc.orEmpty(), mnc.orEmpty(), area, cell)

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
