package com.example.worktimetracker.ui.app

import com.example.worktimetracker.data.entity.SiteEntity

/**
 * 编辑地点页的可变草稿（多地点 / 界面稿 10）。
 *
 * `id == null` 表示新增：保存时主键交给 Room 自增，避免界面层自己发号。
 * `isPrimary` 在主地点唯一这条约束上由 ViewModel 兜底（保存前先清空旧主地点）。
 */
data class SiteDraft(
    val id: Long? = null,
    val name: String = "",
    val siteType: String = SiteEntity.TYPE_WORK,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int = SiteEntity.DEFAULT_RADIUS_METERS,
    val isPrimary: Boolean = false,
    val enabled: Boolean = true
) {
    val gpsReady: Boolean get() = latitude != null && longitude != null

    /** 保存门槛：必须有名字 + 有定位。只有名字没有坐标的地点无法参与判定。 */
    val canSave: Boolean get() = name.isNotBlank() && gpsReady

    val isWork: Boolean get() = siteType == SiteEntity.TYPE_WORK

    companion object {
        fun from(site: SiteEntity): SiteDraft = SiteDraft(
            id = site.id,
            name = site.name,
            siteType = site.siteType,
            latitude = site.latitude,
            longitude = site.longitude,
            radiusMeters = site.radiusMeters,
            isPrimary = site.isPrimary,
            enabled = site.enabled
        )
    }
}

/** 地点列表行：地点本体 + 已声明证据源数量 + 到当前位置的距离。 */
data class SiteRowUi(
    val site: SiteEntity,
    val sourceCount: Int,
    val distanceMeters: Double?
)

/** Wi-Fi 选择页状态。 */
sealed interface WifiScanUi {
    data object Idle : WifiScanUi
    data object Scanning : WifiScanUi

    data class Ready(
        val candidates: List<com.example.worktimetracker.location.evidence.ScannedWifi>,
        val scannedAt: Long
    ) : WifiScanUi

    data class Failed(val message: String) : WifiScanUi
}
