package com.aurora.music.playback.engine

import com.aurora.music.playback.ImpulseResponse
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

// a measured room response in place of the modelled reflections and tail; its direct sound is cut (the model supplies that)
// and it starts one convolver block after the peak, so the block latency lands every reflection where it was measured
class MeasuredRoom private constructor(val convolver: PrecisionConvolver, val energy: Double) {
    companion object {
        const val BLOCK = 128

        fun prepare(impulse: ImpulseResponse, rate: Int): MeasuredRoom {
            val channels = impulse.matrixChannels().map { BandlimitedResampler.resampleImpulse(it, impulse.sampleRate, rate) }
            val left = channels.first(); val right = channels.last()
            var peakAt = 0; var peak = 0.0
            for (ch in listOf(left, right)) for (i in ch.indices) if (abs(ch[i]) > peak) { peak = abs(ch[i]); peakAt = i }
            require(peak > 0) { "Impulse response is silent" }
            val start = peakAt + max(BLOCK, (0.0025 * rate).toInt())
            val fade = (0.001 * rate).toInt().coerceAtLeast(1)
            fun trim(ch: DoubleArray): DoubleArray {
                val n = min(ch.size - start, PrecisionConvolver.MAX_IR_FRAMES)
                require(n > fade) { "Impulse response has no room after its direct sound" }
                return DoubleArray(n) { i -> ch[start + i] * min(1.0, i.toDouble() / fade) }
            }
            val l = trim(left); val r = trim(right)
            val energy = (musicEnergy(l, rate) + musicEnergy(r, rate)) / 2
            require(energy > 0) { "Impulse response has no room after its direct sound" }
            return MeasuredRoom(PrecisionConvolver(l, r, BLOCK), energy)
        }

        // what a warm, music-like signal draws from the room, in white-noise terms, so the room plays as loud as the modelled one
        private fun musicEnergy(ch: DoubleArray, rate: Int): Double {
            val k = 1 - exp(-2 * PI * 1000.0 / rate)
            var y = 0.0; var sum = 0.0
            for (x in ch) { y += k * (x - y); sum += y * y }
            return sum * (2 - k) / k
        }
    }
}
