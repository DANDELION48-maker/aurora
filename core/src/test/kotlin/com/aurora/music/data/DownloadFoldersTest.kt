package com.aurora.music.data

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadFoldersTest {
    @get:Rule val folder = TemporaryFolder()

    private fun entry(id: String, label: String?, serverId: String, dir: File, liked: Boolean? = null) = DownloadedSong(
        id, "Song $id", "Artist", "", "", "artist-$id", 120,
        File(dir, "$id.audio").apply { writeText("audio $id") }.absolutePath,
        File(dir, "$id.jpg").apply { writeText("cover $id") }.absolutePath,
        serverId = serverId, playbackSource = label?.let { PlaybackSourceIdentity(providerId = "provider:$it", providerLabel = it) }, liked = liked)

    private fun manager(dir: File) = DownloadManager(dir, { "file://$it" }, { null })

    private fun waitFor(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(check())
    }

    @Test fun sourcesComeFromTheAccountLabelThenTheServer() {
        assertEquals(DownloadSource.SOUNDCLOUD, DownloadSource.of("SoundCloud · soundcloud.com", "https://navidrome.example"))
        assertEquals(DownloadSource.SERVER, DownloadSource.of("Jellyfin · media.example", "https://music.youtube.com"))
        assertEquals(DownloadSource.YOUTUBE, DownloadSource.of(null, "https://music.youtube.com"))
        assertEquals(DownloadSource.SPOTIFY, DownloadSource.of(null, "https://open.spotify.com"))
        assertEquals(DownloadSource.SERVER, DownloadSource.of(null, "http://100.67.239.35:4533"))
        assertEquals(DownloadSource.YOUTUBE, DownloadSource.forView("discovery"))
    }

    @Test fun olderDownloadsMoveIntoTheirSourceFolderOnce() {
        val dir = folder.newFolder("downloads")
        val old = listOf(entry("sc", "SoundCloud · soundcloud.com", "https://soundcloud.com", dir),
            entry("nd", "Navidrome · server", "http://server", dir))
        File(dir, "index.json").writeText(Gson().toJson(old))
        val downloads = manager(dir)
        waitFor { downloads.downloads.value.values.all { File(it.audioPath).parentFile.name != "downloads" } }
        val sc = downloads.get("sc")!!
        assertEquals(File(dir, "soundcloud/sc.audio").absolutePath, sc.audioPath)
        assertEquals(File(dir, "soundcloud/sc.jpg").absolutePath, sc.coverPath)
        assertEquals("audio sc", File(sc.audioPath).readText())
        assertEquals(File(dir, "server/nd.audio").absolutePath, downloads.get("nd")!!.audioPath)
        assertFalse(File(dir, "sc.audio").exists())
    }

    @Test fun offlineShowsAViewPerSourceWithOnlyLikedDownloadsAsLikedSongs() = runBlocking {
        val dir = folder.newFolder("downloads")
        File(dir, "index.json").writeText(Gson().toJson(listOf(
            entry("a", "SoundCloud · soundcloud.com", "https://soundcloud.com", dir, liked = true),
            entry("b", "SoundCloud · soundcloud.com", "https://soundcloud.com", dir),
            entry("c", "Navidrome · server", "http://server", dir, liked = true))))
        val downloads = manager(dir)
        val repository = MusicRepository({ null }, downloads, offlineProvider = { true })
        assertEquals(listOf("library", "soundcloud"), repository.libraryViews.map { it.id })
        assertEquals(listOf("Song a", "Song b"), repository.songsPage(0, 10, "soundcloud").map { it.title })
        assertEquals(1, repository.starredCount("soundcloud"))
        assertEquals(listOf("Song a", "Song c"), repository.detail("liked", "all")!!.tracks.map { it.title })
        assertEquals(listOf("artist-c"), repository.allArtists("library").map { it.id })
    }
}
