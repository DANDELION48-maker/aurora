package com.aurora.music.desktop.video

import com.aurora.music.desktop.audio.ResolvedStream
import com.aurora.music.desktop.audio.decode.HttpOptions
import com.aurora.music.desktop.youtube.YoutubeResolver
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class CompanionVideoState(val ready: Boolean = false, val loading: Boolean = false, val qualityHeight: Int? = null,
    val canvas: Boolean = false, val player: VideoPlayer? = null)

/** A song's canvas, or its music video kept in step with the audio; the video is always muted. */
class CompanionVideo(
    private val resolver: YoutubeResolver,
    private val canvasFor: suspend (Song) -> String?,
    private val sourceFor: (Song) -> ResolvedStream,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutable = MutableStateFlow(CompanionVideoState())
    val state: StateFlow<CompanionVideoState> = mutable.asStateFlow()
    private var job: Job? = null
    private var alignJob: Job? = null
    private var qualityJob: Job? = null
    @Volatile private var songId: String? = null
    @Volatile private var videoId: String? = null
    @Volatile private var plan: VideoSyncPlan? = null
    private val planCache = LinkedHashMap<Pair<String, String>, VideoSyncPlan>()
    @Volatile private var visible = false
    private var activeOffset = 0L
    private var lastSeek = 0L
    private var lastAudio: Triple<Long, Boolean, Float> = Triple(0L, false, 1f)

    fun request(song: Song, wantCanvas: Boolean, onUnavailable: () -> Unit) {
        if (song.id.isBlank() || song.title.isBlank() || mutable.value.loading) return
        if (songId == song.id && mutable.value.ready && mutable.value.canvas == wantCanvas) return
        clear()
        songId = song.id
        mutable.value = CompanionVideoState(loading = true)
        job = scope.launch {
            if (wantCanvas) {
                val url = runCatching { canvasFor(song) }.getOrNull()
                ensureActive()
                if (url == null || songId != song.id) { clear(); onUnavailable(); return@launch }
                start(url, canvas = true, quality = null, onUnavailable)
                return@launch
            }
            if (song.artist.isBlank()) { clear(); onUnavailable(); return@launch }
            val match = withContext(Dispatchers.IO) { resolver.findMusicVideo(song.artist, song.title, song.durationSec) }
            ensureActive()
            if (match == null || songId != song.id) { clear(); onUnavailable(); return@launch }
            videoId = match.videoId
            val key = song.id to match.videoId
            synchronized(planCache) { planCache[key] }?.let { plan = it }
            if (plan == null) alignJob = scope.launch {
                val audio = runCatching { resolver.resolveVideo(match.videoId) }.getOrNull() ?: return@launch
                val found = runCatching {
                    VideoAudioAligner.plan(sourceFor(song), ResolvedStream(audio, userAgent = YoutubeResolver.USER_AGENT))
                }.getOrNull() ?: return@launch
                if (songId != song.id || videoId != match.videoId) return@launch
                plan = found
                synchronized(planCache) { if (planCache.size >= 64) planCache.remove(planCache.keys.first()); planCache[key] = found }
                sync(lastAudio.first, lastAudio.second, lastAudio.third, forceSeek = true)
            }
            start(match.streamUrl, canvas = false, quality = 720, onUnavailable)
        }
    }

    private suspend fun start(url: String, canvas: Boolean, quality: Int?, onUnavailable: () -> Unit) {
        val player = VideoPlayer(url, HttpOptions(userAgent = YoutubeResolver.USER_AGENT), loop = canvas)
        val ready = withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 20_000
            while (!player.ready.value && !player.failed.value && System.currentTimeMillis() < deadline) Thread.sleep(25)
            player.ready.value
        }
        if (!ready) { player.close(); clear(); onUnavailable(); return }
        mutable.value = CompanionVideoState(ready = true, qualityHeight = quality, canvas = canvas, player = player)
        sync(lastAudio.first, lastAudio.second, lastAudio.third, forceSeek = true)
    }

    fun clearIfSongChanged(currentSongId: String) {
        if (songId != null && songId != currentSongId) clear()
    }

    fun setVisible(value: Boolean) {
        visible = value
        sync(lastAudio.first, lastAudio.second, lastAudio.third, forceSeek = value)
    }

    // the video follows the audio clock: a seek, a new alignment segment or a long drift re-seeks it
    fun sync(audioMs: Long, playing: Boolean, speed: Float, forceSeek: Boolean = false) {
        lastAudio = Triple(audioMs, playing, speed)
        val current = mutable.value
        val player = current.player ?: return
        if (!visible || current.canvas) {
            if (visible && playing) player.play() else player.pause()
            return
        }
        player.setSpeed(speed)
        val offset = plan?.offsetAt(audioMs) ?: 0L
        val segmentChanged = offset != activeOffset
        activeOffset = offset
        val target = audioMs + offset
        if (target < 0) { player.pause(); return }
        val now = System.currentTimeMillis()
        val drift = playing && abs(player.positionMs - target) > 1_500 && now - lastSeek > 5_000
        if (forceSeek || segmentChanged || drift) { player.seek(target); lastSeek = now }
        if (playing) player.play() else player.pause()
    }

    fun setQuality(height: Int?) {
        val current = mutable.value
        val id = videoId ?: return
        if (!current.ready || current.canvas || height == current.qualityHeight) return
        qualityJob?.cancel()
        qualityJob = scope.launch {
            mutable.value = current.copy(loading = true)
            val url = withContext(Dispatchers.IO) { runCatching { resolver.resolveVisualStream(id, height?.coerceAtLeast(144)) }.getOrNull() }
            ensureActive()
            if (url == null || videoId != id) { mutable.value = mutable.value.copy(loading = false); return@launch }
            val next = VideoPlayer(url, HttpOptions(userAgent = YoutubeResolver.USER_AGENT))
            withContext(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + 20_000
                while (!next.ready.value && !next.failed.value && System.currentTimeMillis() < deadline) Thread.sleep(25)
            }
            if (!next.ready.value || videoId != id) { next.close(); mutable.value = mutable.value.copy(loading = false); return@launch }
            mutable.value.player?.close()
            mutable.value = CompanionVideoState(ready = true, qualityHeight = height, canvas = false, player = next)
            sync(lastAudio.first, lastAudio.second, lastAudio.third, forceSeek = true)
        }
    }

    fun clear() {
        job?.cancel(); job = null
        alignJob?.cancel(); alignJob = null
        qualityJob?.cancel(); qualityJob = null
        mutable.value.player?.close()
        songId = null
        videoId = null
        plan = null
        activeOffset = 0L
        lastSeek = 0L
        visible = false
        mutable.value = CompanionVideoState()
    }

    suspend fun awaitIdle() { state.first { !it.loading } }
}
