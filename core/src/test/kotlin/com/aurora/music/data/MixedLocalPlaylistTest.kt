package com.aurora.music.data

import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

class MixedLocalPlaylistTest {
    @get:Rule val temp = TemporaryFolder()

    private val ownSong = Song("l1", "On Disk", "Artist", "Album", "", 200)
    private val serverSong = Song("r1", "On Server", "Artist", "Album", "", 180)

    private val catalog = Proxy.newProxyInstance(LocalCatalog::class.java.classLoader, arrayOf(LocalCatalog::class.java)) { _, method, args ->
        when (method.name) {
            "getSongs" -> listOf(ownSong)
            "song" -> ownSong.takeIf { it.id == args!![0] }
            "ensureLoaded" -> Unit
            else -> error("Unexpected ${method.name}")
        }
    } as LocalCatalog

    private val server = Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, _ ->
        when (method.name) {
            "getSession" -> Session("https://server.invalid", "Listener", "", "token", ServerType.SUBSONIC)
            "allSongs" -> listOf(serverSong)
            "playbackSourceIdentity" -> null
            "playlistsForSong", "allPlaylists" -> listOf(Playlist("server-pl", "Server list", "", "", 0))
            "createPlaylistWithId" -> "made-on-server"
            else -> error("Unexpected ${method.name}")
        }
    } as MediaBackend

    @Test fun aLocalPlaylistHoldsServerSongsOnlyWhileMerged() = runBlocking {
        val store = LocalStore(temp.root)
        val local = LocalBackend(catalog, store, Session("local", "Me", "", "local", ServerType.LOCAL))
        val merged = MergedBackend(listOf(local, server), server.session)
        val songs = merged.allSongs()
        val own = songs.single { it.title == "On Disk" }
        val remote = songs.single { it.title == "On Server" }
        val playlistId = store.createPlaylist("Mix")

        val offered = merged.playlistsForSong(remote.id)
        assertEquals(listOf("Server list", "Mix"), offered.map { it.title })
        val mix = offered.last().id
        assertTrue(merged.addSongsToPlaylist(mix, listOf(remote, own)))
        assertEquals(listOf(remote.id, own.id), merged.detail("playlist", mix)!!.tracks.map { it.id })
        assertEquals(listOf(remote.id, own.id), merged.collectionTracks("playlist", mix).map { it.id })
        assertEquals(listOf("l1"), local.detail("playlist", playlistId)!!.tracks.map { it.id })

        assertTrue(merged.removeFromPlaylist(mix, listOf(remote.id)))
        assertEquals(listOf(own.id), merged.detail("playlist", mix)!!.tracks.map { it.id })
        assertTrue(store.playlist(playlistId)!!.foreign.isNullOrEmpty())
    }

    @Test fun theSelfHostedLibraryLetsYouPickWhereANewPlaylistLives() = runBlocking {
        val store = LocalStore(temp.root)
        val local = LocalBackend(catalog, store, Session("local", "Me", "", "local", ServerType.LOCAL))
        val merged = MergedBackend(listOf(local, server), server.session)
        val targets = merged.playlistTargets
        assertEquals(listOf(ServerType.LOCAL, ServerType.SUBSONIC), targets.map { it.type })
        val id = merged.createPlaylistIn(targets.first().id, "Device list")!!
        assertEquals(listOf("Device list"), store.playlists().map { it.title })
        assertEquals("Device list", merged.detail("playlist", id)!!.info.title)
        assertTrue(merged.createPlaylistIn(targets.last().id, "Server list")!!.endsWith("made-on-server"))
        assertTrue(MergedBackend(listOf(server), server.session).playlistTargets.isEmpty())
    }
}
