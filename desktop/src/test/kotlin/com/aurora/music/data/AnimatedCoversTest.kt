package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.ui.components.CoverKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AnimatedCoversTest {
    @get:Rule val temp = TemporaryFolder()

    private fun covers(): Pair<AnimatedCovers, SettingsStore> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SettingsStore(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) {
            File(temp.root, "aurora_settings.preferences_pb") }, temp.root, temp.root)
        return AnimatedCovers(store, scope) to store
    }

    private fun AnimatedCovers.await(folder: String) = runBlocking { config.first { it.folder == folder } }

    private fun webp(animated: Boolean) = ByteArray(30).also { bytes ->
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBP".toByteArray().copyInto(bytes, 8)
        "VP8X".toByteArray().copyInto(bytes, 12)
        bytes[20] = if (animated) 0x02 else 0x00
    }

    @Test fun namedFilesMatchArtistAndAlbumIgnoringCaseAccentsAndPunctuation() {
        val (covers, _) = covers()
        val folder = temp.newFolder("covers")
        File(folder, "Beyoncé - Renaissance.mp4").writeBytes(ByteArray(8))
        File(folder, "Blonde (Deluxe).gif").writeBytes(ByteArray(8))
        File(folder, "Notes.txt").writeText("ignored")
        runBlocking { covers.setFolder(folder) }
        covers.await(folder.absolutePath)
        runBlocking {
            val renaissance = covers.find("RENAISSANCE", "Beyonce", "")!!
            assertTrue(renaissance.video)
            assertEquals("Beyoncé - Renaissance.mp4", renaissance.file.name)
            assertFalse(covers.find("Blonde (Deluxe)", "Frank Ocean", "")!!.video)
            assertNull(covers.find("Notes", "", ""))
            assertNull(covers.find("Unknown", "Nobody", ""))
        }
    }

    @Test fun coversBesideLocalTracksPreferVideoAndSkipStaticWebp() {
        val (covers, _) = covers()
        runBlocking { covers.setFolder(null) }
        val album = temp.newFolder("music", "Album")
        val track = File(album, "01 Song.flac").apply { writeBytes(ByteArray(4)) }
        File(album, "cover.webp").writeBytes(webp(animated = false))
        runBlocking { assertNull(covers.find("Album", "Artist", track.path)) }

        val other = temp.newFolder("music", "Other")
        val otherTrack = File(other, "01.flac").apply { writeBytes(ByteArray(4)) }
        File(other, "folder.webp").writeBytes(webp(animated = true))
        File(other, "Cover.MP4").writeBytes(ByteArray(8))
        runBlocking {
            val found = covers.find("Other", "Artist", otherTrack.path)!!
            assertTrue(found.video)
            assertEquals("Cover.MP4", found.file.name)
        }
    }

    @Test fun turningCoversOffHidesEverything() {
        val (covers, store) = covers()
        val folder = temp.newFolder("covers")
        File(folder, "Album.gif").writeBytes(ByteArray(8))
        runBlocking { covers.setFolder(folder); store.setAnimatedCoversEnabled(false) }
        runBlocking { covers.config.first { !it.enabled && it.folder == folder.absolutePath } }
        runBlocking { assertNull(covers.find("Album", "", "")) }
    }

    @Test fun localTracksExposeTheirFilePath() {
        val file = File(temp.newFolder("Música ü"), "a b#1.flac")
        val song = Song(id = "1", title = "A", artist = "B", album = "C", durationSec = 1, artworkUrl = "",
            streamUrl = file.toPath().toUri().toString())
        assertEquals(file.path, CoverKey.of(song).path)
        assertEquals("", CoverKey.of(song.copy(streamUrl = "https://example.com/a.mp3")).path)
        assertEquals("C", CoverKey.of(song).album)
    }
}
