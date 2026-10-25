package com.aurora.music.playback.engine

import com.aurora.music.data.ConcertSound
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// the crowd around you, reacting to the song: chatter that rises in the quiet bits, a cheer when the music kicks in after silence,
// cheers and singing along when a chorus lifts, a roar on a drop, shouts and whistles in a breakdown, applause when a song ends.
// what it does follows the genre's crowd; levels follow a slow memory of how loud the show is, so it sits right under any master
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
    private val band = DoubleArray(15)
    private val bandState = DoubleArray(12)
    private var sing = DoubleArray(1)
    private var singPos = 0
    private val lfoCos = DoubleArray(4)
    private val lfoSin = DoubleArray(4)
    private val lfoStepCos = DoubleArray(4)
    private val lfoStepSin = DoubleArray(4)
    private var vocalHp = 0.0
    private var low = 0.0
    private var show = 0.0; private var now = 0.0; private var recent = 0.0; private var bassNow = 0.0; private var bassRecent = 0.0
    private var loudFrames = 0; private var quietFrames = 0
    private var applauseFrames = 0; private var roarFrames = 0; private var liftFrames = 0; private var singFrames = 0
    private var shoutFrames = 0; private var introFrames = 0
    private var liftCool = 0; private var dropCool = 0; private var shoutCool = 0
    private var applause = 0.0; private var roar = 0.0; private var singing = 0.0; private var shout = 0.0
    private var whistlePhase = 0.0; private var whistleHz = 0.0; private var whistleEnv = 0.0; private var whistleLeft = 0
    private var showRise = 0.0; private var showFall = 0.0; private var nowK = 0.0; private var recentK = 0.0
    private var bassK = 0.0; private var bassRecentK = 0.0; private var lowK = 0.0; private var hpK = 0.0
    private var up = 0.0; private var down = 0.0; private var syllable = 0.0; private var clapDecay = 0.0; private var whistleDecay = 0.0
    private var chatter = 0.5; private var cheer = 0.8; private var singAlong = 0.5; private var roarAmount = 0.5; private var movement = 0.4

    fun configure(sampleRate: Int) {
        rate = sampleRate
        showRise = coeff(2.0); showFall = coeff(40.0); nowK = coeff(0.3); recentK = coeff(6.0)
        bassK = coeff(0.15); bassRecentK = coeff(3.0); lowK = 1 - exp(-2 * PI * 120.0 / rate); hpK = 1 - exp(-2 * PI * 250.0 / rate)
        up = coeff(0.4); down = coeff(2.0); syllable = coeff(0.03)
        clapDecay = exp(-1 / (0.006 * rate)); whistleDecay = exp(-1 / (0.12 * rate))
        bandPass(0, 1500.0, 0.8)
        bandPass(5, 900.0, 0.6)
        lowPass(10, 3500.0)
        var size = 1
        while (size < 0.06 * rate) size = size shl 1
        sing = DoubleArray(size)
        val hz = doubleArrayOf(0.23, 0.31, 0.43, 0.57)
        for (i in 0..3) { lfoStepCos[i] = cos(2 * PI * hz[i] / rate); lfoStepSin[i] = sin(2 * PI * hz[i] / rate) }
        reset()
    }

    fun personality(rig: ConcertSound) {
        chatter = rig.chatter.toDouble(); cheer = rig.cheer.toDouble(); singAlong = rig.singAlong.toDouble()
        roarAmount = rig.roar.toDouble(); movement = rig.movement.toDouble()
    }

    fun reset() {
        seed = SEED
        voiceState.fill(0.0); voiceLevel.fill(0.0); voiceTarget.fill(0.0); voiceTimer.fill(0)
        clapEnv.fill(0.0); clapTimer.fill(0); bandState.fill(0.0); sing.fill(0.0); singPos = 0
        for (i in 0..3) { lfoCos[i] = cos(i * 1.3); lfoSin[i] = sin(i * 1.3) }
        vocalHp = 0.0; low = 0.0; show = 0.0; now = 0.0; recent = 0.0; bassNow = 0.0; bassRecent = 0.0
        loudFrames = 0; quietFrames = 0; applauseFrames = 0; roarFrames = 0; liftFrames = 0; singFrames = 0; shoutFrames = 0; introFrames = 0
        liftCool = 0; dropCool = 0; shoutCool = 0; applause = 0.0; roar = 0.0; singing = 0.0; shout = 0.0
        whistlePhase = 0.0; whistleHz = 0.0; whistleEnv = 0.0; whistleLeft = 0; left = 0.0; right = 0.0
        for (v in 0 until VOICES) { voicePan[v] = 0.1 + 0.8 * uniform(); setVoice(v, 600.0) }
    }

    fun copyStateFrom(previous: CrowdNoise) {
        seed = previous.seed
        previous.voiceCoef.copyInto(voiceCoef); previous.voiceState.copyInto(voiceState); previous.voiceLevel.copyInto(voiceLevel)
        previous.voiceTarget.copyInto(voiceTarget); previous.voiceTimer.copyInto(voiceTimer); previous.voicePan.copyInto(voicePan)
        previous.clapEnv.copyInto(clapEnv); previous.clapTimer.copyInto(clapTimer); previous.bandState.copyInto(bandState)
        if (previous.sing.size == sing.size) previous.sing.copyInto(sing)
        previous.lfoCos.copyInto(lfoCos); previous.lfoSin.copyInto(lfoSin)
        singPos = previous.singPos; vocalHp = previous.vocalHp; low = previous.low
        show = previous.show; now = previous.now; recent = previous.recent; bassNow = previous.bassNow; bassRecent = previous.bassRecent
        loudFrames = previous.loudFrames; quietFrames = previous.quietFrames; applauseFrames = previous.applauseFrames
        roarFrames = previous.roarFrames; liftFrames = previous.liftFrames; singFrames = previous.singFrames
        shoutFrames = previous.shoutFrames; introFrames = previous.introFrames
        liftCool = previous.liftCool; dropCool = previous.dropCool; shoutCool = previous.shoutCool
        applause = previous.applause; roar = previous.roar; singing = previous.singing; shout = previous.shout
        whistlePhase = previous.whistlePhase; whistleHz = previous.whistleHz; whistleEnv = previous.whistleEnv; whistleLeft = previous.whistleLeft
        left = previous.left; right = previous.right
    }

    fun next(inL: Double, inR: Double, amount: Double) {
        val power = max(inL * inL, inR * inR)
        val mono = (inL + inR) * 0.5
        low += lowK * (mono - low)
        show += (if (power > show) showRise else showFall) * (power - show)
        now += nowK * (power - now); recent += recentK * (power - recent)
        bassNow += bassK * (low * low - bassNow); bassRecent += bassRecentK * (low * low - bassRecent)
        events()

        val ref = sqrt(max(show, FLOOR)) * amount
        val quiet = (1 - now / (0.2 * show + FLOOR)).coerceIn(0.0, 1.0)
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
        val murmur = MURMUR * 2 * chatter * (0.6 + quiet) * ref
        l *= murmur; r *= murmur

        // people moving and yelling along under a loud song, plus roars and cheers on the events
        val bed = BED * movement * min(1.5, now / (show + FLOOR))
        val roarLevel = roar * roarAmount + bed
        if (applause > 1e-4 || roarLevel > 1e-4) {
            var envL = 0.0; var envR = 0.0
            if (applause > 1e-4) for (k in 0 until CLAPPERS) {
                if (--clapTimer[k] <= 0) { clapEnv[k] = 0.5 + 0.5 * uniform(); clapTimer[k] = (rate * (0.07 + 0.08 * uniform())).toInt() }
                clapEnv[k] *= clapDecay
                if (k and 1 == 0) envL += clapEnv[k] else envR += clapEnv[k]
            }
            val claps = applause * cheer * CLAP * ref; val yell = roarLevel * ROAR * ref
            l += filter(0, 0, noise() * envL) * claps + filter(5, 4, noise()) * yell
            r += filter(0, 2, noise() * envR) * claps + filter(5, 6, noise()) * yell
        }

        // singing along: the song's own vocal band, smeared into many voices around you
        vocalHp += hpK * (mono - vocalHp)
        sing[singPos] = filter(10, 8, mono - vocalHp)
        if (singing > 1e-4) {
            val m = sing.size - 1
            var sl = 0.0; var sr = 0.0
            for (i in 0..3) {
                val c = lfoCos[i] * lfoStepCos[i] - lfoSin[i] * lfoStepSin[i]
                lfoSin[i] = lfoSin[i] * lfoStepCos[i] + lfoCos[i] * lfoStepSin[i]; lfoCos[i] = c
                val v = read(sing, m, singPos, (0.018 + 0.006 * i + 0.003 * lfoSin[i]) * rate)
                if (i and 1 == 0) sl += v else sr += v
            }
            val g = singing * singAlong * SING * amount
            l += sl * g; r += sr * g
        }
        singPos = (singPos + 1) and (sing.size - 1)

        if (shout > 1e-4) {
            if (whistleLeft <= 0 && uniform() < 2.0 / rate) { whistleLeft = (0.5 * rate).toInt(); whistleHz = 1900.0; whistleEnv = 1.0 }
            if (whistleLeft > 0) {
                whistleLeft--; whistleHz += 1200.0 / (0.5 * rate); whistlePhase += 2 * PI * whistleHz / rate
                if (whistlePhase > 2 * PI) whistlePhase -= 2 * PI
                val w = sin(whistlePhase) * whistleEnv * shout * cheer * WHISTLE * ref
                l += w * 0.7; r += w * 0.3
            } else whistleEnv *= whistleDecay
        }
        left = l; right = r
    }

    private fun events() {
        if (now > 0.25 * show) loudFrames++
        if (now < 0.003 * show) quietFrames++
        else {
            if (quietFrames > rate * 1.5 && now > 0.05 * show) introFrames = (rate * 2.5).toInt()
            quietFrames = 0
        }
        if (liftCool > 0) liftCool--
        if (dropCool > 0) dropCool--
        if (shoutCool > 0) shoutCool--
        if (applauseFrames == 0 && loudFrames > rate * 20 && now < 0.01 * show) {
            applauseFrames = rate * 5; roarFrames = max(roarFrames, rate * 2); loudFrames = 0
        }
        if (liftCool == 0 && now > 1.8 * recent && now > 0.3 * show && recent > 0.05 * show && loudFrames > rate * 6) { liftFrames = (rate * 2.5).toInt(); singFrames = rate * 14; liftCool = rate * 10 }
        if (dropCool == 0 && bassNow > 4 * bassRecent && bassRecent < 0.15 * recent && now > 0.3 * show) { roarFrames = rate * 3; dropCool = rate * 15 }
        if (shoutCool == 0 && loudFrames > rate * 10 && now < 0.3 * recent && now > 0.01 * show) { shoutFrames = (rate * 2.5).toInt(); shoutCool = rate * 12 }

        val applauseTarget = if (applauseFrames > 0) 1.0 else if (introFrames > 0) 0.5 else 0.0
        val roarTarget = if (roarFrames > 0) 1.0 else if (liftFrames > 0) 0.5 else if (introFrames > 0) 0.4 else 0.0
        val singTarget = if (singFrames > 0 && now > 0.15 * show) 1.0 else 0.0
        val shoutTarget = if (shoutFrames > 0) 1.0 else 0.0
        applause += (applauseTarget - applause) * (if (applauseTarget > applause) up else down)
        roar += (roarTarget - roar) * (if (roarTarget > roar) up else down)
        singing += (singTarget - singing) * (if (singTarget > singing) up else down)
        shout += (shoutTarget - shout) * (if (shoutTarget > shout) up else down)
        if (applauseFrames > 0) applauseFrames--
        if (roarFrames > 0) roarFrames--
        if (liftFrames > 0) liftFrames--
        if (singFrames > 0) singFrames--
        if (shoutFrames > 0) shoutFrames--
        if (introFrames > 0) introFrames--
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

    private fun bandPass(at: Int, hz: Double, q: Double) {
        val w = 2 * PI * hz / rate
        val alpha = sin(w) / (2 * q); val a0 = 1 + alpha
        band[at] = alpha / a0; band[at + 1] = 0.0; band[at + 2] = -alpha / a0; band[at + 3] = -2 * cos(w) / a0; band[at + 4] = (1 - alpha) / a0
    }

    private fun lowPass(at: Int, hz: Double) {
        val w = 2 * PI * min(hz, rate * 0.45) / rate
        val alpha = sin(w) / (2 * 0.7071); val c = cos(w); val a0 = 1 + alpha
        band[at] = (1 - c) / 2 / a0; band[at + 1] = (1 - c) / a0; band[at + 2] = (1 - c) / 2 / a0; band[at + 3] = -2 * c / a0; band[at + 4] = (1 - alpha) / a0
    }

    private fun filter(at: Int, st: Int, x: Double): Double {
        val f = band; val s = bandState
        val y = f[at] * x + s[st]
        s[st] = f[at + 1] * x - f[at + 3] * y + s[st + 1]; s[st + 1] = f[at + 2] * x - f[at + 4] * y
        return y
    }

    private fun read(buffer: DoubleArray, mask: Int, write: Int, delay: Double): Double {
        val position = write - delay
        val index = floor(position)
        val frac = position - index
        val i = index.toInt()
        val a = buffer[i and mask]
        return a + (buffer[(i + 1) and mask] - a) * frac
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
        private const val BED = 0.12
        private const val SING = 0.6
        private const val WHISTLE = 0.25
    }
}
