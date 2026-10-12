package com.aurora.music.desktop.player

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DetailedListeningRecorder
import com.aurora.music.data.DetailedPlaySink
import com.aurora.music.data.DspMode
import com.aurora.music.data.InsightsPrefs
import com.aurora.music.data.PlayContext
import com.aurora.music.data.PlayedTrack
import com.aurora.music.data.ProcessingRack
import com.aurora.music.desktop.audio.EngineState
import com.aurora.music.desktop.audio.OutputInfo
import java.util.Locale

internal class DesktopListeningDetails(sink: DetailedPlaySink, platform: String) {
    @Volatile var crossfadeMs = 0L
    private val recorder = DetailedListeningRecorder(sink, platform, crossfadeMs = { crossfadeMs })
    @Volatile var prefs = InsightsPrefs()
    @Volatile var privateSession = false
    @Volatile var autoEqProfile = ""
    @Volatile var audio: AudioPrefs? = null
    @Volatile var rack: ProcessingRack? = null

    fun update() {
        recorder.allowed = prefs.enabled && !privateSession
    }

    fun track(state: EngineState, nowMs: Long) {
        if (!recorder.allowed) return
        val song = state.current?.song
        val track = song?.let {
            PlayedTrack(it.id, it.title, it.artist, it.album, it.albumId, it.artistId,
                it.durationSec.takeIf { d -> d > 0 } ?: (state.durationMs.coerceAtLeast(0) / 1000).toInt())
        }
        recorder.track(track, state.isPlaying, state.positionMs, nowMs) { context(state) }
    }

    fun finish() = recorder.finish()

    private fun context(state: EngineState): PlayContext {
        val song = state.current?.song
        val p = prefs
        val path = p.audioPath
        val audio = audio
        val custom = audio?.dspMode == DspMode.CUSTOM
        val source = state.source
        return PlayContext(
            genre = song?.genre?.takeIf(String::isNotBlank),
            source = song?.playbackSource?.source?.name,
            provider = song?.playbackSource?.providerLabel?.substringBefore(" · ")?.takeIf(String::isNotBlank),
            playlist = song?.playbackCollection?.name?.takeIf(String::isNotBlank),
            shuffle = state.shuffle,
            repeat = state.repeat.ordinal,
            output = if (p.device) state.output?.let(::category) else null,
            codec = if (path) source?.codec?.takeIf(String::isNotBlank) else null,
            sampleRateHz = if (path) source?.sampleRate?.takeIf { it > 0 } else null,
            bitDepth = if (path) source?.sampleFormat?.bits?.takeIf { it > 0 } else null,
            lossless = if (path) source?.sampleFormat?.lossless else null,
            dspEngine = if (path) audio?.dspMode?.let { if (it == DspMode.CUSTOM) "custom" else "off" } else null,
            autoEq = if (path && audio != null) custom && autoEqProfile.isNotBlank() else null,
            convolution = if (path && audio != null) custom && audio.dspConvEnabled else null,
            replayGain = if (path) audio?.replayGain else null,
            crossfeed = if (path && audio != null) custom && audio.dspCrossfeed > 0f else null,
            rack = if (path && audio != null) custom && rack?.enabled == true else null,
            bitPerfect = if (path && state.output != null) state.processing.bitPerfect else null,
        )
    }

    // desktop audio apis only expose device names, so the category comes from the name
    private fun category(output: OutputInfo): String {
        val name = output.deviceName.orEmpty().lowercase(Locale.ROOT)
        return when {
            listOf("bluetooth", "hands-free", "a2dp", "airpods", "buds").any(name::contains) -> "BLUETOOTH"
            listOf("usb", "dac").any(name::contains) -> "USB"
            "hdmi" in name || "display" in name -> "HDMI"
            "headphone" in name || "headset" in name -> "HEADPHONES"
            "speaker" in name -> "SPEAKER"
            else -> "OTHER"
        }
    }
}
