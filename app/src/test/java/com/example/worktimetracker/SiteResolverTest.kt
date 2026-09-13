package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.LocationStatusAnalyzer
import com.example.worktimetracker.domain.engine.SitePoint
import com.example.worktimetracker.domain.engine.SiteResolver
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.domain.model.LocationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多地点距离判定护栏（v4.3）。
 *
 * 这些断言锁住三件事：
 * 1. WORK / NON_WORK 必须折算成 COMPANY / HOME 两个锚点，多工作地点不新增状态；
 * 2. 「在半径内」才算命中，靠近但没进圈不算；都在圈内时主地点优先；
 * 3. 数据库没有可用地点时，旧设置（companyLat/homeLat）必须能兜底，
 *    否则老装机升到 v4.3 会突然「一个地点都认不出来」。
 */
class SiteResolverTest {

    // 基准点：lat=30.0, lng=120.0（1° 纬 ≈ 111320m，1° 经 ≈ 96486m @30°N）
    private val baseLat = 30.0
    private val baseLng = 120.0

    private fun work(
        id: Long = 1,
        name: String = "车间",
        lat: Double = baseLat,
        lng: Double = baseLng,
        radius: Int = 150,
        primary: Boolean = true,
        enabled: Boolean = true
    ) = SitePoint(id, name, SiteResolver.TYPE_WORK, lat, lng, radius, primary, enabled)

    private fun home(
        id: Long = 9,
        lat: Double = baseLat,
        lng: Double = baseLng,
        radius: Int = 150,
        enabled: Boolean = true
    ) = SitePoint(id, "家", SiteResolver.TYPE_NON_WORK, lat, lng, radius, false, enabled)

    /** 车间 B 在正北 ~556m 处；家在最东 ~1930m 处 */
    private val workB = work(id = 2, name = "仓库", lat = 30.005, primary = false)
    private val homeFar = home(lng = 120.02)

    // ------------------------------------------------------------------ 语义映射

    @Test
    fun `WORK 折算为 COMPANY，NON_WORK 折算为 HOME`() {
        assertEquals(ResolvedPlace.COMPANY, work().resolvedPlace)
        assertEquals(ResolvedPlace.HOME, home().resolvedPlace)
        assertTrue(work().isWork)
        assertFalse(home().isWork)
    }

    @Test
    fun `未知 siteType 按工作地点处理（保守：宁可计入工时）`() {
        val weird = SitePoint(7, "X", "SOMETHING_ELSE", baseLat, baseLng, 100)
        assertEquals(ResolvedPlace.COMPANY, weird.resolvedPlace)
    }

    // ------------------------------------------------------------------ 最近地点

    @Test
    fun `nearest 按类型取最近，忽略半径`() {
        val sites = listOf(work(), workB)
        val m = SiteResolver.nearest(baseLat, baseLng, sites, SiteResolver.TYPE_WORK)
        assertEquals(1L, m!!.site.id)
        assertTrue(m.distanceMeters < 1.0)
    }

    @Test
    fun `nearest 不受半径限制（半径外也返回，用于算离公司多远）`() {
        val sites = listOf(work())
        // 距车间 ~1.1km，半径只有 150m
        val m = SiteResolver.nearest(30.01, baseLng, sites, SiteResolver.TYPE_WORK)
        assertTrue(m!!.distanceMeters > 1000.0)
        assertFalse(m.withinRadius)
    }

    @Test
    fun `nearest 无坐标时返回 null`() {
        val noGps = SitePoint(3, "空地点", SiteResolver.TYPE_WORK, null, null, 150)
        assertNull(SiteResolver.nearest(baseLat, baseLng, listOf(noGps), SiteResolver.TYPE_WORK))
        assertNull(SiteResolver.nearest(baseLat, baseLng, emptyList(), SiteResolver.TYPE_WORK))
    }

    @Test
    fun `nearest 跳过停用地点`() {
        val disabled = work(id = 5, enabled = false)
        assertNull(SiteResolver.nearest(baseLat, baseLng, listOf(disabled), SiteResolver.TYPE_WORK))
    }

    @Test
    fun `nearestAny 跨类型取最近`() {
        val sites = listOf(work(), homeFar)
        assertEquals(1L, SiteResolver.nearestAny(baseLat, baseLng, sites)!!.site.id)
        assertEquals(9L, SiteResolver.nearestAny(baseLat, 120.02, sites)!!.site.id)
    }

    // ------------------------------------------------------------------ 命中判定

    @Test
    fun `matching 只有进圈才命中`() {
        val sites = listOf(work())
        assertNull(SiteResolver.matching(30.01, baseLng, sites))            // ~1.1km，圈外
        assertTrue(SiteResolver.matching(30.0005, baseLng, sites) != null)  // ~56m，圈内
    }

    @Test
    fun `matching 半径边界按闭区间（恰好等于半径算命中）`() {
        val site = work(radius = 1000)
        // 0.00898° 纬 ≈ 999.6m，稳稳在 1000m 圈内；再往外 0.0092° ≈ 1024m 出圈
        assertTrue(SiteResolver.matching(baseLat + 0.00898, baseLng, listOf(site)) != null)
        assertNull(SiteResolver.matching(baseLat + 0.0092, baseLng, listOf(site)))
    }

    @Test
    fun `matching 多个都在圈内时主地点优先`() {
        val a = work(id = 1, name = "车间", lat = baseLat, lng = baseLng, radius = 800, primary = true)
        val b = work(id = 2, name = "仓库", lat = 30.002, lng = baseLng, radius = 800, primary = false)
        // 点离 B 更近（~111m）但离 A 也在圈内（~222m）→ 主地点 A 胜出
        val m = SiteResolver.matching(30.001, baseLng, listOf(a, b))
        assertEquals(1L, m!!.site.id)
    }

    @Test
    fun `matching 都不是主地点时取更近者`() {
        val a = work(id = 1, lat = baseLat, lng = baseLng, radius = 800, primary = false)
        val b = work(id = 2, lat = 30.002, lng = baseLng, radius = 800, primary = false)
        assertEquals(2L, SiteResolver.matching(30.001, baseLng, listOf(a, b))!!.site.id)
    }

    // ------------------------------------------------------------------ 地理分类

    @Test
    fun `classify 命中工作地点为 COMPANY`() {
        assertEquals(
            LocationType.COMPANY,
            SiteResolver.classify(baseLat, baseLng, listOf(work(), homeFar))
        )
    }

    @Test
    fun `classify 命中非工作地点为 HOME`() {
        assertEquals(
            LocationType.HOME,
            SiteResolver.classify(baseLat, 120.02, listOf(work(), homeFar))
        )
    }

    @Test
    fun `classify 都不命中为 OTHER`() {
        assertEquals(
            LocationType.OTHER,
            SiteResolver.classify(31.5, 121.5, listOf(work(), homeFar))
        )
    }

    /** 多地点核心语义：离开了车间 A，但人还在车间 B 圈内，仍然算在岗。 */
    @Test
    fun `多工作地点 任一命中即为 COMPANY`() {
        val sites = listOf(work(id = 1, radius = 150), workB.copy(radiusMeters = 150))
        // 人在车间 B 上
        assertEquals(LocationType.COMPANY, SiteResolver.classify(30.005, baseLng, sites))
        // 中间地带（离两边都 > 150m）→ OTHER
        assertEquals(LocationType.OTHER, SiteResolver.classify(30.0035, baseLng, sites))
        // 回车间 A → 仍是 COMPANY
        assertEquals(LocationType.COMPANY, SiteResolver.classify(baseLat, baseLng, sites))
    }

    @Test
    fun `classify 空地点集合为 OTHER`() {
        assertEquals(LocationType.OTHER, SiteResolver.classify(baseLat, baseLng, emptyList()))
    }

    // ------------------------------------------------------------------ 生效地点（兜底）

    @Test
    fun `effective 数据库地点可用时不合成兜底`() {
        val sites = listOf(work(), homeFar)
        val eff = SiteResolver.effective(sites, 31.0, 121.0, 200, 32.0, 122.0, 300)
        assertEquals(2, eff.size)
        assertTrue(eff.none { it.id == SitePoint.SYNTHETIC_ID })
    }

    @Test
    fun `effective 没有工作地点坐标时由旧设置合成公司`() {
        val eff = SiteResolver.effective(emptyList(), 31.0, 121.0, 200, null, null, 0)
        val synth = eff.first { it.id == SitePoint.SYNTHETIC_ID }
        assertEquals(31.0, synth.latitude!!, 1e-9)
        assertEquals(121.0, synth.longitude!!, 1e-9)
        assertEquals(200, synth.radiusMeters)
        assertTrue(synth.isPrimary)
        assertEquals(ResolvedPlace.COMPANY, synth.resolvedPlace)
    }

    @Test
    fun `effective 没有非工作地点坐标时由旧设置合成家`() {
        val eff = SiteResolver.effective(listOf(work()), null, null, 0, 32.0, 122.0, 300)
        val synth = eff.first { it.id == SitePoint.SYNTHETIC_ID_HOME }
        assertEquals(ResolvedPlace.HOME, synth.resolvedPlace)
        assertEquals(300, synth.radiusMeters)
    }

    @Test
    fun `effective 旧设置也没有坐标时不合成任何地点`() {
        assertTrue(SiteResolver.effective(emptyList(), null, null, 150, null, null, 150).isEmpty())
    }

    @Test
    fun `effective 忽略停用地点（停用后走兜底）`() {
        val eff = SiteResolver.effective(
            listOf(work(enabled = false)),
            31.0, 121.0, 200, null, null, 0
        )
        assertTrue(eff.none { it.enabled && it.id == 1L })
        assertTrue(eff.any { it.id == SitePoint.SYNTHETIC_ID })
    }

    @Test
    fun `effective 有工作地点但缺非工作地点时只补家`() {
        val eff = SiteResolver.effective(listOf(work()), 31.0, 121.0, 200, 32.0, 122.0, 300)
        assertEquals(2, eff.size)
        assertTrue(eff.any { it.id == 1L })
        assertTrue(eff.any { it.id == SitePoint.SYNTHETIC_ID_HOME })
    }

    // ------------------------------------------------------------------ 列表展示

    @Test
    fun `distanceTo 缺坐标返回 null`() {
        assertNull(SiteResolver.distanceTo(null, 120.0, work()))
        assertNull(SiteResolver.distanceTo(baseLat, null, work()))
        assertNull(SiteResolver.distanceTo(baseLat, baseLng, SitePoint(4, "空", SiteResolver.TYPE_WORK, null, null, 100)))
    }

    @Test
    fun `distanceTo 与最近判定同口径`() {
        val d = SiteResolver.distanceTo(baseLat, baseLng, work())!!
        assertTrue(d < 1.0)
        val far = SiteResolver.distanceTo(30.01, baseLng, work())!!
        assertTrue(far > 1000.0)
    }

    @Test
    fun `距离计算与 LocationStatusAnalyzer 一致`() {
        val expected = LocationStatusAnalyzer().distanceMeters(baseLat, baseLng, 30.005, baseLng)
        val actual = SiteResolver.distanceTo(baseLat, baseLng, workB)!!
        assertEquals(expected, actual, 1e-6)
    }
}
