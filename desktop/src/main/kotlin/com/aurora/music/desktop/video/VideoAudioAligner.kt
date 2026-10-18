package com.aurora.music.desktop.video

import com.aurora.music.data.Chromaprint
import com.aurora.music.desktop.audio.ResolvedStream
import com.aurora.music.desktop.audio.decode.HttpOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

// lines the song's audio up with the video's own soundtrack by fingerprint, so an intro or skit in the video doesn't push it out of sync
internal object VideoAudioAligner {
    suspend fun plan(song: ResolvedStream, video: ResolvedStream): VideoSyncPlan? = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job] ?: return@withContext null
        val songPrint = Chromaprint.rawFingerprint(song.url, 45, HttpOptions(song.headers, song.userAgent, rangedRequests = true)) { !job.isActive } ?: return@withContext null
        val videoPrint = Chromaprint.rawFingerprint(video.url, 65, HttpOptions(video.headers, video.userAgent, rangedRequests = true)) { !job.isActive } ?: return@withContext null
        if (songPrint.itemDurationMs != videoPrint.itemDurationMs) return@withContext null
        VideoFingerprintMatch.plan(VideoFingerprintMatch.anchors(songPrint.values, videoPrint.values, songPrint.itemDurationMs))
    }
}

internal data class VideoSyncSegment(val audioStartMs: Long, val offsetMs: Long)

internal data class VideoSyncPlan(val segments: List<VideoSyncSegment>) {
    fun offsetAt(audioMs: Long): Long = segments.lastOrNull { it.audioStartMs <= audioMs }?.offsetMs ?: 0L
}

internal object VideoFingerprintMatch {
    data class Anchor(val songMs: Long, val offsetMs: Long, val error: Double, val margin: Double)

    fun anchors(song: IntArray, video: IntArray, itemMs: Int): List<Anchor> {
        if (itemMs <= 0) return emptyList()
        val window = (10_000.0 / itemMs).roundToInt()
        val stride = (5_000.0 / itemMs).roundToInt().coerceAtLeast(1)
        if (song.size < window || video.size < window) return emptyList()
        return (0..song.size - window step stride).map { songStart ->
            val results = ArrayList<Pair<Int, Double>>()
            for (videoStart in 0..video.size - window) {
                val offset = (videoStart - songStart) * itemMs
                if (offset < -15_000 || offset > 45_000) continue
                var distance = 0
                for (i in 0 until window) {
                    distance += Integer.bitCount(song[songStart + i] xor video[videoStart + i])
                }
                results.add(videoStart to distance.toDouble() / window)
            }
            val best = results.minByOrNull { it.second } ?: return@map null
            val runnerUp = results.asSequence().filter { abs(it.first - best.first) * itemMs >= 2_000 }
                .minOfOrNull { it.second } ?: 32.0
            Anchor(songStart.toLong() * itemMs, (best.first - songStart).toLong() * itemMs,
                best.second, runnerUp - best.second)
        }.filterNotNull()
    }

    fun plan(anchors: List<Anchor>): VideoSyncPlan? {
        val strong = anchors.filter { it.error <= 9.0 && it.margin >= 0.8 }
        if (strong.size < 2) return null
        val groups = ArrayList<List<Anchor>>()
        var run = ArrayList<Anchor>()
        for (anchor in strong) {
            if (run.isNotEmpty() && (abs(anchor.offsetMs - run.last().offsetMs) > 900 ||
                    anchor.songMs - run.last().songMs > 12_000)) {
                groups.add(run)
                run = ArrayList()
            }
            run.add(anchor)
        }
        if (run.isNotEmpty()) groups.add(run)
        val supported = groups.filter { group ->
            group.size >= 2 && (group.sumOf { it.margin } >= 4.0 ||
                group.size >= 4 && group.map { it.error }.average() <= 3.0)
        }
        if (supported.isEmpty()) return null
        val strongest = supported.maxBy { it.size }
        val credible = supported.filter { it.size * 3 >= strongest.size || it === strongest }
        val segments = ArrayList<VideoSyncSegment>()
        for (group in credible) {
            val offset = group.map { it.offsetMs }.sorted()[group.size / 2]
            if (segments.isEmpty()) {
                if (group.first().songMs > 5_000) segments.add(VideoSyncSegment(0, 0))
                if (offset != segments.lastOrNull()?.offsetMs) {
                    segments.add(VideoSyncSegment(
                        if (group.first().songMs <= 5_000) 0 else group.first().songMs, offset))
                }
            } else if (offset - segments.last().offsetMs >= 1_000) {
                segments.add(VideoSyncSegment(group.first().songMs, offset))
            }
        }
        return VideoSyncPlan(segments)
    }
}
