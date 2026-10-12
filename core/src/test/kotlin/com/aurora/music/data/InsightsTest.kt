package com.aurora.music.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneOffset

class InsightsTest {
    private val utc = ZoneOffset.UTC
    private val monday = LocalDateTime.of(2026, 9, 7, 0, 0)

    private fun at(day: Int, hour: Int, minute: Int = 0) = monday.plusDays(day.toLong()).withHour(hour).withMinute(minute).toInstant(utc).toEpochMilli()

    private fun play(id: String, start: Long, minutes: Long, artist: String = "Artist", genre: String? = "Ambient",
        ending: PlayEnding? = PlayEnding.COMPLETED, context: PlayContext = PlayContext()) = DetailedPlay(
        "$id-$start", start, start + minutes * 60_000, minutes * 60_000, PlayedTrack(id, id, artist, "Album", "al", "ar", (minutes * 60).toInt()),
        context.copy(genre = context.genre ?: genre), ending)

    @Test
    fun reportCountsTimeHeatmapSessionsAndEndings() {
        val plays = listOf(
            play("a", at(0, 22), 4),
            play("b", at(0, 22, 5), 3, genre = "Shoegaze", ending = PlayEnding.SKIPPED),
            play("c", at(2, 9), 5, artist = "Newcomer"),
        )
        val report = Insights.build(plays, mapOf("artist" to 0L, "newcomer" to at(2, 9)), from = at(0, 0), zone = utc)
        assertEquals(12L, report.minutes)
        assertEquals(3, report.plays)
        assertEquals(2, report.sessions)
        assertEquals(7L * 60_000, report.heatmap[0][22])
        assertEquals(22, report.peakHour)
        assertEquals(1, report.newArtists)
        assertEquals(2f / 3, report.completion!!, 1e-4f)
        assertEquals(1f / 3, report.skips!!, 1e-4f)
        assertEquals(listOf("Ambient", "Shoegaze"), report.genres.map { it.label })
        assertTrue(report.badges.isEmpty())
    }

    @Test
    fun audioPathAndOutputSharesAreWeightedByTime() {
        val plays = listOf(
            play("a", at(1, 10), 30, context = PlayContext(output = "USB", lossless = true, sampleRateHz = 96_000, codec = "audio/flac")),
            play("b", at(1, 11), 10, context = PlayContext(output = "BLUETOOTH", lossless = false, sampleRateHz = 44_100, codec = "audio/mp4a-latm")),
        )
        val report = Insights.build(plays, emptyMap(), from = 0, zone = utc)
        assertEquals(.75f, report.lossless!!, 1e-4f)
        assertEquals(.75f, report.hiRes!!, 1e-4f)
        assertEquals("USB", report.outputs.first().label)
        assertEquals(listOf("FLAC", "AAC"), report.formats.map { it.label })
        assertNull(report.autoEq)
    }

    @Test
    fun placesBadgesAndSlices() {
        val plays = List(12) { i ->
            play("s$i", at(i % 7, 23), 10, genre = if (i % 2 == 0) "Ambient" else "Shoegaze",
                context = PlayContext(zone = if (i < 9) PlaceZones.HOME else PlaceZones.WORK, shuffle = false))
        }
        val report = Insights.build(plays, emptyMap(), from = 0, zone = utc)
        assertEquals(PlaceZones.HOME, report.zones.first().zone)
        assertEquals(.75f, report.zones.first().fraction, 1e-4f)
        assertTrue(report.badges.any { it.kind == InsightBadge.NIGHT_LISTENER && it.value == 100 })
        assertTrue(report.transitions.isNotEmpty())
        val slice = Insights.slice(plays, utc) { day, hour -> day == 0 && hour == 23 }
        assertEquals(20L, slice.minutes)
    }

    @Test
    fun zonesUseTheNearestSavedPlaceElseAway() {
        val places = Places(home = Place(52.0, 5.0, 200), work = Place(52.01, 5.0, 200))
        assertEquals(PlaceZones.HOME, PlaceZones.zoneFor(52.0005, 5.0, 20f, places))
        assertEquals(PlaceZones.WORK, PlaceZones.zoneFor(52.0101, 5.0, 20f, places))
        assertEquals(PlaceZones.AWAY, PlaceZones.zoneFor(52.1, 5.1, 20f, places))
        assertNull(PlaceZones.zoneFor(52.0, 5.0, 20f, Places()))
    }

    private val root: File = Files.createTempDirectory("aurora-insights").toFile()
    @After fun tearDown() { root.deleteRecursively() }

    @Test
    fun jsonLinesStoreKeepsTheNewestRowAndSurvivesReloadAndCompaction() = runBlocking {
        val file = File(root, "details.jsonl")
        val store = JsonLinesDetailedListeningStore(file, "windows")
        val first = play("a", at(0, 9), 1, ending = null)
        store.save(first)
        store.save(first.copy(listenedMs = 90_000, ending = PlayEnding.COMPLETED))
        assertEquals(1, store.summary().plays)
        val reloaded = JsonLinesDetailedListeningStore(file, "windows")
        assertEquals(90_000L, reloaded.plays(0, Long.MAX_VALUE).single().listenedMs)
        val history = List(1_200) { PlayEvent("h$it", "t", "Artist", "b", "c", "d", "", 200, 1_000L + it, 60_000) }
        assertEquals(1_200, reloaded.importHistory(history))
        assertEquals(1_200, reloaded.importHistory(history))
        assertEquals(1_201, reloaded.summary().plays)
        assertTrue(file.readLines().size <= 1_201 * 2)
        assertEquals(1_000L, reloaded.firstArtistPlays()["artist"])
        reloaded.clear()
        assertEquals(0, JsonLinesDetailedListeningStore(file, "windows").summary().plays)
    }
}
