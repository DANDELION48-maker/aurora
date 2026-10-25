package com.aurora.music.data

import com.aurora.music.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueSheetTest {
    private val sheet = """
        REM GENRE Classical
        REM DATE 1991
        REM REPLAYGAIN_ALBUM_GAIN -4.20 dB
        PERFORMER "Piers Lane"
        TITLE "Romantic Piano Concerto - 1"
        FILE "Romantic Piano Concerto.wav" WAVE
          TRACK 01 AUDIO
            TITLE "Concerto in E: I. Moderato"
            PERFORMER "Moszkowski"
            REM REPLAYGAIN_TRACK_GAIN -3.10 dB
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "Concerto in E: II. Andante"
            INDEX 00 14:58:50
            INDEX 01 15:00:00
          TRACK 03 AUDIO
            TITLE "Concerto in E: III. Scherzo"
            INDEX 01 23:30:37
    """.trimIndent()

    private val whole = Song(
        id = "77", title = "Romantic Piano Concerto - 1", artist = "Moritz Moszkowski", album = "Romantic Piano Concerto - 1",
        artworkUrl = "content://art/5", durationSec = 4343, streamUrl = "content://media/external/audio/media/77", albumId = "5",
        suffix = "flac", path = "/storage/emulated/0/Music/Hyperion/Romantic Piano Concerto.flac",
    )

    @Test fun readsTheAlbumAndEveryTrackStart() {
        val cue = CueSheets.parse(sheet)
        assertEquals("Romantic Piano Concerto - 1", cue.title)
        assertEquals("Piers Lane", cue.performer)
        assertEquals(1991, cue.year)
        assertEquals("Classical", cue.genre)
        assertEquals(-4.2f, cue.albumGainDb)
        assertEquals(listOf(0L, 900_000L, 1_410_493L), cue.tracks.map { it.startMs })
        assertEquals("Moszkowski", cue.tracks[0].performer)
        assertEquals(-3.1f, cue.tracks[0].gainDb)
    }

    @Test fun anAlbumFileBecomesItsTracksPlayedAsRanges() {
        val parts = CueSheets.split(listOf(whole), mapOf(CueSheets.dirKey("/storage/emulated/0/Music/Hyperion") to listOf(CueSheets.parse(sheet))))
        assertEquals(3, parts.size)
        val (first, second, last) = parts.map { it.song }
        assertEquals("Concerto in E: I. Moderato", first.title)
        assertEquals("Moszkowski", first.artist)
        assertEquals("Piers Lane", second.artist)
        assertEquals(900, first.durationSec)
        assertEquals(CueClip(whole.streamUrl, 900_000, 1_410_493), CueClip.of(second.streamUrl))
        assertEquals(CueClip(whole.streamUrl, 1_410_493, null), CueClip.of(last.streamUrl))
        assertEquals(4343 - 1410, last.durationSec)
        assertEquals("5", second.albumId)
        assertEquals(listOf(1, 2, 3), parts.map { it.position })
        assertTrue(parts.all { it.whole.id == "77" && it.song.path == whole.path })
    }

    @Test fun oneTrackPerFileLeavesTheLibraryAlone() {
        val split = """
            FILE "01.flac" WAVE
              TRACK 01 AUDIO
                INDEX 01 00:00:00
            FILE "02.flac" WAVE
              TRACK 02 AUDIO
                INDEX 01 00:00:00
        """.trimIndent()
        val songs = listOf("01", "02").map { whole.copy(id = it, path = "/m/a/$it.flac") }
        assertTrue(CueSheets.split(songs, mapOf(CueSheets.dirKey("/m/a") to listOf(CueSheets.parse(split)))).isEmpty())
    }

    @Test fun folderKeysIgnoreTrailingSlashesAndCase() {
        val parts = CueSheets.split(listOf(whole), mapOf(CueSheets.dirKey("/storage/emulated/0/MUSIC/Hyperion/") to listOf(CueSheets.parse(sheet))))
        assertEquals(3, parts.size)
    }

    @Test fun windowsCodepageSheetsStillRead() {
        val bytes = "TITLE \"Caf\u00e9\"".toByteArray(charset("windows-1252"))
        assertEquals("Caf\u00e9", CueSheets.parse(bytes).title)
        assertEquals("Caf\u00e9", CueSheets.parse("\uFEFFTITLE \"Caf\u00e9\"".toByteArray()).title)
    }

    @Test fun clipUrlsRoundTripAndPlainUrlsAreNotClips() {
        val clip = CueClip("file:///C:/Music/a.flac", 61_234, 120_000)
        assertEquals("file:///C:/Music/a.flac#t=61.234,120.000", clip.url)
        assertEquals(clip, CueClip.of(clip.url))
        assertNull(CueClip.of("file:///C:/Music/a.flac"))
        assertEquals("file:///C:/Music/a.flac", CueClip.source(clip.url))
    }
}
