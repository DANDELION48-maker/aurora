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
