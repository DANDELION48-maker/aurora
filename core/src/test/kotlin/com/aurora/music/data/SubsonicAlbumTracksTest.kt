package com.aurora.music.data

import com.aurora.music.data.remote.SongDto
import org.junit.Assert.assertEquals
import org.junit.Test

class SubsonicAlbumTracksTest {
    private fun song(id: String, track: Int, title: String, duration: Int, disc: Int = 1, starred: String? = null, bitRate: Int = 320) =
        SongDto(id = id, title = title, artist = "Oliver Francis", track = track, discNumber = disc, duration = duration, starred = starred, bitRate = bitRate)

    @Test fun twoCopiesOfAReleaseCollapseToOneTrackEach() {
        val songs = listOf(
            song("a1", 1, "jet ski freestyle", 115), song("b1", 1, "jet ski freestyle", 115, bitRate = 1000),
            song("a2", 2, "AAHYEAHH", 185, starred = "2026-10-01"), song("b2", 2, "AAHYEAHH", 186, bitRate = 1000),
            song("a3", 3, "jellyfish", 147), song("b3", 3, "jellyfish", 147),
        )
        assertEquals(listOf("b1", "a2", "a3"), distinctAlbumTracks(songs).map { it.id })
    }

    @Test fun differentTracksWithOneNameStay() {
        val songs = listOf(
            song("intro", 1, "Intro", 60), song("reprise", 9, "Intro", 60),
            song("d1", 1, "Theme", 200, disc = 1), song("d2", 1, "Theme", 200, disc = 2),
            song("edit", 4, "Theme", 120), song("full", 4, "Theme", 300),
        )
        assertEquals(songs.map { it.id }, distinctAlbumTracks(songs).map { it.id })
    }
}
