package com.aurora.music.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.R
import com.aurora.music.data.ProcessingPreset
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.audio.OutputBackend
import com.aurora.music.desktop.audio.ResolvedStream
import com.aurora.music.desktop.audio.compare.ComparisonPlayback
import com.aurora.music.desktop.audio.compare.ComparisonRenderer
import com.aurora.music.desktop.audio.defaultOutputBackend
import com.aurora.music.desktop.player.PlayerController
import com.aurora.music.localization.appString
import com.aurora.music.playback.compare.AbxResult
import com.aurora.music.playback.compare.AbxTrial
import com.aurora.music.playback.compare.ComparisonLevels
import com.aurora.music.playback.compare.ComparisonSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ComparisonUiState(val preparing: Boolean = false, val active: Boolean = false,
    val blind: Boolean = false, val selection: ComparisonSelection = ComparisonSelection.A,
    val answered: Int = 0, val planned: Int = 16, val result: AbxResult? = null,
    val levels: ComparisonLevels? = null, val rate: Int = 0, val selectionFrames: Int = 0, val error: String? = null)

// the player pauses while two renders of the same selection play, and resumes afterwards unless something else took over
class ComparisonViewModel(private val container: DesktopContainer, private val player: PlayerController,
    private val backend: () -> OutputBackend = { defaultOutputBackend() }) : ViewModel() {
    private val store = container.settingsStore
    private val mutable = MutableStateFlow(ComparisonUiState())
    val state = mutable.asStateFlow()
    private var task: Job? = null
    private var playback: ComparisonPlayback? = null
    private var trial: AbxTrial? = null
    private var restorePlaying = false
    private var originalId: String? = null
    private var frozen = false
    private var generation = 0L
    private var measuredLevels: ComparisonLevels? = null
    private val output by lazy(backend)

    fun start(source: ResolvedStream, startSeconds: Int, a: ProcessingPreset, b: ProcessingPreset, blind: Boolean) {
        if (mutable.value.active || mutable.value.preparing) return
        val session = ++generation
        trial = null
        measuredLevels = null
        mutable.value = ComparisonUiState(preparing = true, blind = blind)
        task = viewModelScope.launch {
            try {
                require(!store.presetRuleContext.current.frozen) { appString(R.string.text_another_comparison_is_active_48df0f) }
                store.presetRuleContext.setFrozen(true); frozen = true
                val playing = player.state.value
                originalId = playing.current.id.takeIf { it.isNotEmpty() }
                restorePlaying = playing.isPlaying
                if (playing.isPlaying) player.togglePlay()
                val deviceId = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val key = store.processingRoutes.current.route.key
                    output.devices().firstOrNull { com.aurora.music.desktop.player.RulePublisher.routeKey(it.id) == key }?.id
                }
                val rate = withContext(kotlinx.coroutines.Dispatchers.IO) { output.mixRate(deviceId) } ?: 48_000
                val audio = withTimeoutOrNull(120_000) {
                    ComparisonRenderer.prepare(source, startSeconds, a, b, rate, player.volume.value.toDouble().coerceIn(0.0, 1.0))
                } ?: error(appString(R.string.text_preparing_the_comparison_timed_out_fe2312))
                if (player.state.value.isPlaying || player.state.value.current.id.takeIf { it.isNotEmpty() } != originalId) {
                    restorePlaying = false
                    error(appString(R.string.text_playback_output_or_volume_changed_prepare_the_comparison_again_f65441))
                }
                measuredLevels = audio.levels
                val sound = ComparisonPlayback(output, deviceId, audio) { reason -> viewModelScope.launch { if (generation == session) stop(reason) } }
                playback = sound
                sound.start()
                sound.awaitReady()
                trial = if (blind) AbxTrial() else null
                mutable.value = ComparisonUiState(active = true, blind = blind, levels = if (blind) null else audio.levels,
                    rate = audio.rate, selectionFrames = audio.frames)
                while (mutable.value.active) {
                    delay(250)
                    if (player.state.value.current.id.takeIf { it.isNotEmpty() } != originalId || player.state.value.isPlaying) {
                        restorePlaying = false
                        stop(appString(R.string.text_playback_changed_the_comparison_stopped_2af425)); break
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == session) mutable.value = mutable.value.copy(preparing = false, active = false,
                    error = failure.message?.takeUnless { it.contains("://") } ?: appString(R.string.text_could_not_prepare_this_audio_selection_814b67))
            } finally {
                if (generation == session) {
                    releaseOutput()
                    mutable.value = mutable.value.copy(preparing = false, active = false)
                }
            }
        }
    }

    fun select(selection: ComparisonSelection) {
        if (!mutable.value.active) return
        val a = when (selection) {
            ComparisonSelection.A -> true
            ComparisonSelection.B -> false
            ComparisonSelection.X -> trial?.takeIf { it.active }?.xIsA() ?: return
        }
        playback?.select(a)
        mutable.value = mutable.value.copy(selection = selection)
    }

    fun guess(a: Boolean) {
        val current = trial?.takeIf { it.active && mutable.value.active } ?: return
        current.guess(a)
        if (current.complete) {
            generation++
            task?.cancel(); task = null
            mutable.value = mutable.value.copy(active = false, answered = current.answered, result = current.result(), levels = measuredLevels)
            releaseOutput()
        } else {
            mutable.value = mutable.value.copy(answered = current.answered)
            select(ComparisonSelection.A)
        }
    }

    fun stop(reason: String? = null) {
        generation++
        if (reason != null) restorePlaying = false
        trial?.takeIf { it.active }?.interrupt()
        mutable.value = mutable.value.copy(active = false, preparing = false, error = reason,
            result = trial?.takeIf { !it.active }?.result(), levels = measuredLevels)
        task?.cancel(); task = null
        releaseOutput()
    }

    private fun releaseOutput() {
        val sound = playback
        playback = null
        val unfreeze = frozen
        frozen = false
        val resume = restorePlaying && sound?.invalidationReason == null
        val mediaId = originalId
        restorePlaying = false
        fun complete() {
            if (unfreeze) store.presetRuleContext.setFrozen(false)
            if (resume && player.state.value.current.id == mediaId && !player.state.value.isPlaying) player.togglePlay()
        }
        if (sound == null) complete() else {
            sound.whenClosed(::complete)
            sound.close()
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
