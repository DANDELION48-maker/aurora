package com.aurora.music.data

import com.aurora.music.data.remote.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SoundCloudBackendTest {
    private val session = Session(SoundCloudClient.WEB, "listener", "", "opaque-reference", ServerType.SOUNDCLOUD, "42")
    private fun track(id: Int, title: String = "Track $id") =
        """{"id":$id,"title":"$title","duration":30000,"full_duration":322606,"artwork_url":"https://i1.sndcdn.com/artworks-a-large.jpg","user":{"id":7,"username":"uploader","avatar_url":"https://i1.sndcdn.com/avatars-b-large.jpg"},"publisher_metadata":{"artist":"Real Artist","album_title":"Record","explicit":true}}"""
    private fun stub(id: Int) = """{"id":$id,"kind":"track","policy":"ALLOW"}"""

    @Test fun mapsTracksWithPublisherArtistFullLengthAndLargeArt() {
        val song = SoundCloudBackend.song(JsonParser.parseString(track(5)).asJsonObject)!!
        assertEquals("Real Artist", song.artist)
        assertEquals("Record", song.album)
        assertEquals(322, song.durationSec)
        assertEquals("https://i1.sndcdn.com/artworks-a-t500x500.jpg", song.artworkUrl)
        assertEquals("aurora-sc://track/5", song.streamUrl)
        assertEquals("7", song.artistId)
        assertTrue(song.explicit)
        assertNull(SoundCloudBackend.song(JsonParser.parseString(stub(6)).asJsonObject))
    }

    @Test fun playlistsHydrateIdStubsInOrder() = runBlocking {
        val asked = mutableListOf<String>()
        val backend = SoundCloudBackend(session, SoundCloudTransport { path ->
            asked += path
            JsonParser.parseString(when {
                path.startsWith("/playlists/9") -> """{"id":9,"title":"Mix","is_album":false,"user":{"id":42,"username":"listener"},"tracks":[${track(1)},${stub(2)},${track(3)},${stub(4)}]}"""
                path.startsWith("/tracks?ids=") -> "[${track(4)},${track(2)}]"
                else -> error(path)
            })
        })
        val detail = backend.detail("playlist", "9")!!
        assertEquals(listOf("1", "2", "3", "4"), detail.tracks.map { it.id })
        assertTrue(detail.info.editable)
        assertEquals(listOf("/playlists/9", "/tracks?ids=2,4"), asked)
    }

    @Test fun libraryFollowsEveryPageAndSplitsAlbumsFromPlaylists() = runBlocking {
        val backend = SoundCloudBackend(session, SoundCloudTransport { path ->
            JsonParser.parseString(when (path) {
                "/me/library/all?limit=200" -> """{"collection":[{"type":"playlist","playlist":{"id":1,"title":"Mine","track_count":3,"user":{"username":"listener"}}}],"next_href":"https://api-v2.soundcloud.com/me/library/all?offset=1"}"""
                "https://api-v2.soundcloud.com/me/library/all?offset=1" -> """{"collection":[{"type":"album-like","playlist":{"id":2,"title":"LP","is_album":true,"track_count":9,"release_date":"2001-03-12T00:00:00Z","user":{"username":"band"}}},{"type":"system-playlist-like","system_playlist":{"urn":"soundcloud:system-playlists:weekly:42","title":"Weekly","tracks":[{"id":1}]}}],"next_href":null}"""
                else -> error(path)
            })
        })
        assertEquals(listOf("1", "soundcloud:system-playlists:weekly:42"), backend.allPlaylists().map { it.id })
        assertEquals(listOf(2001), backend.allAlbums().map { it.year })
    }

    @Test fun nextLinksStayOnTheApiHost() {
        assertEquals("api-v2.soundcloud.com", SoundCloudClient.url("/me").host)
        assertThrows(IllegalArgumentException::class.java) { SoundCloudClient.url("https://evil.example/me") }
        assertThrows(IllegalArgumentException::class.java) { SoundCloudClient.url("/me/../oauth") }
    }

    @Test fun sessionReadsTheTokenExpiryAndRotatesCookies() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":1792103236}""".toByteArray())
        val web = SoundCloudWebSession("sc_anonymous_id=x; oauth_token=h.$payload.s; oauth_refresh_token=old", "UA")
        assertEquals(1792103236L, web.expiresAt)
        assertTrue(web.canRefresh)
        val next = web.refreshed(listOf("oauth_token=fresh; Path=/; Secure", "oauth_refresh_token=new; HttpOnly", "sc_anonymous_id=; Max-Age=0"))
        assertEquals("fresh", next.token)
        assertEquals("oauth_token=fresh; oauth_refresh_token=new", next.cookie)
        assertEquals(next.cookie, SoundCloudWebSession.decode(next.encode()).cookie)
    }
}
