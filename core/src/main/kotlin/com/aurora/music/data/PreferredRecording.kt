package com.aurora.music.data

import com.aurora.music.model.Song
import java.text.Normalizer
import java.util.Locale

fun recordingMatches(a: Song, b: Song): Boolean = a.artist.isNotBlank() && recordingTitlesMatch(a, b) && artistsMatch(a.artist, b.artist)

fun recordingTitlesMatch(a: Song, b: Song): Boolean =
    a.title.isNotBlank() && normalized(recordingTitle(a.title)) == normalized(recordingTitle(b.title))

fun recordingTitle(title: String): String = title
    .replace(Regex("\\s*[\\[(](?:feat\\.?|ft\\.?|featuring)\\s+[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE), "").trim()

// the title a recording is filed under, for finding it among another source's songs
fun recordingKey(title: String): String = normalized(recordingTitle(title))

// a differently spelled artist still counts when the title and the length agree closely
fun sameLength(song: Song, copy: Song) = recordingTitlesMatch(song, copy) && song.durationSec > 0 && copy.durationSec > 0 &&
    kotlin.math.abs(song.durationSec - copy.durationSec) <= RELAXED_DURATION_SEC

// uploads often carry "artist - title" in the title, posted by a label or fan account
fun recordingVariants(song: Song): List<Song> {
    val parts = song.title.split(" - ", " \u2013 ", limit = 2)
    return if (parts.size == 2) listOf(song, song.copy(artist = parts[0], title = parts[1])) else listOf(song)
}

fun twinMatches(song: Song, candidate: Song): Boolean = recordingVariants(candidate).any { recordingMatches(song, it) || sameLength(song, it) }

private const val RELAXED_DURATION_SEC = 2

// services disagree on accents and on a leading "the", so "The Soft Walls" is still "Soft Walls"
private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun artistsMatch(a: String, b: String): Boolean {
    fun credits(value: String) = recordingTitle(value).lowercase(Locale.ROOT)
        .split(Regex("\\s+(?:&|and)\\s+|\\s*[,;]\\s*"))
        .map { normalized(it).removePrefix("the ") }.filter { it.isNotBlank() }.toSet()
    return credits(a).intersect(credits(b)).isNotEmpty()
}

/** Keep catalogue identity and queue context; copy only the playable recording's properties. */
fun Song.withPlaybackFrom(other: Song): Song = copy(
    durationSec = other.durationSec.takeIf { it > 0 } ?: durationSec,
    streamUrl = other.streamUrl, suffix = other.suffix, bitrateKbps = other.bitrateKbps,
    sampleRateHz = other.sampleRateHz, bitDepth = other.bitDepth,
    replayGainTrack = other.replayGainTrack, replayGainAlbum = other.replayGainAlbum,
    path = other.path, playbackSource = other.playbackSource?.copy(songId = other.playbackSource.songId ?: other.id),
)
