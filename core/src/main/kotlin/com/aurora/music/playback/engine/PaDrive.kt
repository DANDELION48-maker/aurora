package com.aurora.music.playback.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tanh

// a pa pushed hard: everything below 5 khz runs into a slightly lopsided soft clipper while the top passes clean, so nothing aliases;
// the input is scaled by the running programme level so a quiet master breaks up like a loud one
class PaDrive {
    var left = 0.0
        private set
    var right = 0.0
        private set
    private val lp = DoubleArray(5)
    private val state = DoubleArray(4)
    private val dcIn = DoubleArray(2)
    private val dcOut = DoubleArray(2)
    private var dcPole = 0.0

    fun configure(rate: Int) {
        val w = 2 * PI * minOf(SPLIT_HZ, rate * 0.4) / rate
        val alpha = sin(w) / (2 * 0.7071); val c = cos(w); val a0 = 1 + alpha
        lp[0] = (1 - c) / 2 / a0; lp[1] = (1 - c) / a0; lp[2] = lp[0]; lp[3] = -2 * c / a0; lp[4] = (1 - alpha) / a0
        dcPole = exp(-2 * PI * 5.0 / rate)
        reset()
    }

    fun reset() { state.fill(0.0); dcIn.fill(0.0); dcOut.fill(0.0); left = 0.0; right = 0.0 }

    fun copyStateFrom(previous: PaDrive) {
        previous.state.copyInto(state); previous.dcIn.copyInto(dcIn); previous.dcOut.copyInto(dcOut)
        left = previous.left; right = previous.right
    }

    fun process(l: Double, r: Double, amount: Double, level: Double) {
        val a = amount * KNEE / max(level, LEVEL_FLOOR)
        val makeup = 1 + MAKEUP * amount
        left = channel(0, l, a, amount) * makeup
        right = channel(1, r, a, amount) * makeup
    }

    private fun channel(ch: Int, x: Double, a: Double, amount: Double): Double {
        val s = ch * 2
        val low = lp[0] * x + state[s]
        state[s] = lp[1] * x - lp[3] * low + state[s + 1]; state[s + 1] = lp[2] * x - lp[4] * low
        var y = x
        if (a > 1e-6) {
            val b = BIAS * amount; val tb = tanh(b)
            y = (tanh(a * low + b) - tb) / (a * (1 - tb * tb)) + (x - low)
        }
        val out = y - dcIn[ch] + dcPole * dcOut[ch]
        dcIn[ch] = y; dcOut[ch] = out
        return out
    }

    companion object {
        private const val SPLIT_HZ = 5000.0
        private const val KNEE = 1.6
        private const val BIAS = 0.25
        private const val MAKEUP = 0.45
        private const val LEVEL_FLOOR = 0.02
    }
}
