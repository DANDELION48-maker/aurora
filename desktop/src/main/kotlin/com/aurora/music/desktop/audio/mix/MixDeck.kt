package com.aurora.music.desktop.audio.mix

import com.aurora.music.desktop.audio.ResolvedStream
import com.aurora.music.desktop.audio.decode.FfmpegDecoder
import com.aurora.music.desktop.audio.decode.HttpOptions
import com.aurora.music.mix.MixClip
import com.aurora.music.playback.engine.BandlimitedResampler
import com.aurora.music.playback.engine.BiquadCoefficients
import com.aurora.music.playback.engine.PrecisionDspCoeffBuilder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// waveform-similarity overlap-add: segments are taken from the input at the tempo's pace, each nudged within a small
// window to line up with where the previous one left off, then crossfaded, so tempo changes without changing pitch
internal class TimeStretcher(rate: Int) {
    private val hop = (rate * 0.030).roundToInt().coerceAtLeast(64)
    private val overlap = (rate * 0.010).roundToInt().coerceAtLeast(16)
    private val window = (rate * 0.012).roundToInt().coerceAtLeast(8)
    private var input = DoubleArray(rate * 2)
    private var inputFrames = 0
    private var position = 0.0
    private var pending: DoubleArray? = null
    var tempo = 1.0

    fun needs(): Int = (position.toInt() + window * 2 + hop + overlap + 1 - inputFrames).coerceAtLeast(0)

    fun push(samples: DoubleArray, frames: Int) {
        if ((inputFrames + frames) * 2 > input.size) input = input.copyOf(max(input.size * 2, (inputFrames + frames) * 2))
        samples.copyInto(input, inputFrames * 2, 0, frames * 2)
        inputFrames += frames
    }

    // appends up to one hop of stretched audio to out, returning the frames written, or zero when more input is needed
    fun step(out: DoubleArray, offset: Int): Int {
        if (needs() > 0) return 0
        val nominal = position.toInt().coerceAtLeast(window)
        val previous = pending
        var best = nominal
        if (previous != null) {
            var bestScore = Double.NEGATIVE_INFINITY
            for (candidate in nominal - window..nominal + window) {
                var dot = 0.0; var energy = 0.0
                for (i in 0 until overlap * 2) {
                    val v = input[candidate * 2 + i]
                    dot += v * previous[i]; energy += v * v
                }
                val score = dot / sqrt(energy + 1e-12)
                if (score > bestScore) { bestScore = score; best = candidate }
            }
        }
        for (frame in 0 until hop) for (channel in 0..1) {
            val value = input[(best + frame) * 2 + channel]
            out[(offset + frame) * 2 + channel] = if (previous != null && frame < overlap) {
                val fade = (frame + 0.5) / overlap
                previous[frame * 2 + channel] * (1 - fade) + value * fade
            } else value
        }
        pending = input.copyOfRange((best + hop) * 2, (best + hop + overlap) * 2)
        position += hop * tempo
        val drop = (min(position.toInt(), best) - window).coerceAtLeast(0)
        if (drop > hop * 4) {
            input.copyInto(input, 0, drop * 2, inputFrames * 2)
            inputFrames -= drop
            position -= drop
        }
        return hop
    }

    val hopFrames: Int get() = hop
}

// catmull-rom interpolation at an arbitrary ratio, for the pitch half of a speed and pitch change
internal class CubicResampler {
    private var buffer = DoubleArray(8192)
    private var frames = 0
    private var position = 1.0
    var step = 1.0

    fun push(samples: DoubleArray, count: Int, offset: Int = 0) {
        if ((frames + count) * 2 > buffer.size) buffer = buffer.copyOf(max(buffer.size * 2, (frames + count) * 2))
        samples.copyInto(buffer, frames * 2, offset * 2, (offset + count) * 2)
        frames += count
    }

    fun read(out: DoubleArray, offset: Int, max: Int): Int {
        var produced = 0
        while (produced < max && position + 2 < frames) {
            val i = position.toInt()
            val t = position - i
            for (c in 0..1) {
                val p0 = buffer[(i - 1) * 2 + c]; val p1 = buffer[i * 2 + c]; val p2 = buffer[(i + 1) * 2 + c]; val p3 = buffer[(i + 2) * 2 + c]
                out[(offset + produced) * 2 + c] = p1 + 0.5 * t * (p2 - p0 + t * (2 * p0 - 5 * p1 + 4 * p2 - p3 + t * (3 * (p1 - p2) + p3 - p0)))
            }
            produced++
            position += step
        }
        val drop = (position.toInt() - 2).coerceAtLeast(0)
        if (drop > 4096) {
            buffer.copyInto(buffer, 0, drop * 2, frames * 2)
            frames -= drop
            position -= drop
        }
        return produced
    }
}

private class Biquad(var c: BiquadCoefficients) {
    private val x1 = DoubleArray(2); private val x2 = DoubleArray(2); private val y1 = DoubleArray(2); private val y2 = DoubleArray(2)
    fun tick(input: Double, channel: Int): Double {
        val output = c.b0 * input + c.b1 * x1[channel] + c.b2 * x2[channel] - c.a1 * y1[channel] - c.a2 * y2[channel]
        x2[channel] = x1[channel]; x1[channel] = input; y2[channel] = y1[channel]; y1[channel] = output
        return output
    }
}

/** One clip's audio from its source to the mix bus, at the output rate. */
internal class MixDeck(clip: MixClip, private val source: ResolvedStream, private val outputRate: Int, startOffsetSec: Float) : AutoCloseable {
    var clip: MixClip = clip
        set(value) { field = value; configure() }
    private val interrupt = AtomicBoolean()
    private val decoder = FfmpegDecoder.open(source.url, HttpOptions(source.headers, source.userAgent), interrupt)
    private val rate = decoder.info.sampleRate
    private val stretcher = TimeStretcher(rate)
    private val cubic = CubicResampler()
    private var bandlimited: BandlimitedResampler? = null
    private val block = DoubleArray(4096 * 2)
    private val stretched = DoubleArray(8192 * 2)
    private var sourceFrames = 0L
    private val endFrame: Long
    private var finished = false
    private val bass = Biquad(BiquadCoefficients.IDENTITY)
    private val mid = Biquad(BiquadCoefficients.IDENTITY)
    private val treble = Biquad(BiquadCoefficients.IDENTITY)
    private var preamp = 1.0
    private var leftGain = 1.0
    private var rightGain = 1.0
    var bassCutDb = 0f
        set(value) { if (field != value) { field = value; configure() } }
    private var modeKey = Pair(1.0, 1.0)
    private var pendingCount = 0
    private var pendingOffset = 0
    private var endQueued = false

    init {
        val start = clip.cueInSec + startOffsetSec * clip.speed
        if (start > 0.05f) decoder.seek((start * 1000).toLong())
        sourceFrames = (start * rate).toLong()
        endFrame = (clip.cueOutSec * rate).toLong()
        configure()
    }

    private fun configure() {
        val c = clip
        bass.c = PrecisionDspCoeffBuilder.band(1, 150.0, (c.bassDb + bassCutDb).toDouble(), 0.7, outputRate)
        mid.c = PrecisionDspCoeffBuilder.band(0, 1200.0, c.midDb.toDouble(), 0.7, outputRate)
        treble.c = PrecisionDspCoeffBuilder.band(2, 6000.0, c.trebleDb.toDouble(), 0.7, outputRate)
        preamp = 10.0.pow(-max(0f, max(c.bassDb, max(c.midDb, c.trebleDb))) / 20.0)
        leftGain = if (c.pan > 0) 1.0 - c.pan else 1.0
        rightGain = if (c.pan < 0) 1.0 + c.pan else 1.0
        val pitch = 2.0.pow(c.pitchSemitones / 12.0)
        val speed = c.speed.toDouble()
        modeKey = speed to pitch
        stretcher.tempo = speed / pitch
        cubic.step = pitch * rate / outputRate
    }

    private val plain: Boolean get() = abs(modeKey.first - 1.0) < 1e-6 && abs(modeKey.second - 1.0) < 1e-6

    // decoded frames up to the clip's cue-out; -1 once the source has nothing more
    private fun decode(): Int {
        if (finished) return -1
        val allowed = (endFrame - sourceFrames).coerceAtMost(4096L).toInt()
        if (allowed <= 0) { finished = true; return -1 }
        val read = decoder.read(block, allowed)
        if (read < 0) { finished = true; return -1 }
        sourceFrames += read
        return read
    }

    /** Fills out with the next frames of the clip, already equalised and panned; returns false once the clip has ended. */
    fun render(out: DoubleArray, frames: Int): Boolean {
        var produced = 0
        if (plain) {
            if (rate == outputRate) {
                while (produced < frames) {
                    val want = min(frames - produced, 4096)
                    if (finished) break
                    val allowed = (endFrame - sourceFrames).coerceAtMost(want.toLong()).toInt()
                    if (allowed <= 0) { finished = true; break }
                    val read = decoder.read(block, allowed)
                    if (read < 0) { finished = true; break }
                    block.copyInto(out, produced * 2, 0, read * 2)
                    sourceFrames += read
                    produced += read
                }
            } else {
                val resampler = bandlimited ?: BandlimitedResampler(rate, outputRate).also { bandlimited = it }
                while (produced < frames) {
                    produced += resampler.readOutput(out, produced, frames - produced)
                    if (produced >= frames || resampler.isEnded) break
                    if (pendingOffset < pendingCount) {
                        val queued = resampler.queueInput(block, pendingOffset, pendingCount - pendingOffset)
                        pendingOffset += queued
                        if (queued > 0) continue
                        if (resampler.readOutput(out, produced, 0) == 0 && queued == 0) break
                    }
                    if (endQueued) break
                    val decoded = decode()
                    if (decoded < 0) { resampler.queueEndOfInput(); endQueued = true } else { pendingCount = decoded; pendingOffset = 0 }
                }
            }
        } else {
            while (produced < frames) {
                val read = cubic.read(out, produced, frames - produced)
                produced += read
                if (produced >= frames) break
                val made = stretcher.step(stretched, 0)
                if (made > 0) { cubic.push(stretched, made); continue }
                val decoded = decode()
                if (decoded < 0) break
                stretcher.push(block, decoded)
            }
        }
        for (i in produced until frames) { out[i * 2] = 0.0; out[i * 2 + 1] = 0.0 }
        for (i in 0 until produced) {
            var left = out[i * 2] * preamp
            var right = out[i * 2 + 1] * preamp
            left = treble.tick(mid.tick(bass.tick(left, 0), 0), 0) * leftGain
            right = treble.tick(mid.tick(bass.tick(right, 1), 1), 1) * rightGain
            out[i * 2] = left.coerceIn(-CEILING, CEILING)
            out[i * 2 + 1] = right.coerceIn(-CEILING, CEILING)
        }
        return produced == frames || !finished
    }

    override fun close() {
        interrupt.set(true)
        runCatching { decoder.close() }
    }

    private companion object {
        val CEILING = 10.0.pow(-0.5 / 20.0)
    }
}
