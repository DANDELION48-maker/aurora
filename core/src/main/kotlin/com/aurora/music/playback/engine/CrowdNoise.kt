package com.aurora.music.playback.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// the crowd around you: a murmur of formant-filtered voices, plus applause and a roar when a loud song stops;
// levels follow a slow memory of how loud the show is, so it sits right under any master
class CrowdNoise {
    var left = 0.0
        private set
    var right = 0.0
        private set
    private var rate = 48_000
    private var seed = SEED
    private val voiceCoef = DoubleArray(VOICES * 3)
    private val voiceState = DoubleArray(VOICES * 2)
    private val voiceLevel = DoubleArray(VOICES)
    private val voiceTarget = DoubleArray(VOICES)
    private val voiceTimer = IntArray(VOICES)
    private val voicePan = DoubleArray(VOICES)
    private val clapEnv = DoubleArray(CLAPPERS)
    private val clapTimer = IntArray(CLAPPERS)
    private val band = DoubleArray(10)
    private val bandState = DoubleArray(8)
    private var show = 0.0; private var now = 0.0; private var cheer = 0.0
    private var loudFrames = 0; private var cheerFrames = 0
    private var showRise = 0.0; private var showFall = 0.0; private var nowCoef = 0.0
    private var cheerUp = 0.0; private var cheerDown = 0.0; private var syllable = 0.0; private var clapDecay = 0.0

    fun configure(sampleRate: Int) {
        rate = sampleRate
        showRise = coeff(2.0); showFall = coeff(40.0); nowCoef = coeff(0.3)
        cheerUp = coeff(0.6); cheerDown = coeff(2.5); syllable = coeff(0.03); clapDecay = exp(-1 / (0.006 * rate))
        bandPass(band, 0, 1500.0, 0.8)
        bandPass(band, 5, 900.0, 0.6)
        reset()
    }

    fun reset() {
        seed = SEED
        voiceState.fill(0.0); voiceLevel.fill(0.0); voiceTarget.fill(0.0); voiceTimer.fill(0)
        clapEnv.fill(0.0); clapTimer.fill(0); bandState.fill(0.0)
        show = 0.0; now = 0.0; cheer = 0.0; loudFrames = 0; cheerFrames = 0; left = 0.0; right = 0.0
        for (v in 0 until VOICES) { voicePan[v] = 0.1 + 0.8 * uniform(); setVoice(v, 600.0) }
    }

    fun copyStateFrom(previous: CrowdNoise) {
        seed = previous.seed
        previous.voiceCoef.copyInto(voiceCoef); previous.voiceState.copyInto(voiceState); previous.voiceLevel.copyInto(voiceLevel)
        previous.voiceTarget.copyInto(voiceTarget); previous.voiceTimer.copyInto(voiceTimer); previous.voicePan.copyInto(voicePan)
        previous.clapEnv.copyInto(clapEnv); previous.clapTimer.copyInto(clapTimer); previous.bandState.copyInto(bandState)
        show = previous.show; now = previous.now; cheer = previous.cheer
        loudFrames = previous.loudFrames; cheerFrames = previous.cheerFrames; left = previous.left; right = previous.right
    }

    fun next(inL: Double, inR: Double, amount: Double) {
        val power = max(inL * inL, inR * inR)
        show += (if (power > show) showRise else showFall) * (power - show)
        now += nowCoef * (power - now)
        if (now > 0.25 * show) loudFrames++
        // a song that was loud for a while and then stops gets cheered
        if (cheerFrames == 0 && loudFrames > rate * 20 && now < 0.01 * show) { cheerFrames = rate * 5; loudFrames = 0 }
        val target = if (cheerFrames > 0) { cheerFrames--; 1.0 } else 0.0
        cheer += (target - cheer) * (if (target > cheer) cheerUp else cheerDown)
        val ref = sqrt(max(show, FLOOR)) * amount

        var l = 0.0; var r = 0.0
        for (v in 0 until VOICES) {
            if (--voiceTimer[v] <= 0) newSyllable(v)
            voiceLevel[v] += (voiceTarget[v] - voiceLevel[v]) * syllable
            val x = noise(); val c = v * 3; val s = v * 2
            val y = voiceCoef[c] * x + voiceState[s]
            voiceState[s] = -voiceCoef[c + 1] * y + voiceState[s + 1]
            voiceState[s + 1] = -voiceCoef[c] * x - voiceCoef[c + 2] * y
            val out = y * voiceLevel[v]
            l += out * (1 - voicePan[v]); r += out * voicePan[v]
        }
        l *= MURMUR * ref; r *= MURMUR * ref

        if (cheer > 1e-4) {
            var envL = 0.0; var envR = 0.0
            for (k in 0 until CLAPPERS) {
                if (--clapTimer[k] <= 0) { clapEnv[k] = 0.5 + 0.5 * uniform(); clapTimer[k] = (rate * (0.07 + 0.08 * uniform())).toInt() }
                clapEnv[k] *= clapDecay
                if (k and 1 == 0) envL += clapEnv[k] else envR += clapEnv[k]
            }
            val loud = cheer * ref
            l += (filter(0, 0, noise() * envL) * CLAP + filter(5, 4, noise()) * ROAR) * loud
            r += (filter(0, 2, noise() * envR) * CLAP + filter(5, 6, noise()) * ROAR) * loud
        }
        left = l; right = r
    }

    private fun newSyllable(v: Int) {
        val talking = uniform() < 0.7
        voiceTarget[v] = if (talking) 0.4 + 0.6 * uniform() else 0.0
        voiceTimer[v] = (rate * (0.09 + 0.2 * uniform())).toInt()
        if (talking) setVoice(v, 300 + 1500 * uniform())
    }

    private fun setVoice(v: Int, hz: Double) {
        val w = 2 * PI * hz / rate
        val alpha = sin(w) / (2 * 3.0); val a0 = 1 + alpha
        voiceCoef[v * 3] = alpha / a0; voiceCoef[v * 3 + 1] = -2 * cos(w) / a0; voiceCoef[v * 3 + 2] = (1 - alpha) / a0
    }

    private fun bandPass(f: DoubleArray, at: Int, hz: Double, q: Double) {
        val w = 2 * PI * hz / rate
        val alpha = sin(w) / (2 * q); val a0 = 1 + alpha
        f[at] = alpha / a0; f[at + 1] = 0.0; f[at + 2] = -alpha / a0; f[at + 3] = -2 * cos(w) / a0; f[at + 4] = (1 - alpha) / a0
    }

    private fun filter(at: Int, st: Int, x: Double): Double {
        val f = band; val s = bandState
        val y = f[at] * x + s[st]
        s[st] = f[at + 1] * x - f[at + 3] * y + s[st + 1]; s[st + 1] = f[at + 2] * x - f[at + 4] * y
        return y
    }

    private fun uniform(): Double {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 7); seed = seed xor (seed shl 17)
        return (seed ushr 11).toDouble() / (1L shl 53)
    }

    private fun noise() = (uniform() - 0.5) * 3.4641

    private fun coeff(sec: Double) = 1 - exp(-1 / (sec * rate))

    companion object {
        private const val VOICES = 8
        private const val CLAPPERS = 24
        private const val SEED = 0x2545F4914F6CDD1DL
        private const val FLOOR = 1e-4
        private const val MURMUR = 0.33
        private const val CLAP = 1.5
        private const val ROAR = 0.6
    }
}
