package com.aurora.music.playback.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

// front-of-house bus compressor whose threshold rides the running programme level, so a quiet master gets squashed like a loud one
class LiveCompressor {
    private var fast = 0.0
    private var slow = 0.0
    private var attack = 0.0; private var release = 0.0; private var rise = 0.0; private var fall = 0.0
    private var headroomDb = 12.0; private var slope = 0.0; private var makeupDb = 0.0

    fun configure(rate: Int, ratio: Double, headroomDb: Double, releaseSec: Double, makeupDb: Double) {
        attack = coeff(0.01, rate); release = coeff(releaseSec, rate); rise = coeff(0.4, rate); fall = coeff(2.5, rate)
        this.headroomDb = headroomDb; slope = 1 - 1 / ratio.coerceAtLeast(1.0); this.makeupDb = makeupDb
    }

    fun reset() { fast = 0.0; slow = 0.0 }

    // the running programme level (rms of the louder channel)
    fun level() = kotlin.math.sqrt(slow)

    fun copyStateFrom(previous: LiveCompressor) { fast = previous.fast; slow = previous.slow }

    // gain change in dB for this frame
    fun next(left: Double, right: Double): Double {
        val power = max(left * left, right * right)
        fast += (if (power > fast) attack else release) * (power - fast)
        slow += (if (power > slow) rise else fall) * (power - slow)
        if (slow < SILENCE) return makeupDb
        val over = DB * ln(fast / slow) - headroomDb
        return makeupDb - if (over > 0) over * slope else 0.0
    }

    private fun coeff(sec: Double, rate: Int) = 1 - exp(-1 / (sec * rate))

    companion object {
        private const val SILENCE = 1e-8
        private val DB = 10 / ln(10.0)
    }
}
