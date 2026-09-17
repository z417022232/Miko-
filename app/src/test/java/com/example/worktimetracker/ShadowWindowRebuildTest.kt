package com.example.worktimetracker

import com.example.worktimetracker.data.entity.EnvironmentFingerprintEntity
import com.example.worktimetracker.data.entity.PlaceAnchorCandidateEntity
import com.example.worktimetracker.location.service.ShadowWindow
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 影子窗口的重建规则（`ShadowWindow`）。
 *
 * 这条规则不显眼但决定成败：**同一天的多行只算一天**（取当天最后一条）。
 * `ShadowValidator` 的「窗口内每一天都必须有有效样本」完全建立在它之上，
 * 而学习侧与展示侧共用这一份实现 —— 各写一份的话，两边迟早对同一个窗口给出不同的天数，
 * 表现为「页面上说已经 7 天，但就是不生效」。这种不一致不会报错，只会让人反复怀疑算法。
 */
class ShadowWindowRebuildTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val day0 = LocalDate.of(2026, 9, 1)

    private fun millis(day: LocalDate, hour: Int) =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun row(
        id: Long,
        day: LocalDate,
        hour: Int = 12,
        lat: Double = 31.0,
        ambient: Int = 2,
        spread: Double? = 10.0,
        firstSeenAt: Long = millis(day0, 8)
    ) = PlaceAnchorCandidateEntity(
        id = id,
        placeId = 1L,
        centerLat = lat,
        centerLng = 121.0,
        sampleCount = 10,
        distinctDayCount = 3,
        ambientSourceCount = ambient,
        stableMillis = 60_000L,
        firstSeenAt = firstSeenAt,
        lastSeenAt = millis(day, hour),
        offsetMeters = 5.0,
        status = "SHADOW",
        spreadP90Meters = spread
    )

    private fun fingerprint(place: String, hash: String, observedAt: Long) = EnvironmentFingerprintEntity(
        place = place,
        source = "wifi",
        identifierHash = hash,
        observationCount = 5,
        distinctDayCount = 3,
        lastObservedDay = "2026-09-02",
        lastObservedAt = observedAt,
        minSignal = -70,
        maxSignal = -50,
        level = "STABLE",
        discriminative = true
    )

    // ---------------------------------------------------------------- 重建

    @Test fun severalRowsOnTheSameDayCountAsOneDay() {
        // 这是最关键的一条：同一天插了多行（历史数据 / 状态变化）也只算一天，
        // 否则「每天都有样本」这个条件会被同一天的重复行骗过去。
        val rows = listOf(
            row(id = 1, day = day0, hour = 9),
            row(id = 2, day = day0, hour = 15),
            row(id = 3, day = day0, hour = 21)
        )
        val observations = ShadowWindow.observations(rows, zone)
        assertEquals(1, observations.size)
        assertEquals(day0, observations.first().day)
    }

    @Test fun theLastRowOfTheDayWins() {
        val rows = listOf(
            row(id = 1, day = day0, hour = 9, ambient = 1, spread = 90.0),
            row(id = 2, day = day0, hour = 21, ambient = 3, spread = 8.0)
        )
        val observation = ShadowWindow.observations(rows, zone).single()
        assertEquals(3, observation.ambientSources)
        assertEquals(8.0, observation.spreadP90Meters!!, 1e-9)
    }

    @Test fun daysAreReturnedInAscendingOrderNoMatterTheInputOrder() {
        val rows = listOf(
            row(id = 1, day = day0.plusDays(2), hour = 12),
            row(id = 2, day = day0, hour = 12),
            row(id = 3, day = day0.plusDays(1), hour = 12)
        )
        val days = ShadowWindow.observations(rows, zone).map { it.day }
        assertEquals(listOf(day0, day0.plusDays(1), day0.plusDays(2)), days)
    }

    @Test fun aMissingSpreadReadingStaysNullThroughTheRebuild() {
        // null 一路透到 ShadowValidator，不许在重建时被压成 0（0 = 完美集中）。
        val rows = listOf(row(id = 1, day = day0, spread = null))
        assertNull(ShadowWindow.observations(rows, zone).single().spreadP90Meters)
    }

    @Test fun anEmptyWindowRebuildsToNothing() {
        assertTrue(ShadowWindow.observations(emptyList(), zone).isEmpty())
    }

    @Test fun theZoneDecidesWhichDayARowBelongsTo() {
        // 23:30 UTC 在 Asia/Shanghai 已经是第二天 —— 自然日归属必须跟着 zone 走，
        // 否则跨时区（或设备时区被改）时窗口天数会对不上。
        val utc = ZoneId.of("UTC")
        val lateUtc = LocalDate.of(2026, 9, 1).atTime(23, 30).atZone(utc).toInstant().toEpochMilli()
        val shanghaiDay = ShadowWindow.dayOf(lateUtc, zone)
        val utcDay = ShadowWindow.dayOf(lateUtc, utc)
        assertEquals(LocalDate.of(2026, 9, 2), shanghaiDay)
        assertEquals(LocalDate.of(2026, 9, 1), utcDay)
    }

    // ------------------------------------------------------------ 指纹冲突

    @Test fun theSameIdentifierSupportingTwoPlacesIsOneConflict() {
        val start = millis(day0, 0)
        val conflicts = ShadowWindow.conflicts(
            listOf(
                fingerprint("COMPANY", "hash-a", start + 1000),
                fingerprint("HOME", "hash-a", start + 2000)
            ),
            windowStart = start
        )
        assertEquals(1, conflicts)
    }

    @Test fun fingerprintsObservedBeforeTheWindowDoNotCount() {
        // 历史脏数据不许把新窗口一票否决：只看窗口内仍活跃的指纹。
        val start = millis(day0, 0)
        val conflicts = ShadowWindow.conflicts(
            listOf(
                fingerprint("COMPANY", "hash-a", start - 60_000),
                fingerprint("HOME", "hash-a", start - 60_000)
            ),
            windowStart = start
        )
        assertEquals(0, conflicts)
    }

    @Test fun severalIdentifiersSupportingDifferentPlacesStillCountPerIdentifier() {
        val start = millis(day0, 0)
        val conflicts = ShadowWindow.conflicts(
            listOf(
                fingerprint("COMPANY", "hash-a", start + 1),
                fingerprint("HOME", "hash-a", start + 2),
                fingerprint("COMPANY", "hash-b", start + 3),
                fingerprint("HOME", "hash-b", start + 4),
                fingerprint("COMPANY", "hash-c", start + 5)
            ),
            windowStart = start
        )
        assertEquals("hash-c 只支持一个地点，不算冲突", 2, conflicts)
    }

    @Test fun noFingerprintsMeansNoConflicts() {
        assertEquals(0, ShadowWindow.conflicts(emptyList(), windowStart = 0L))
    }
}
