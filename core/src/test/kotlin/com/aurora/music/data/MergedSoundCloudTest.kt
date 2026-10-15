package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class MergedSoundCloudTest {
    private val soundcloud = Session("https://soundcloud.com", "listener", "", "reference", ServerType.SOUNDCLOUD, userId = "42")
    private val yt = Session("https://music.youtube.com", "Listener", "", "token", ServerType.YOUTUBE_MUSIC, userId = "account")
    private val local = Session("On this device", "Local Library", "", "local", ServerType.LOCAL)

    private val scSong = Song("275215991", "sake bomb", "Topherblaine", "", "", 191, streamUrl = "aurora-sc://track/275215991")
    private val ytSong = Song("youtube-id", "Still love you", "GLM-RST", "Still", "", 146, streamUrl = "aurora-yt://video/youtube-id")
    private val localSong = Song("file", "Local only", "Someone", "Disk", "", 180, streamUrl = "file:///local.flac")

    private fun source(session: Session, songs: List<Song>, searches: MutableList<String> = mutableListOf()): MediaBackend =
        Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, args ->
            when (method.name) {
                "getSession" -> session
                "home" -> HomeData(sections = listOf(HomeFeedSection("shelf", "For you", items = songs.map { HomeFeedItem.Track(it) })))
                "search" -> { searches += args!![0] as String; SearchResults(songs = songs) }
                "allSongs", "librarySongs", "starredSongs", "songsPage", "matchingSongs" -> songs
                "starredCount" -> songs.size
                "allAlbums", "allArtists", "allPlaylists" -> emptyList<Any>()
                "playbackSourceIdentity" -> PlaybackSourceIdentity.fromSession(session, (args!![0] as Song).albumId)
                "downloadUrl" -> "https://cdn.example/${args!![0]}/playlist.m3u8"
                else -> error("Unexpected ${method.name}")
            }
        } as MediaBackend

    @Test fun soundcloudJoinsTheMergeAsItsOwnCatalogue() = runBlocking {
        assertTrue(ServerType.SOUNDCLOUD.supportsMergedLibrary && ServerType.SOUNDCLOUD.isCatalogue)
        val searches = mutableListOf<String>()
        val merged = MergedBackend(listOf(source(local, listOf(localSong)), source(yt, listOf(ytSong)), source(soundcloud, listOf(scSong), searches)), soundcloud)
        assertEquals(listOf("library", "discovery", "soundcloud"), merged.homeFeeds.map { it.id })
        assertEquals(listOf("library", "youtube", "soundcloud"), merged.libraryViews.map { it.id })
        assertEquals(listOf("sake bomb"), merged.home("soundcloud").sections.flatMap { it.items }.map { (it as HomeFeedItem.Track).song.title })
        assertEquals(listOf("sake bomb"), merged.search("bomb", "soundcloud").songs.map { it.title })
        assertEquals(listOf("bomb"), searches)
        // catalogues stay out of the merged library and its likes keep to their own view
        assertEquals(listOf("Local only"), merged.libraryView("library").allSongs().map { it.title })
        assertEquals(listOf("sake bomb"), merged.detail("liked", "soundcloud")!!.tracks.map { it.title })
        // downloads ask the owning source, not the merged play link
        val wrapped = merged.detail("liked", "soundcloud")!!.tracks.single().id
        assertEquals("https://cdn.example/275215991/playlist.m3u8", merged.downloadUrl(wrapped, 0, true))
    }
}
