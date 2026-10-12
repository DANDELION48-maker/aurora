package com.aurora.music.data

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MergedLikedSongsTest {
    private class Source(override val session: Session, private val liked: List<Song>, private val extraIds: Set<String>) : MediaBackend {
        override suspend fun ping() = true
        override suspend fun home() = HomeData(starred = liked)
        override suspend fun allAlbums() = emptyList<Album>()
        override suspend fun allArtists() = emptyList<Artist>()
        override suspend fun allPlaylists() = emptyList<Playlist>()
        override suspend fun allSongs() = liked
        override suspend fun starredSongs() = liked
        override suspend fun starredCount() = liked.size
        override suspend fun starredIds() = liked.map { it.id }.toSet() + extraIds
        override suspend fun songFor(id: String) = liked.firstOrNull { it.id == id }
        override suspend fun search(query: String) = SearchResults()
        override suspend fun scrobble(id: String) = Unit
        override suspend fun radio(seedId: String) = emptyList<Song>()
        override suspend fun createPlaylist(name: String) = false
        override suspend fun updatePlaylist(id: String, name: String?, comment: String?) = false
        override suspend fun deletePlaylist(id: String) = false
        override suspend fun setStarred(id: String, starred: Boolean, kind: String) = false
        override suspend fun detail(kind: String, id: String): DetailData? = null
        override suspend fun serverLyrics(song: Song): Lyrics? = null
        override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean) = ""
        override fun coverArtUrl(id: String, size: Int) = ""
    }

    private fun song(id: String, title: String, artist: String, seconds: Int) = Song(id, title, artist, "Album", "", seconds)

    private fun source(name: String, liked: List<Song>, extra: Set<String> = emptySet()) =
        Source(Session("https://$name.invalid", name, "salt", "token", ServerType.SUBSONIC, "user-$name"), liked, extra)

    @Test fun likedCountMatchesTheMergedSongListNotEveryStarredId() = runBlocking {
        val first = source("first", listOf(song("1", "Café Lights", "Tester", 200), song("2", "夏の雨", "テスト", 240), song("3", "冬の星", "テスト", 241)),
            extra = setOf("album-9", "artist-4"))
        val second = source("second", listOf(song("a", "Cafe Lights", "Tester", 201), song("b", "Another Song", "Tester", 180)), extra = setOf("album-7"))
        val merged = MergedBackend(listOf(first, second), first.session)
        val songs = merged.starredSongs()
        assertEquals(4, songs.size)
        assertEquals(songs.size, merged.starredCount())
    }

    @Test fun matchingKeepsOtherScriptsAndFoldsLatinAccents() {
        assertEquals(TrackMatch.key("Beyoncé", "Café"), TrackMatch.key("Beyonce", "Cafe"))
        assertNotEquals(TrackMatch.key("テスト", "夏の雨"), TrackMatch.key("テスト", "冬の星"))
        assertNotEquals(TrackMatch.norm("かき"), TrackMatch.norm("がき"))
        assertEquals(TrackMatch.norm("Song"), TrackMatch.norm("Song (feat. Someone)"))
        assertNotEquals(TrackMatch.identity("!!!"), TrackMatch.identity("…"))
    }
}
