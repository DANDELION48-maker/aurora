package com.aurora.music.data

import com.aurora.music.data.remote.SpotifyGraphql
import com.aurora.music.data.remote.SpotifyGraphqlException
import com.aurora.music.model.Song
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyWebLyricsTest {
    private val session = Session(SpotifyWebBackend.SERVER, "Listener", SpotifyWebBackend.WEB_PLAYER, "reference", ServerType.SPOTIFY, "listener")
    private val song = Song("4uLU6hMCjMI75M1A2tKUQC", "Track", "Artist", "Album", "https://i.scdn.co/image/ab67616d0000b273", 200)
    private val paths = mutableListOf<String>()

    private fun backend(reply: (String) -> JsonObject) = SpotifyWebBackend(session, object : SpotifyGraphql {
        override suspend fun query(operation: String, variables: JsonObject) = error("unused")
        override suspend fun spclient(path: String, body: JsonObject?): JsonObject { paths += path; return reply(path) }
    }) { it }

    private fun lyrics(syncType: String, lines: String) =
        JsonParser.parseString("""{"lyrics":{"syncType":"$syncType","providerDisplayName":"Musixmatch","lines":[$lines]}}""").asJsonObject

    @Test fun lineSyncedLyricsKeepTimesAndCredit() = runBlocking {
        val result = backend { lyrics("LINE_SYNCED", """{"startTimeMs":"1000","words":"Hello world","syllables":[],"endTimeMs":"0"},{"startTimeMs":"3000","words":"♪","syllables":[],"endTimeMs":"0"}""") }
            .serverLyrics(song)!!
        assertTrue(result.synced)
        assertEquals("Spotify · Musixmatch", result.source)
        assertEquals(listOf(1000L, 3000L), result.lines.map { it.startMs })
        assertTrue(result.lines.all { it.words.isEmpty() })
        assertTrue(paths.single().startsWith("/color-lyrics/v2/track/${song.id}/image/https%3A%2F%2Fi.scdn.co"))
    }

    @Test fun syllablesBecomeWordsThatRebuildTheLine() = runBlocking {
        val result = backend {
            lyrics("SYLLABLE_SYNCED", """{"startTimeMs":"1000","words":"Hello world","syllables":[{"startTimeMs":"1000","numChars":"5"},{"startTimeMs":"1600","numChars":"5"}],"endTimeMs":"0"},
                {"startTimeMs":"3000","words":"Again","syllables":[],"endTimeMs":"0"}""")
        }.serverLyrics(song)!!
        val words = result.lines[0].words
        assertEquals(listOf("Hello ", "world"), words.map { it.text })
        assertEquals("Hello world", result.lines[0].displayText)
        assertEquals(listOf(1000L to 1600L, 1600L to 3000L), words.map { it.startMs to it.endMs })
    }

    @Test fun inconsistentSyllablesFallBackToLineTiming() = runBlocking {
        val result = backend {
            lyrics("SYLLABLE_SYNCED", """{"startTimeMs":"1000","words":"Hi","syllables":[{"startTimeMs":"1000","numChars":"9"}],"endTimeMs":"0"}""")
        }.serverLyrics(song)!!
        assertTrue(result.lines[0].words.isEmpty())
        assertEquals("Hi", result.lines[0].displayText)
    }

    @Test fun unsyncedAndMissingLyrics() = runBlocking {
        val plain = backend { lyrics("UNSYNCED", """{"startTimeMs":"0","words":"Just text","syllables":[],"endTimeMs":"0"}""") }.serverLyrics(song)!!
        assertEquals(false, plain.synced)
        assertEquals(-1, plain.lines[0].timeSec)
        assertNull(backend { throw SpotifyGraphqlException("none", 404) }.serverLyrics(song))
        assertNull(backend { error("unused") }.serverLyrics(song.copy(id = "aurora-mix:1")))
    }
}
