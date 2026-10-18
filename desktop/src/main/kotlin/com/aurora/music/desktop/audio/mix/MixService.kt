package com.aurora.music.desktop.audio.mix

import com.aurora.music.data.SettingsStore
import com.aurora.music.desktop.audio.OutputBackend
import com.aurora.music.desktop.audio.ResolvedStream
import com.aurora.music.desktop.audio.defaultOutputBackend
import com.aurora.music.desktop.player.PlayerController
import com.aurora.music.mix.MixCommand
import com.aurora.music.mix.MixController
import com.aurora.music.mix.MixProject
import com.aurora.music.mix.MixPlaybackState
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// the desktop counterpart of android's mix session: one mix at a time, and the regular player and the mix never play together
class MixService(
    private val bus: MixController,
    private val settings: SettingsStore,
    private val resolve: (Song) -> ResolvedStream,
    scope: CoroutineScope,
    private val backend: () -> OutputBackend = { defaultOutputBackend() },
) : AutoCloseable {
    private val output by lazy(backend)
    @Volatile private var player: DesktopMixPlayer? = null
    @Volatile private var main: PlayerController? = null

    init {
        scope.launch {
            bus.commands.collect { command ->
                val mix = player ?: return@collect
                when (command) {
                    MixCommand.Toggle -> { if (!mix.playing) pauseMain(); mix.toggle() }
                    MixCommand.Stop -> stop()
                    is MixCommand.Seek -> mix.seek(command.seconds)
                    is MixCommand.Update -> mix.update(command.project)
                }
            }
        }
    }

    fun attach(controller: PlayerController, scope: CoroutineScope) {
        main = controller
        scope.launch { controller.state.collect { if (it.isPlaying) player?.takeIf { mix -> mix.playing }?.pause() } }
    }

    fun start(project: MixProject, fromSec: Float) {
        stop()
        pauseMain()
        val deviceId = runCatching {
            val key = settings.processingRoutes.current.route.key
            output.devices().firstOrNull { com.aurora.music.desktop.player.RulePublisher.routeKey(it.id) == key }?.id
        }.getOrNull()
        val rate = runCatching { output.mixRate(deviceId) }.getOrNull() ?: 48_000
        val replayGain = runCatching { runBlocking(Dispatchers.IO) { settings.audioPrefs.first().replayGain } }.getOrDefault(0)
        player = DesktopMixPlayer(project, bus, resolve, output, deviceId, rate, fromSec) { replayGain }.also { it.play() }
    }

    fun stop() {
        player?.close()
        player = null
        bus.state.value = MixPlaybackState()
    }

    private fun pauseMain() {
        main?.let { if (it.state.value.isPlaying) it.togglePlay() }
    }

    override fun close() = stop()
}
