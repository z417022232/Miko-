package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 一个地点已选定的证据源（v4 界面稿「编辑地点 · 证据源」/ DB v11）。
 *
 * ⚠️ 隐私不变量：本表**不存 Wi-Fi BSSID / SSID、蓝牙地址等原始标识**——沿用工程既有的
 * [com.example.worktimetracker.location.evidence.EnvironmentIdentifierHasher] 加盐哈希，
 * 与 `environment_fingerprints` 同一套算法（`hash(salt, ["wifi", ssid, bssid])`、
 * `hash(salt, ["ble", name, address, uuids, mfr])`），所以两边可以直接比对。
 * 原始值只在扫描结果还在内存里时用于展示，落库前一律换哈希。
 *
 * [label] 是**用户自己起的昵称**（可空），不是 SSID。界面上的「已选 N 个」计数由本表行数给出。
 */
@Entity(
    tableName = "site_evidence_sources",
    primaryKeys = ["siteId", "sourceType", "identifierHash"],
    indices = [Index("siteId"), Index(value = ["sourceType", "identifierHash"])]
)
data class SiteEvidenceSourceEntity(
    val siteId: Long,
    /** 与 [com.example.worktimetracker.domain.evidence.EvidenceSource] 名称对齐：WIFI / BLUETOOTH。 */
    val sourceType: String,
    /** 加盐哈希后的标识（64 位十六进制），不是原始 BSSID / 蓝牙地址。 */
    val identifierHash: String,
    /** 用户昵称，可空；不写 SSID / BSSID 原文。 */
    val label: String? = null,
    /** 选择当时的信号强度（dBm），仅作参考展示。 */
    val lastSignal: Int? = null,
    val selectedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_WIFI = "WIFI"
        const val TYPE_BLUETOOTH = "BLUETOOTH"
    }
}
