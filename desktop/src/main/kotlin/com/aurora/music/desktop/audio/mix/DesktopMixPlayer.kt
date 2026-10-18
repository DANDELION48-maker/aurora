package com.aurora.music.desktop.audio.mix

import com.aurora.music.desktop.audio.AudioOutput
import com.aurora.music.desktop.audio.OutputBackend
import com.aurora.music.desktop.audio.ResolvedStream
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.mix.AutoMixPlanner
import com.aurora.music.mix.MixClip
import com.aurora.music.mix.MixController
import com.aurora.music.mix.MixMath
import com.aurora.music.mix.MixPlaybackState
import com.aurora.music.mix.MixProject
import com.aurora.music.mix.StemMode
import com.aurora.music.model.Song
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.max

/** Every clip of a mix decoded and summed in one stream on the computer's output, transported as a whole. */
class DesktopMixPlayer(
    initial: MixProject,
    private val bus: MixController,
    private val resolve: (Song) -> ResolvedStream,
    private val backend: OutputBackend,
    private val deviceId: String?,
    private val rate: Int,
    startSec: Float = 0f,
    private val replayGain: () -> Int = { 0 },
) : AutoCloseable {
    @Volatile private var project = initial.normalized()
    @Volatile private var wanted = false
    @Volatile private var position = startSec.coerceIn(0f, project.durationSec)
    @Volatile private var closed = false
    @Volatile private var error: String? = null
    @Volatile private var buffering = false
    @Volatile private var seekTo: Float? = null
    private val decks = ConcurrentHashMap<String, MixDeck>()
    private val opening = ConcurrentHashMap<String, Future<*>>()
    private val loader = Executors.newFixedThreadPool(2) { Thread(it, "aurora-mix-deck").apply { isDaemon = true } }
    private val lock = Object()
    private val thread: Thread

    init {
        require(project.clips.isNotEmpty()) { "Add tracks to the mix first" }
        require(AutoMixPlanner.maxLayers(project) <= MixProject.MAX_LAYERS) { "At most eight tracks can overlap at once." }
        bus.activeProject = project
        thread = Thread(::run, "aurora-mix").apply { isDaemon = true; start() }
        publish()
    }

    val playing: Boolean get() = wanted

    fun play() { synchronized(lock) { if (position >= project.durationSec) seekInternal(0f); wanted = true; error = null; lock.notifyAll() } }
    fun pause() { synchronized(lock) { wanted = false; lock.notifyAll() }; publish() }
    fun toggle() = if (wanted) pause() else play()
    fun seek(seconds: Float) { synchronized(lock) { seekTo = seconds; lock.notifyAll() } }

    fun update(updated: MixProject) {
        if (updated.clips.map { it.id } != project.clips.map { it.id }) return
        val next = updated.normalized()
        if (AutoMixPlanner.maxLayers(next) > MixProject.MAX_LAYERS) return
        var reposition = false
        next.clips.forEach { clip ->
            val deck = decks[clip.id] ?: return@forEach
            val old = deck.clip
            if (old.startSec != clip.startSec || old.cueInSec != clip.cueInSec || old.speed != clip.speed || old.stem != clip.stem || old.stemUri != clip.stemUri) reposition = true
            else deck.clip = clip
        }
        project = next
        bus.activeProject = next
        if (reposition) seek(position)
        publish()
    }

    private fun sourceFor(clip: MixClip): ResolvedStream =
        if (clip.stem != StemMode.FULL && clip.stemUri.isNotBlank()) ResolvedStream(clip.stemUri) else resolve(clip.song)

    // decoders open off the audio thread for whatever plays now or within the next fifteen seconds
    private fun syncDecks() {
        val near = project.clips.filter { it.endSec > position && it.startSec <= position + 15f }.sortedBy { it.startSec }.take(MixProject.MAX_LAYERS)
        val ids = near.map { it.id }.toSet()
        decks.keys.filter { it !in ids }.forEach { id -> decks.remove(id)?.close() }
        opening.keys.filter { it !in ids }.forEach { id -> opening.remove(id)?.cancel(true) }
        for (clip in near) if (!decks.containsKey(clip.id) && !opening.containsKey(clip.id)) {
            val offset = (position - clip.startSec).coerceIn(0f, clip.durationSec)
            opening[clip.id] = loader.submit {
                try {
                    val deck = MixDeck(clip, sourceFor(clip), rate, offset)
                    if (opening.remove(clip.id) != null && !closed) decks[clip.id] = deck else deck.close()
                } catch (e: Exception) {
                    opening.remove(clip.id)
                    if (!closed) { error = "Could not play ${clip.song.title}. Check the source and retry."; wanted = false }
                }
            }
        }
    }

    private fun seekInternal(seconds: Float) {
        position = seconds.coerceIn(0f, project.durationSec)
        decks.values.forEach { it.close() }
        decks.clear()
        opening.values.forEach { it.cancel(true) }
        opening.clear()
        syncDecks()
    }

    private fun run() {
        var output: AudioOutput? = null
        val frames = 1024
        val mix = DoubleArray(frames * 2)
        val deckBuffer = DoubleArray(frames * 2)
        val bytes = ByteBuffer.allocate(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        var lastPublish = 0L
        try {
            syncDecks()
            while (!closed) {
                synchronized(lock) {
                    seekTo?.let { seekInternal(it); seekTo = null }
                    if (!wanted) {
                        output?.pause()
                        lock.wait(250)
                    }
                }
                if (closed) break
                if (!wanted) { syncDecks(); publish(); continue }
                syncDecks()
                val active = project.clips.filter { position >= it.startSec && position < it.endSec }
                buffering = active.any { !decks.containsKey(it.id) } && error == null
                if (buffering) { publish(); Thread.sleep(20); continue }
                val out = output ?: backend.open(deviceId, false, rate, OutputEncoding.F32, 150).also { output = it }
                out.resume()
                mix.fill(0.0)
                val levels = gains()
                project.clips.forEachIndexed { index, clip ->
                    val deck = decks[clip.id] ?: return@forEachIndexed
                    if (position < clip.startSec || position >= clip.endSec) return@forEachIndexed
                    deck.bassCutDb = bassCut(clip)
                    deck.render(deckBuffer, frames)
                    val gain = levels[index].toDouble()
                    for (i in 0 until frames * 2) mix[i] += deckBuffer[i] * gain
                }
                val master = MixMath.amplitude(project.masterDb).toDouble()
                bytes.clear()
                for (i in 0 until frames * 2) {
                    var value = mix[i] * master
                    if (project.protectPeaks) value = value.coerceIn(-PEAK, PEAK)
                    bytes.putFloat(value.toFloat())
                }
                var written = 0
                while (written < bytes.capacity() && wanted && !closed) {
                    val count = out.write(bytes.array(), written, bytes.capacity() - written, 200)
                    if (count < 0) error("Mix output stopped accepting audio.")
                    written += count
                }
                position += frames.toFloat() / rate
                if (position >= project.durationSec) {
                    if (project.loop) seekInternal(0f) else { position = project.durationSec; wanted = false }
                }
                val now = System.nanoTime()
                if (now - lastPublish > 33_000_000L) { lastPublish = now; publish(levels) }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            error = e.message ?: "Mix output failed."
            wanted = false
            publish()
        } finally {
            output?.let { runCatching { it.pause(); it.flush() }; runCatching { it.close() } }
        }
    }

    private fun gains(): List<Float> {
        val mode = replayGain()
        return MixMath.gains(project, position).mapIndexed { index, gain ->
            val song = project.clips[index].song
            val db = when (mode) { 1 -> song.replayGainTrack; 2 -> song.replayGainAlbum; else -> 0f }
            gain * if (db.isFinite()) MixMath.amplitude(db).coerceIn(0.1f, 1f) else 1f
        }
    }

    // bass swap pulls the low end out of a track while it fades in or out, so two basslines never stack
    private fun bassCut(c: MixClip): Float {
        if (!c.bassSwap) return 0f
        val entrance = if (c.fadeInSec > 0) 1 - MixMath.rise(2 * (position - c.startSec) / c.fadeInSec, "SMOOTH") else 0f
        val exit = if (c.fadeOutSec > 0) MixMath.rise(1 - 2 * (c.endSec - position) / c.fadeOutSec, "SMOOTH") else 0f
        val cut = -18f * max(entrance, exit)
        return if (abs(cut) < .25f) 0f else (cut * 4).toInt() / 4f
    }

    private fun publish(levels: List<Float> = emptyList()) {
        bus.state.value = MixPlaybackState(project.id, wanted, buffering, position, error, levels)
    }

    override fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        thread.join(2_000)
        decks.values.forEach { it.close() }
        decks.clear()
        opening.values.forEach { it.cancel(true) }
        loader.shutdownNow()
        bus.state.value = MixPlaybackState()
        bus.activeProject = null
    }

    private companion object {
        val PEAK = 10.0.pow(-0.3 / 20.0)

        private fun Double.pow(x: Double) = Math.pow(this, x)
    }
}
