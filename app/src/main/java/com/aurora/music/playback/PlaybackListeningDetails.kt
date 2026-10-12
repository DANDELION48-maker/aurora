package com.aurora.music.playback

import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DetailedListeningRecorder
import com.aurora.music.data.DetailedPlaySink
import com.aurora.music.data.DspMode
import com.aurora.music.data.InsightsPrefs
import com.aurora.music.data.PlayContext
import com.aurora.music.data.PlayedTrack
import com.aurora.music.data.Preservation
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.SignalPath
import com.aurora.music.data.routes.ProcessingRoute
import com.aurora.music.data.routes.ProcessingRouteKind

// the sink is only touched once a play is saved, so the database never exists unless the feature is on
internal class PlaybackListeningDetails(
    sink: DetailedPlaySink,
    private val route: () -> ProcessingRoute,
    private val signal: () -> SignalPath,
    private val audio: () -> AudioPrefs?,
    private val rack: () -> ProcessingRack?,
    private val location: (zone: Boolean, point: Boolean) -> Pair<String?, com.aurora.music.data.LocationFix?>,
    crossfadeMs: () -> Long,
) {
    private val recorder = DetailedListeningRecorder(sink, crossfadeMs = crossfadeMs)
    @Volatile var prefs = InsightsPrefs()
    @Volatile var privateSession = false
    @Volatile var autoEqProfile = ""

    fun update() {
        recorder.allowed = prefs.enabled && !privateSession
    }

    fun track(active: Player?, nativeUsbPlaying: Boolean) {
        if (!recorder.allowed) return
        val item = active?.currentMediaItem
        val metadata = item?.mediaMetadata
        val extras = metadata?.extras
        val track = item?.let {
            PlayedTrack(extras?.getString("aurora.songId")?.takeIf(String::isNotBlank) ?: it.mediaId,
                it.mediaMetadata.title?.toString().orEmpty(), it.mediaMetadata.artist?.toString().orEmpty(), it.mediaMetadata.albumTitle?.toString().orEmpty(),
                extras?.getString("aurora.albumId").orEmpty(), extras?.getString("aurora.artistId").orEmpty(),
                extras?.getInt("aurora.durationSec")?.takeIf { d -> d > 0 } ?: (active.duration.coerceAtLeast(0) / 1000).toInt())
        }
        val playing = active != null && (active.isPlaying || nativeUsbPlaying)
        recorder.track(track, playing, active?.currentPosition ?: 0L, SystemClock.elapsedRealtime()) { context(active, extras) }
    }

    fun finish() = recorder.finish()

    private fun context(player: Player?, extras: Bundle?): PlayContext {
        val p = prefs
        val signal = signal().takeIf { it.active }
        val audio = audio()
        val custom = audio?.dspMode == DspMode.CUSTOM
        val path = p.audioPath
        val current = route()
        val (zone, fix) = if (p.places || p.locationHistory) location(p.places, p.locationHistory) else null to null
        return PlayContext(
            genre = extras?.getStringArrayList(TAGS + "genres")?.firstOrNull(),
            source = extras?.getString(TAGS + "source"),
            provider = extras?.getString(TAGS + "providerLabel")?.substringBefore(" · ")?.takeIf(String::isNotBlank),
            playlist = extras?.getString(TAGS + "playlistName"),
            shuffle = player?.shuffleModeEnabled,
            repeat = player?.repeatMode,
            output = if (!p.device) null else when (current.kind) {
                ProcessingRouteKind.CAST -> "CAST"
                ProcessingRouteKind.NATIVE_USB -> "USB"
                ProcessingRouteKind.ANDROID -> current.category?.name
                else -> null
            },
            codec = if (path) signal?.codec?.takeIf(String::isNotBlank) else null,
            sampleRateHz = if (path) signal?.sampleRateHz?.takeIf { it > 0 } else null,
            bitDepth = if (path) signal?.bitDepth?.takeIf { it > 0 } else null,
            lossless = if (path) PlayContext.losslessCodec(signal?.codec) else null,
            dspEngine = if (path) audio?.dspMode?.let { when (it) { DspMode.SYSTEM -> "system"; DspMode.CUSTOM -> "custom"; else -> "off" } } else null,
            autoEq = if (path && audio != null) custom && autoEqProfile.isNotBlank() else null,
            convolution = if (path && audio != null) custom && audio.dspConvEnabled else null,
            replayGain = if (path) audio?.replayGain else null,
            crossfeed = if (path && audio != null) custom && audio.dspCrossfeed > 0f else null,
            rack = if (path && audio != null) custom && rack()?.enabled == true else null,
            bitPerfect = if (path) signal?.let { it.preservation == Preservation.PRESERVED } else null,
            zone = zone,
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            locationAccuracyM = fix?.accuracyM,
        )
    }

    private companion object {
        const val TAGS = "aurora.rules."
    }
}
