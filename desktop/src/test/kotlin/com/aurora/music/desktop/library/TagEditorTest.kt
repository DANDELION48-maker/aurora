package com.aurora.music.desktop.library

import com.aurora.music.data.AudioTags
import com.aurora.music.data.Chromaprint
import com.aurora.music.desktop.audio.decode.FfmpegDecoder
import com.aurora.music.desktop.audio.decode.TestAssets
import com.aurora.music.desktop.audio.decode.decodeAll
import com.aurora.music.desktop.audio.decode.encodedTone
import com.aurora.music.desktop.audio.decode.png
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TagEditorTest {
    @get:Rule val temp = TemporaryFolder()

    private val tags = AudioTags(title = "Night Signal", artist = "Aurora & Friends", album = "Ünïcode Album", albumArtist = "Aurora",
        genre = "Synthwave", year = "2026", trackNumber = "7")

    private fun roundTrip(fixture: String) = roundTrip(TestAssets.file(fixture).copyTo(temp.newFile(fixture), overwrite = true))

    private fun roundTrip(file: File) = runBlocking {
        val before = FfmpegDecoder.open(file.path).use { decodeAll(it) }
        val editor = TagEditor()
        assertTrue(editor.write(file.path, tags, png()))
        assertEquals(tags, editor.read(file.path))
        val probed = FfmpegDecoder.probe(file)
        assertEquals("Night Signal", probed.tags.title)
        assertEquals("Aurora & Friends", probed.tags.artist)
        assertEquals("Ünïcode Album", probed.tags.album)
        assertEquals(7, probed.tags.track)
        assertNotNull(probed.cover)
        val after = FfmpegDecoder.open(file.path).use { decodeAll(it) }
        assertTrue(before.contentEquals(after))
        assertFalse(temp.root.listFiles().orEmpty().any { it.name.startsWith(".aurora-tags-") })

        assertTrue(editor.write(file.path, tags.copy(genre = "", year = ""), null))
        val cleared = editor.read(file.path)!!
        assertEquals("", cleared.genre)
        assertEquals("", cleared.year)
        assertEquals("Night Signal", cleared.title)
    }

    @Test fun writesFlacTagsWithoutTouchingTheAudio() = roundTrip("lowbits-24.flac")

    @Test fun writesMp3TagsWithoutTouchingTheAudio() = roundTrip("gapless.mp3")

    @Test fun writesM4aTagsWithoutTouchingTheAudio() = roundTrip(encodedTone(temp.newFile("tone.m4a"), "ipod", "aac"))

    @Test fun missingFilesFailCleanly() = runBlocking {
        val editor = TagEditor()
        assertFalse(editor.write(temp.root.resolve("missing.flac").path, tags))
        assertEquals(null, editor.read(temp.root.resolve("missing.flac").path))
    }

    @Test fun fingerprintsADecodedFile() {
        val fingerprint = Chromaprint.fingerprint(TestAssets.file("lowbits-24.flac").path)
        assertTrue(fingerprint.orEmpty().startsWith("AQA"))
    }
}
