package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

class PlaylistImportTest {
    @get:Rule val temp = TemporaryFolder()

    private fun song(id: String, title: String, artist: String, seconds: Int, url: String = "stream://$id") = Song(id, title, artist, "", "", seconds, streamUrl = url)

    @Test fun exportedListsFromEveryAppRead() {
        val exportify = "\"Track URI\",\"Track Name\",\"Artist Name(s)\",\"Album Name\",\"Duration (ms)\"\n" +
            "\"spotify:track:1\",\"Hey Jude - Remastered 2015\",\"The Beatles\",\"1\",\"429000\"\n" +
            "\"spotify:track:2\",\"Under Pressure\",\"Queen, David Bowie\",\"Hot Space\",\"248000\"\n"
        val a = PlaylistFiles.read("Road Trip.csv", exportify.toByteArray())
        assertEquals("Road Trip", a.name)
        assertEquals(listOf(ImportTrack("Hey Jude - Remastered 2015", "The Beatles", "1", 429), ImportTrack("Under Pressure", "Queen, David Bowie", "Hot Space", 248)), a.tracks)

        val apple = "Name\tArtist\tComposer\tAlbum\tTime\r\nTitanium\tDavid Guetta\t\tNothing but the Beat\t245\r\n"
        val b = PlaylistFiles.read("Workout.txt", ("﻿" + apple).toByteArray(Charsets.UTF_16LE).let { byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + it.drop(2) })
        assertEquals(listOf(ImportTrack("Titanium", "David Guetta", "Nothing but the Beat", 245)), b.tracks)

        val tunemymusic = "Track name,Artist name,Album,Playlist name,Type,ISRC\nYellow,Coldplay,Parachutes,Chill,Playlist,GBAYE0000351\n"
        assertEquals("Chill", PlaylistFiles.read("export.csv", tunemymusic.toByteArray()).name)

        val text = "1. Daft Punk - One More Time\nRadiohead – Creep\nJust A Title\n"
        assertEquals(listOf(ImportTrack("One More Time", "Daft Punk"), ImportTrack("Creep", "Radiohead"), ImportTrack("Just A Title")),
            PlaylistFiles.read("list.txt", text.toByteArray()).tracks)

        val xspf = "<?xml version=\"1.0\"?><playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\"><title>Mix &amp; Match</title><trackList>" +
            "<track><title>Song 2</title><creator>Blur</creator><duration>122000</duration></track></trackList></playlist>"
        val x = PlaylistFiles.read("x.xspf", xspf.toByteArray())
        assertEquals("Mix & Match", x.name)
        assertEquals(listOf(ImportTrack("Song 2", "Blur", durationSec = 122)), x.tracks)

        val m3u = "#EXTM3U\n#EXTINF:200,Blur - Parklife\nMusic/Blur/Parklife.mp3\n"
        assertEquals(listOf(ImportTrack("Parklife", "Blur", durationSec = 200)), PlaylistFiles.read("p.m3u8", m3u.toByteArray()).tracks)
    }

    @Test fun linksFromEveryAppAreRecognised() {
        val links = PlaylistLinks(OkHttpClient())
        fun check(url: String, service: PlaylistLinks.Service, kind: String, id: String) {
            val link = links.parse(url) ?: error("not recognised: $url")
            assertEquals(url, service to kind, link.service to link.kind)
            if (id.isNotEmpty()) assertEquals(url, id, link.id)
        }
        check("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc", PlaylistLinks.Service.SPOTIFY, "playlist", "37i9dQZF1DXcBWIGoYBM5M")
        check("https://open.spotify.com/intl-de/album/4aawyAB9vmqN3uQ7FjRGTy", PlaylistLinks.Service.SPOTIFY, "album", "4aawyAB9vmqN3uQ7FjRGTy")
        check("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", PlaylistLinks.Service.SPOTIFY, "playlist", "37i9dQZF1DXcBWIGoYBM5M")
        check("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb", PlaylistLinks.Service.APPLE_MUSIC, "playlist", "pl.f4d106fed2bd41149aaacabb233eb5eb")
        check("https://www.deezer.com/en/playlist/908622995", PlaylistLinks.Service.DEEZER, "playlist", "908622995")
        check("https://tidal.com/browse/playlist/4261748a-4287-4758-aaab-6d5be3e99e52", PlaylistLinks.Service.TIDAL, "playlist", "4261748a-4287-4758-aaab-6d5be3e99e52")
        check("https://music.youtube.com/playlist?list=PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i", PlaylistLinks.Service.YOUTUBE, "playlist", "PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i")
        check("https://www.youtube.com/watch?v=abc&list=PLx", PlaylistLinks.Service.YOUTUBE, "playlist", "PLx")
        check("https://soundcloud.com/someone/sets/late-night?si=1", PlaylistLinks.Service.SOUNDCLOUD, "playlist", "")
        assertNull(links.parse("https://example.com/playlist/1"))
        assertNull(links.parse("https://soundcloud.com/someone/a-single-track"))
    }

    @Test fun yourOwnSourcesWinThenTheCatalogues() = runBlocking {
        val library = listOf(song("lib1", "Under Pressure", "Queen & David Bowie", 248))
        val youtube = listOf(song("yt1", "Hey Jude", "The Beatles", 431, "aurora-yt://video/yt1"), song("yt2", "Under Pressure", "Queen", 248, "aurora-yt://video/yt2"))
        val soundcloud = listOf(song("sc1", "Night Drive (Original Mix)", "Some Producer", 300, "aurora-sc://track/sc1"))
        val stages = listOf(
            ImportStage(ImportSource.LIBRARY) { library },
            ImportStage(ImportSource.YOUTUBE_MUSIC) { youtube },
            ImportStage(ImportSource.SOUNDCLOUD) { soundcloud },
        )
        val tracks = listOf(
            ImportTrack("Under Pressure", "Queen, David Bowie", durationSec = 248),
            ImportTrack("Hey Jude - Remastered 2015", "The Beatles", durationSec = 429),
            ImportTrack("Night Drive", "Some Producer", durationSec = 300),
            ImportTrack("Not Anywhere", "Nobody", durationSec = 100),
        )
        val seen = mutableListOf<Int>()
        val m = PlaylistMatcher.matchAll(tracks, stages) { done, _ -> synchronized(seen) { seen += done } }
        assertEquals(listOf("lib1", "yt1", "sc1", null), m.map { it.song?.id })
        assertEquals(listOf(ImportSource.LIBRARY, ImportSource.YOUTUBE_MUSIC, ImportSource.SOUNDCLOUD, null), m.map { it.source })
        assertEquals(4, seen.max())
    }

    @Test fun anotherVersionOrArtistIsNotTheSameSong() {
        val track = ImportTrack("Creep", "Radiohead", durationSec = 236)
        assertNull(PlaylistMatcher.best(track, listOf(song("a", "Creep (Live)", "Radiohead", 290))))
        assertNull(PlaylistMatcher.best(track, listOf(song("b", "Creep", "TLC", 268))))
        assertNull(PlaylistMatcher.best(track, listOf(song("c", "Creep", "Radiohead", 236, url = ""))))
        assertEquals("d", PlaylistMatcher.best(track, listOf(song("d", "Creep", "Radiohead", 238)))?.id)
        assertEquals("Radiohead Creep", PlaylistMatcher.query(track))
        assertEquals("Queen Under Pressure", PlaylistMatcher.query(ImportTrack("Under Pressure (feat. David Bowie)", "Queen, David Bowie")))
    }

    @Test fun aCatalogueListKeepsItsOwnSongWhenNothingElseHasIt() = runBlocking {
        val original = song("vid", "Unreleased Demo", "Band", 180, "aurora-yt://video/vid")
        val m = PlaylistMatcher.match(ImportTrack("Unreleased Demo", "Band", durationSec = 180, original = original, originalSource = ImportSource.YOUTUBE_MUSIC),
            listOf(ImportStage(ImportSource.LIBRARY) { emptyList() }))
        assertEquals("vid", m.song?.id)
        assertEquals(ImportSource.YOUTUBE_MUSIC, m.source)
    }

    @Test fun importedPlaylistsKeepSongsFromEverySource() = runBlocking {
        val store = LocalStore(temp.root)
        val catalog = Proxy.newProxyInstance(LocalCatalog::class.java.classLoader, arrayOf(LocalCatalog::class.java)) { _, method, args ->
            when (method.name) {
                "song" -> null
                "ensureLoaded" -> Unit
                else -> error("Unexpected ${method.name}")
            }
        } as LocalCatalog
        val local = LocalBackend(catalog, store, Session("local", "Me", "", "", ServerType.LOCAL))
        val guest = Session.guest(ServerType.YOUTUBE_MUSIC, "Guest")
        val yt = song("vid", "Hey Jude", "The Beatles", 431, "aurora-yt://video/vid").inNamespace(guest)
        val id = local.createMixedPlaylist("From Spotify", listOf(yt))!!
        val entries = local.playlistEntries(id)!!
        assertEquals(listOf("aurora-yt://video/vid"), entries.map { it.streamUrl })
        assertTrue(entries.single().id.startsWith(mergeNamespace(guest)))
        assertEquals(listOf(yt.id), local.detail("playlist", id)!!.tracks.map { it.id })
    }
}
