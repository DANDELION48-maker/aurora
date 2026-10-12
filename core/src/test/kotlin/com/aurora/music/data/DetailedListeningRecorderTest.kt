package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailedListeningRecorderTest {
    private val saved = mutableListOf<DetailedPlay>()
    private var now = 1_000_000L
    private var uptime = 0L
    private val recorder = DetailedListeningRecorder({ saved += it }, clock = { now }).also { it.allowed = true }

    private fun track(id: String, seconds: Int) = PlayedTrack(id, "Title $id", "Artist", "Album", "al", "ar", seconds)

    private fun play(track: PlayedTrack?, fromSec: Int, toSec: Int, context: PlayContext = PlayContext()) {
        for (s in fromSec..toSec) {
            recorder.track(track, true, s * 1000L, uptime) { context }
            uptime += 1000
            now += 1000
        }
    }

    private fun finals() = saved.filter { it.ending != null }

    @Test
    fun finishedTrackIsCompleted() {
        play(track("a", 10), 0, 9)
        play(track("b", 200), 0, 1)
        val a = finals().single()
        assertEquals("a", a.track.songId)
        assertEquals(PlayEnding.COMPLETED, a.ending)
        assertEquals(9_000L, a.listenedMs)
    }

    @Test
    fun earlyChangeIsASkipAndLongPlaysAreCheckpointed() {
        play(track("a", 200), 0, 20)
        assertTrue(saved.any { it.track.songId == "a" && it.ending == null })
        play(track("b", 200), 0, 1)
        val a = finals().single()
        assertEquals(PlayEnding.SKIPPED, a.ending)
        assertEquals(saved.first().id, a.id)
    }

    @Test
    fun crossfadeHandoverCountsAsFinished() {
        val faded = DetailedListeningRecorder({ saved += it }, clock = { now }, crossfadeMs = { 8_000L }).also { it.allowed = true }
        for (s in 100..190) { faded.track(track("a", 200), true, s * 1000L, uptime) { PlayContext() }; uptime += 1000; now += 1000 }
        faded.track(track("b", 200), true, 0L, uptime) { PlayContext() }
        assertEquals(PlayEnding.COMPLETED, finals().single().ending)
    }

    @Test
    fun hearingNearlyAllOfATrackCountsAsFinishedEvenAfterSeeking() {
        play(track("a", 100), 0, 91)
        play(track("a", 100), 30, 31)
        recorder.finish()
        assertEquals(PlayEnding.COMPLETED, finals().single().ending)
    }

    @Test
    fun repeatOneStartsANewCompletedRow() {
        val a = track("a", 10)
        play(a, 0, 9)
        play(a, 0, 3)
        val first = finals().single()
        assertEquals(PlayEnding.COMPLETED, first.ending)
        recorder.finish()
        assertEquals(2, finals().size)
        assertNotEquals(first.id, finals().last().id)
    }

    @Test
    fun turningOffStopsTheCurrentPlayAndRecordsNothingMore() {
        play(track("a", 200), 0, 5)
        recorder.allowed = false
        assertEquals(PlayEnding.STOPPED, finals().single().ending)
        play(track("a", 200), 6, 30)
        assertEquals(1, saved.size)
    }

    @Test
    fun radioAndMixesAreIgnored() {
        play(track("radio:station", 0), 0, 30)
        play(track("aurora-mix:1", 0), 0, 30)
        recorder.finish()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun laterSnapshotsFillMissingContext() {
        val a = track("a", 200)
        play(a, 0, 2, PlayContext(genre = "Ambient"))
        play(a, 3, 20, PlayContext(output = "BLUETOOTH"))
        recorder.finish()
        val row = finals().single()
        assertEquals("Ambient", row.context.genre)
        assertEquals("BLUETOOTH", row.context.output)
    }

    @Test
    fun losslessIsReadFromTheCodec() {
        assertEquals(true, PlayContext.losslessCodec("audio/flac"))
        assertEquals(false, PlayContext.losslessCodec("audio/mpeg"))
        assertEquals(null, PlayContext.losslessCodec(""))
    }
}
