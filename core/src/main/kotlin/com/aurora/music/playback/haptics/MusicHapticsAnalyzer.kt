package com.aurora.music.playback.haptics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class HapticKind { KICK, SNARE, HAT }

// finds kick, snare and hat hits plus held bass in decoded audio, stamped with the media time they will be heard
class MusicHapticsAnalyzer(private val sink: Sink) {
    interface Sink {
        fun onTap(timeUs: Long, kind: HapticKind, strength: Float)
        fun onTexture(timeUs: Long, level: Float)
    }

    private var rate = 0
    private var factor = 1
    private var hopSize = 1
    private val lowA = Biquad()
    private val lowB = Biquad()
    private val lowC = Biquad()
    private val midA = Biquad()
    private val midB = Biquad()
    private val highA = Biquad()
    private val highB = Biquad()
    private val bands = arrayOf(
        Band(HapticKind.KICK, window = 4, minRiseDb = 5.0, refractoryUs = 110_000, lagUs = 20_000),
        Band(HapticKind.SNARE, window = 2, minRiseDb = 6.0, refractoryUs = 90_000, lagUs = 13_000),
        Band(HapticKind.HAT, window = 2, minRiseDb = 6.0, refractoryUs = 70_000, lagUs = 9_000),
    )
    private val held = DoubleArray(SUSTAIN_HOPS)
    private var heldCount = 0
    private var decimated = 0.0
    private var decimatedCount = 0
    private var hopCount = 0
    private var lowSum = 0.0
    private var midSum = 0.0
    private var highSum = 0.0
    private var hops = 0L

    fun reset() {
        listOf(lowA, lowB, lowC, midA, midB, highA, highB).forEach { it.clear() }
        bands.forEach { it.reset() }
        heldCount = 0
        decimated = 0.0
        decimatedCount = 0
        hopCount = 0
        lowSum = 0.0
        midSum = 0.0
        highSum = 0.0
        hops = 0
    }

    // firstTimeUs is the media time of samples[offset]
    fun feed(samples: FloatArray, offset: Int, count: Int, sampleRate: Int, firstTimeUs: Long) {
        if (sampleRate <= 0) return
        if (sampleRate != rate) configure(sampleRate)
        for (i in 0 until count) {
            decimated += samples[offset + i]
            if (++decimatedCount < factor) continue
            val x = decimated / factor
            decimated = 0.0
            decimatedCount = 0
            val low = lowC.run(lowB.run(lowA.run(x)))
            val mid = midB.run(midA.run(x))
            val high = highB.run(highA.run(x))
            lowSum += low * low
            midSum += mid * mid
            highSum += high * high
            if (++hopCount == hopSize) hop(firstTimeUs + (i + 1) * 1_000_000L / sampleRate)
        }
    }

    private fun configure(sampleRate: Int) {
        reset()
        rate = sampleRate
        factor = max(1, sampleRate / 40_000)
        val fs = sampleRate.toDouble() / factor
        hopSize = max(1, (fs * HOP_SECONDS).toInt())
        lowA.lowPass(fs, 100.0)
        lowB.lowPass(fs, 100.0)
        lowC.lowPass(fs, 100.0)
        midA.highPass(fs, 1_200.0)
        midB.lowPass(fs, 5_000.0)
        highA.highPass(fs, 7_000.0)
        highB.highPass(fs, 7_000.0)
    }

    private fun hop(endUs: Long) {
        val n = hopSize.toDouble()
        bands[0].hop(lowSum / n, endUs)
        bands[1].hop(midSum / n, endUs)
        bands[2].hop(highSum / n, endUs)
        lowSum = 0.0
        midSum = 0.0
        highSum = 0.0
        hopCount = 0
        held[(hops % SUSTAIN_HOPS).toInt()] = bands[0].db
        heldCount = min(heldCount + 1, SUSTAIN_HOPS)
        if (++hops % TEXTURE_EVERY_HOPS == 0L && heldCount == SUSTAIN_HOPS) texture(endUs)
    }

    // a held bass note stays level across the whole window, a kick's decay does not
    private fun texture(endUs: Long) {
        var floor = Double.MAX_VALUE
        var peak = -Double.MAX_VALUE
        for (db in held) {
            floor = min(floor, db)
            peak = max(peak, db)
        }
        val reference = bands[0].longTerm
        val level = if (reference.isNaN() || floor < TEXTURE_FLOOR_DB) 0.0 else {
            val loud = ((floor - (reference - 8)) / 10).coerceIn(0.0, 1.0)
            val steady = (1 - (peak - floor - 1.5) / 5).coerceIn(0.0, 1.0)
            loud * steady
        }
        sink.onTexture(endUs - SUSTAIN_HOPS * HOP_SECONDS_US / 2, level.toFloat())
    }

    private inner class Band(
        private val kind: HapticKind,
        private val window: Int,
        private val minRiseDb: Double,
        private val refractoryUs: Long,
        private val lagUs: Long,
    ) {
        private val energies = DoubleArray(window)
        private val history = DoubleArray(LOOKBACK_HOPS)
        private var filled = 0
        private var historyCount = 0
        private var cursor = 0
        private var mean = 0.0
        private var deviation = 1.0
        private var lastOnset = Long.MIN_VALUE / 2
        private var previousOdf = 0.0
        private var olderOdf = 0.0
        private var previousThreshold = Double.MAX_VALUE
        private var previousDb = SILENCE_DB
        private var previousUs = 0L
        var db = SILENCE_DB
            private set
        var longTerm = Double.NaN
            private set
        private var hitLevel = Double.NaN
        private var pendingUs = Long.MIN_VALUE
        private var pendingOdf = 0.0
        private var pendingThreshold = 0.0
        private var pendingPeak = SILENCE_DB
        private var pendingHops = 0

        fun reset() {
            energies.fill(0.0)
            filled = 0
            historyCount = 0
            cursor = 0
            mean = 0.0
            deviation = 1.0
            lastOnset = Long.MIN_VALUE / 2
            previousOdf = 0.0
            olderOdf = 0.0
            previousThreshold = Double.MAX_VALUE
            previousDb = SILENCE_DB
            db = SILENCE_DB
            longTerm = Double.NaN
            hitLevel = Double.NaN
            pendingUs = Long.MIN_VALUE
        }

        fun hop(energy: Double, endUs: Long) {
            energies[cursor % window] = energy
            cursor++
            filled = min(filled + 1, window)
            var sum = 0.0
            for (k in 0 until filled) sum += energies[k]
            db = max(SILENCE_DB, 10 * log10(sum / filled + 1e-15))
            var floor = Double.MAX_VALUE
            for (k in 0 until historyCount) floor = min(floor, history[k])
            val odf = if (historyCount == 0) 0.0 else max(0.0, db - floor)
            history[(cursor - 1) % LOOKBACK_HOPS] = db
            historyCount = min(historyCount + 1, LOOKBACK_HOPS)
            val threshold = max(minRiseDb, mean + SPREAD * deviation)
            // a hit is confirmed once its attack has peaked, which the audio buffer ahead of the speaker easily covers
            if (pendingUs != Long.MIN_VALUE) {
                pendingPeak = max(pendingPeak, db)
                if (++pendingHops >= PEAK_HOPS) {
                    onset(pendingUs, pendingOdf, pendingThreshold, pendingPeak)
                    pendingUs = Long.MIN_VALUE
                }
            }
            if (previousOdf > previousThreshold && previousOdf >= olderOdf && previousOdf > odf && pendingUs == Long.MIN_VALUE &&
                previousUs - lagUs - lastOnset >= refractoryUs) {
                pendingUs = previousUs
                pendingOdf = previousOdf
                pendingThreshold = previousThreshold
                pendingPeak = max(previousDb, db)
                pendingHops = 0
            }
            val clipped = min(odf, mean + 4 * deviation)
            mean += (clipped - mean) * STATS_RATE
            deviation += (abs(clipped - mean) - deviation) * STATS_RATE
            if (db > SILENCE_DB + 10) longTerm = if (longTerm.isNaN()) db else longTerm + (db - longTerm) * LONG_TERM_RATE
            if (!hitLevel.isNaN() && hitLevel > longTerm) hitLevel -= HIT_RELEASE_DB
            olderOdf = previousOdf
            previousOdf = odf
            previousThreshold = threshold
            previousDb = db
            previousUs = endUs
        }

        // hits are judged against this band's recent hits, so another drum's bleed stays below the gate
        private fun onset(atUs: Long, odf: Double, threshold: Double, peakDb: Double) {
            val timeUs = atUs - lagUs
            if (timeUs - lastOnset < refractoryUs) return
            val reference = if (hitLevel.isNaN()) peakDb else hitLevel
            if (peakDb < AUDIBLE_DB || peakDb < reference - GATE_DB) return
            lastOnset = timeUs
            hitLevel = if (hitLevel.isNaN()) peakDb else hitLevel + (peakDb - hitLevel) * HIT_RATE
            val loud = ((peakDb - (reference - 15)) / 15).coerceIn(0.0, 1.0)
            val sharp = ((odf - threshold) / (threshold + 3)).coerceIn(0.0, 1.0)
            sink.onTap(timeUs, kind, (0.2 + 0.55 * loud + 0.25 * sharp).toFloat().coerceIn(0f, 1f))
        }
    }

    private class Biquad {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var z1 = 0.0
        private var z2 = 0.0

        fun lowPass(fs: Double, hz: Double) = design(fs, hz, low = true)
        fun highPass(fs: Double, hz: Double) = design(fs, hz, low = false)

        private fun design(fs: Double, hz: Double, low: Boolean) {
            val w = 2 * PI * min(hz, fs * 0.45) / fs
            val alpha = sin(w) / (2 * Q)
            val c = cos(w)
            val a0 = 1 + alpha
            if (low) {
                b0 = (1 - c) / 2 / a0
                b1 = (1 - c) / a0
            } else {
                b0 = (1 + c) / 2 / a0
                b1 = -(1 + c) / a0
            }
            b2 = b0
            a1 = -2 * c / a0
            a2 = (1 - alpha) / a0
            clear()
        }

        fun clear() {
            z1 = 0.0
            z2 = 0.0
        }

        fun run(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    private companion object {
        const val HOP_SECONDS = 0.005
        const val HOP_SECONDS_US = 5_000L
        const val LOOKBACK_HOPS = 4
        const val PEAK_HOPS = 6
        const val SUSTAIN_HOPS = 32
        const val TEXTURE_EVERY_HOPS = 4L
        const val SPREAD = 1.5
        const val STATS_RATE = 1.0 / 300
        const val LONG_TERM_RATE = 1.0 / 1200
        const val SILENCE_DB = -120.0
        const val AUDIBLE_DB = -60.0
        const val GATE_DB = 14.0
        const val HIT_RATE = 0.2
        const val HIT_RELEASE_DB = 0.005
        const val TEXTURE_FLOOR_DB = -45.0
        val Q = 1 / sqrt(2.0)
    }
}
