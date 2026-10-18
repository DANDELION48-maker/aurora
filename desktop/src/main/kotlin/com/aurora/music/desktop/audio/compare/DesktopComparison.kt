package com.aurora.music.desktop.audio.compare

import com.aurora.music.data.DspMode
import com.aurora.music.data.ProcessingPreset
import com.aurora.music.data.ProcessingPresetAssets
import com.aurora.music.data.ProcessingPresetBundle
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.requiresImpulseResponse
import com.aurora.music.desktop.audio.AudioOutput
import com.aurora.music.desktop.audio.OutputBackend
import com.aurora.music.desktop.audio.ResolvedStream
import com.aurora.music.desktop.audio.decode.FfmpegDecoder
import com.aurora.music.desktop.audio.decode.HttpOptions
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.playback.ImpulseResponse
import com.aurora.music.playback.compare.ComparisonAudio
import com.aurora.music.playback.compare.ComparisonMatching
import com.aurora.music.playback.compare.ComparisonMixer
import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.BandlimitedResampler
import com.aurora.music.playback.engine.ChannelLayout
import com.aurora.music.playback.engine.ProductionSerialRack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class ComparisonSource(val rate: Int, val samples: DoubleArray, val warmupFrames: Int)

// both presets render the same selection offline at the output's own rate, so switching is instant and sample aligned
object ComparisonRenderer {
    private const val MAX_SOURCE_FRAMES = 1_000_000

    suspend fun prepare(stream: ResolvedStream, startSeconds: Int, presetA: ProcessingPreset, presetB: ProcessingPreset,
        outputRate: Int, relativeVolume: Double = 1.0): ComparisonAudio = withContext(Dispatchers.Default) {
        require(startSeconds in 0..86_400) { "Invalid selection start." }
        val source = withContext(Dispatchers.IO) { decode(stream, startSeconds, outputRate) }
        currentCoroutineContext().ensureActive()
        val first = render(source, presetA, relativeVolume)
        currentCoroutineContext().ensureActive()
        val second = render(source, presetB, relativeVolume)
        ComparisonMatching.match(source.rate, first.first, second.first, first.second, second.second, source.warmupFrames)
    }

    suspend fun render(source: ComparisonSource, preset: ProcessingPreset, relativeVolume: Double = 1.0): Pair<DoubleArray, Int> {
        require(preset.rack.enabled || preset.audio.dspMode != DspMode.SYSTEM) {
            "Save a processing rack or software DSP preset for comparison."
        }
        val rack = if (preset.rack.enabled) preset.rack else ProcessingRack.legacy(preset.audio, preset.playback.monoAudio).copy(enabled = true)
        val impulse = if (preset.requiresImpulseResponse()) {
            require(preset.audio.dspConvIrPath.isNotBlank()) { "A preset impulse response is missing." }
            val file = File(preset.audio.dspConvIrPath)
            require(ProcessingPresetBundle.sha256(file) == preset.irSha256) { "A preset impulse response has changed." }
            ImpulseResponse.loadWavResult(file).getOrThrow()
        } else null
        ProcessingPresetAssets.validateFiles(preset.rackImpulseAssets)
        val impulses = preset.rackImpulseAssets.associate { entry ->
            entry.id to ImpulseResponse.loadWavResult(File(entry.prepared?.path ?: entry.sourcePath)).getOrThrow()
        }
        val graph = ProductionSerialRack.compile(rack, source.rate, impulse, impulses, relativeVolume)
        require(graph.convolutionUnavailableReason == null) { "A preset impulse response is unavailable." }
        val block = AudioBlock(AudioStreamFormat(source.rate, ChannelLayout.STEREO), ProductionSerialRack.INPUT_FRAMES)
        val result = DoubleArray(source.samples.size + graph.tailFrames * 2 + graph.latencyFrames * 2 + 4096)
        var written = 0
        fun drain(): Boolean {
            var progress = false
            while (true) {
                val output = graph.getOutput() ?: break
                require(written + output.sampleCount <= result.size) { "Preset tail exceeds the comparison limit." }
                output.samples.copyInto(result, written, 0, output.sampleCount)
                written += output.sampleCount
                progress = true
            }
            return progress
        }
        var frame = 0
        while (frame * 2 < source.samples.size) {
            currentCoroutineContext().ensureActive()
            val count = minOf(block.capacityFrames, source.samples.size / 2 - frame)
            block.begin(count, frame * 1_000_000L / source.rate, frame.toLong())
            source.samples.copyInto(block.samples, 0, frame * 2, (frame + count) * 2)
            while (!graph.queueInput(block)) check(drain()) { "Comparison processing stalled." }
            frame += count
            drain()
        }
        graph.queueEndOfStream()
        while (!graph.isEnded) {
            currentCoroutineContext().ensureActive()
            check(drain() || graph.isEnded) { "Comparison tail did not drain." }
        }
        return result.copyOf(minOf(written, source.samples.size + graph.latencyFrames * 2)) to graph.latencyFrames
    }

    // two seconds before the selection settle the processing; the selection itself runs fifteen seconds
    suspend fun decode(stream: ResolvedStream, startSeconds: Int, outputRate: Int): ComparisonSource {
        val interrupt = AtomicBoolean()
        val decoder = FfmpegDecoder.open(stream.url, HttpOptions(stream.headers, stream.userAgent), interrupt)
        try {
            val rate = decoder.info.sampleRate
            require(rate in 8_000..384_000) { "Comparison supports audio up to 384 kHz." }
            val decodeStart = maxOf(0, startSeconds - 2)
            if (decodeStart > 0) decoder.seek(decodeStart * 1000L)
            val wanted = minOf((startSeconds - decodeStart + 15) * rate, MAX_SOURCE_FRAMES)
            val data = DoubleArray(wanted * 2)
            var frames = 0
            val block = DoubleArray(4096 * 2)
            while (frames < wanted) {
                currentCoroutineContext().ensureActive()
                val count = decoder.read(block, minOf(4096, wanted - frames))
                if (count < 0) break
                block.copyInto(data, frames * 2, 0, count * 2)
                frames += count
            }
            require(frames >= rate * 2) { "The selection needs at least one second of audio." }
            require(data.all { it.isFinite() }) { "The source contains invalid audio samples." }
            val warmup = (startSeconds - decodeStart) * rate
            val samples = data.copyOf(frames * 2)
            if (rate == outputRate) return ComparisonSource(rate, samples, warmup)
            return ComparisonSource(outputRate, resample(samples, rate, outputRate), (warmup.toLong() * outputRate / rate).toInt())
        } finally {
            decoder.close()
        }
    }

    private fun resample(samples: DoubleArray, from: Int, to: Int): DoubleArray {
        val resampler = BandlimitedResampler(from, to)
        val frames = samples.size / 2
        val out = DoubleArray(((frames.toLong() * to / from) + 16).toInt() * 2)
        var offset = 0
        var produced = 0
        val chunk = DoubleArray(4096 * 2)
        while (offset < frames || !resampler.isEnded) {
            if (offset < frames) {
                offset += resampler.queueInput(samples, offset, frames - offset)
                if (offset >= frames) resampler.queueEndOfInput()
            }
            val read = resampler.readOutput(chunk, 0, minOf(4096, out.size / 2 - produced))
            if (read <= 0 && offset >= frames && (resampler.isEnded || produced >= out.size / 2)) break
            chunk.copyInto(out, produced * 2, 0, read * 2)
            produced += read
        }
        return out.copyOf(produced * 2)
    }
}

class ComparisonPlayback(private val backend: OutputBackend, private val deviceId: String?, private val audio: ComparisonAudio,
    private val onInvalidated: (String) -> Unit) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val mixer = ComparisonMixer(audio)
    @Volatile private var requestedA = true
    @Volatile private var revision = 0L
    private var worker: Thread? = null
    private val ready = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Unit>()
    @Volatile var invalidationReason: String? = null
        private set

    fun start() {
        check(running.compareAndSet(false, true))
        worker = Thread({
            var output: AudioOutput? = null
            try {
                output = backend.open(deviceId, false, audio.rate, OutputEncoding.F32, 100)
                output.resume()
                val frames = 256
                val block = FloatArray(frames * 2)
                val bytes = ByteBuffer.allocate(block.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                var seen = -1L
                ready.complete(Unit)
                while (running.get()) {
                    val next = revision
                    if (next != seen) { mixer.select(requestedA); seen = next }
                    mixer.render(block, frames)
                    bytes.clear()
                    block.forEach(bytes::putFloat)
                    var written = 0
                    while (written < bytes.capacity() && running.get()) {
                        val count = output.write(bytes.array(), written, bytes.capacity() - written, 200)
                        check(count >= 0) { "Comparison output stopped accepting audio." }
                        written += count
                    }
                }
            } catch (failure: Exception) {
                if (running.get()) invalidate(failure.message ?: "Comparison output failed.")
            } finally {
                output?.let { runCatching { it.pause(); it.flush() }; runCatching { it.close() } }
                if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(invalidationReason ?: "Comparison output stopped."))
                closed.complete(Unit)
            }
        }, "aurora-comparison").apply { isDaemon = true; start() }
    }

    fun select(a: Boolean) { requestedA = a; revision++ }

    suspend fun awaitReady() {
        withTimeoutOrNull(5000) { ready.await(); true } ?: error("The comparison output did not become ready.")
    }

    fun whenClosed(action: () -> Unit) {
        closed.invokeOnCompletion { java.awt.EventQueue.invokeLater(action) }
    }

    private fun invalidate(reason: String) {
        if (running.compareAndSet(true, false)) {
            invalidationReason = reason
            ready.completeExceptionally(IllegalStateException(reason))
            onInvalidated(reason)
        }
    }

    override fun close() {
        running.set(false)
        if (worker == null) closed.complete(Unit)
    }
}
