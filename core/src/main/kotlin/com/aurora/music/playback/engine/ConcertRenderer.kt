package com.aurora.music.playback.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan

// renders every path of a ConcertRoom to the two ears: each source is read from its signal's delay line with a per-ear delay
// (spherical-head itd), and head shadow, surfaces and air become two shelves per ear shared by all sources, so dozens of sources
// stay cheap; the direct sum gets pinna and torso cues for its elevation. speakers get plain level panning instead.
// a new room crossfades in over 25 ms so moving around never clicks.
class ConcertRenderer {
    var left = 0.0
        private set
    var right = 0.0
        private set
    // off while a measured room supplies the reflections
    var taps = true
    private var rate = 0
    private var lines = Array(ConcertAcoustics.FEEDS) { DoubleArray(1) }
    private var mask = 0
    private var pos = 0
    private val current = Slots()
    private val next = Slots()
    private var pending: ConcertRoom? = null
    private var fading = false
    private var mix = 0.0
    private var fadeStep = 0.0
    // crossover, stage extraction and ear filters as transposed direct form ii biquads: b0 b1 b2 a1 a2
    private val coef = DoubleArray(5 * COEFS)
    private val state = DoubleArray(2 * STATES)
    private var fastEnv = 0.0; private var slowEnv = 0.0; private var fastUp = 0.0; private var fastDown = 0.0; private var slowK = 0.0
    private val ear = Array(2) { DoubleArray(EAR_LINE) }
    private var earPos = 0

    fun configure(sampleRate: Int) {
        rate = sampleRate
        var size = 1
        while (size < (ConcertAcoustics.MAX_TAP_SEC + 0.004) * rate + 8) size = size shl 1
        lines = Array(ConcertAcoustics.FEEDS) { DoubleArray(size) }
        mask = size - 1
        fadeStep = 1.0 / (FADE_SEC * rate)
        lowPass(C_CROSS, 90.0); lowPass(C_AMP_LP, 4500.0); highPass(C_AMP_HP, 120.0); lowPass(C_BASS, 300.0)
        firstOrderHighPass(C_SHADE, 1500.0); highPass(C_TOP, 7000.0)
        fastUp = 1 - exp(-1 / (0.001 * rate)); fastDown = 1 - exp(-1 / (0.04 * rate)); slowK = 1 - exp(-1 / (0.15 * rate))
        reset()
    }

    fun reset() {
        lines.forEach { it.fill(0.0) }; state.fill(0.0); ear.forEach { it.fill(0.0) }
        pos = 0; earPos = 0; fastEnv = 0.0; slowEnv = 0.0; left = 0.0; right = 0.0
        current.clear(); next.clear(); pending = null; fading = false; mix = 0.0
    }

    fun copyStateFrom(previous: ConcertRenderer) {
        if (previous.rate != rate) return
        for (f in lines.indices) previous.lines[f].copyInto(lines[f])
        previous.state.copyInto(state); for (e in 0..1) previous.ear[e].copyInto(ear[e])
        pos = previous.pos; earPos = previous.earPos; fastEnv = previous.fastEnv; slowEnv = previous.slowEnv
        current.copyFrom(previous.current); next.copyFrom(previous.next)
        pending = previous.pending; fading = previous.fading; mix = previous.mix
    }

    // snap is for a cold start; otherwise the new room fades in once any running fade is done
    fun setRoom(room: ConcertRoom, snap: Boolean) {
        if (snap) { current.load(room, rate); fading = false; pending = null; return }
        if (fading) pending = room else { next.load(room, rate); fading = true; mix = 0.0 }
    }

    fun render(paL: Double, paR: Double, inL: Double, inR: Double) {
        val lowL = biquad(C_CROSS, S_CROSS_L, paL); val lowR = biquad(C_CROSS, S_CROSS_R, paR)
        val mono = (inL + inR) * 0.5
        val level = abs(mono)
        fastEnv += (if (level > fastEnv) fastUp else fastDown) * (level - fastEnv)
        slowEnv += slowK * (level - slowEnv)
        val hit = ((fastEnv / (slowEnv + 1e-9) - 1.3) * 1.5).coerceIn(0.0, 1.0)
        val l = lines
        l[ConcertAcoustics.PA_L][pos] = paL - lowL
        l[ConcertAcoustics.PA_R][pos] = paR - lowR
        l[ConcertAcoustics.SUB][pos] = (lowL + lowR) * 0.5
        l[ConcertAcoustics.DRUMS][pos] = mono * hit
        l[ConcertAcoustics.AMP_L][pos] = biquad(C_AMP_LP, S_AMP_LP_L, biquad(C_AMP_HP, S_AMP_HP_L, inL))
        l[ConcertAcoustics.AMP_R][pos] = biquad(C_AMP_LP, S_AMP_LP_R, biquad(C_AMP_HP, S_AMP_HP_R, inR))
        l[ConcertAcoustics.BASS][pos] = biquad(C_BASS, S_BASS, mono)
        l[ConcertAcoustics.RAW_L][pos] = inL
        l[ConcertAcoustics.RAW_R][pos] = inR

        val acc = sums
        acc.fill(0.0)
        current.accumulate(this, acc, if (fading) 1 - mix else 1.0)
        if (fading) next.accumulate(this, acc, mix)
        pos = (pos + 1) and mask

        // direct: base + head-shadow shelf + air shelf, then pinna and torso; reflections the same without the ear cues
        val directL = acc[0] + biquad(C_SHADE, S_DSHADE_L, acc[1]) + biquad(C_TOP, S_DTOP_L, acc[2])
        val directR = acc[3] + biquad(C_SHADE, S_DSHADE_R, acc[4]) + biquad(C_TOP, S_DTOP_R, acc[5])
        val tapsL = acc[6] + biquad(C_SHADE, S_TSHADE_L, acc[7]) + biquad(C_TOP, S_TTOP_L, acc[8])
        val tapsR = acc[9] + biquad(C_SHADE, S_TSHADE_R, acc[10]) + biquad(C_TOP, S_TTOP_R, acc[11])
        ear[0][earPos] = directL; ear[1][earPos] = directR
        val w = if (fading) mix else 0.0
        val pinna = current.pinna + (next.pinna - current.pinna) * w
        val torso = current.torso + (next.torso - current.torso) * w
        val torsoGain = current.torsoGain + (next.torsoGain - current.torsoGain) * w
        val cue = current.cues + (next.cues - current.cues) * w
        left = shaped(0, directL, pinna, torso, torsoGain, cue) + tapsL
        right = shaped(1, directR, pinna, torso, torsoGain, cue) + tapsR
        earPos = (earPos + 1) and (EAR_LINE - 1)

        if (fading) {
            mix += fadeStep
            if (mix >= 1.0) {
                current.copyFrom(next); fading = false; mix = 0.0
                pending?.let { pending = null; next.load(it, rate); fading = true }
            }
        }
    }

    private val sums = DoubleArray(12)

    private fun shaped(e: Int, x: Double, pinna: Double, torso: Double, torsoGain: Double, cue: Double): Double {
        if (cue <= 0.0) return x
        val notch = (x + PINNA * read(ear[e], EAR_LINE - 1, earPos, pinna)) / (1 + PINNA)
        val shaped = notch + torsoGain * read(ear[e], EAR_LINE - 1, earPos, torso)
        return x + (shaped - x) * cue
    }

    internal fun line(feed: Int) = lines[feed]
    internal fun tap(buffer: DoubleArray, delay: Double) = read(buffer, mask, pos, delay)

    private fun read(buffer: DoubleArray, m: Int, write: Int, delay: Double): Double {
        val position = write - delay
        val index = floor(position)
        val frac = position - index
        val i = index.toInt()
        val a = buffer[i and m]
        return a + (buffer[(i + 1) and m] - a) * frac
    }

    private fun biquad(c: Int, s: Int, x: Double): Double {
        val k = coef; val st = state
        val y = k[c] * x + st[s]
        st[s] = k[c + 1] * x - k[c + 3] * y + st[s + 1]
        st[s + 1] = k[c + 2] * x - k[c + 4] * y
        return y
    }

    private fun lowPass(c: Int, hz: Double) {
        val w = 2 * PI * min(hz, rate * 0.45) / rate
        val alpha = sin(w) / (2 * 0.7071); val cw = cos(w); val a0 = 1 + alpha
        coef[c] = (1 - cw) / 2 / a0; coef[c + 1] = (1 - cw) / a0; coef[c + 2] = (1 - cw) / 2 / a0
        coef[c + 3] = -2 * cw / a0; coef[c + 4] = (1 - alpha) / a0
    }

    // a first-order corner keeps x + (a - 1) * hp(x) a smooth shelf
    private fun firstOrderHighPass(c: Int, hz: Double) {
        val k = tan(PI * min(hz, rate * 0.45) / rate)
        coef[c] = 1 / (1 + k); coef[c + 1] = -1 / (1 + k); coef[c + 2] = 0.0; coef[c + 3] = (k - 1) / (k + 1); coef[c + 4] = 0.0
    }

    private fun highPass(c: Int, hz: Double) {
        val w = 2 * PI * min(hz, rate * 0.45) / rate
        val alpha = sin(w) / (2 * 0.7071); val cw = cos(w); val a0 = 1 + alpha
        coef[c] = (1 + cw) / 2 / a0; coef[c + 1] = -(1 + cw) / a0; coef[c + 2] = (1 + cw) / 2 / a0
        coef[c + 3] = -2 * cw / a0; coef[c + 4] = (1 - alpha) / a0
    }

    // per-source parameters for both ears, worked out once per room
    private class Slots {
        val feed = IntArray(MAX); val delayL = DoubleArray(MAX); val delayR = DoubleArray(MAX)
        val gainL = DoubleArray(MAX); val gainR = DoubleArray(MAX); val shadeL = DoubleArray(MAX); val shadeR = DoubleArray(MAX)
        val topL = DoubleArray(MAX); val topR = DoubleArray(MAX)
        var direct = 0; var count = 0
        var pinna = 1.0; var torso = 1.0; var torsoGain = 0.0; var cues = 0.0

        fun clear() { count = 0; direct = 0; cues = 0.0 }

        fun copyFrom(o: Slots) {
            o.feed.copyInto(feed); o.delayL.copyInto(delayL); o.delayR.copyInto(delayR); o.gainL.copyInto(gainL); o.gainR.copyInto(gainR)
            o.shadeL.copyInto(shadeL); o.shadeR.copyInto(shadeR); o.topL.copyInto(topL); o.topR.copyInto(topR)
            direct = o.direct; count = o.count; pinna = o.pinna; torso = o.torso; torsoGain = o.torsoGain; cues = o.cues
        }

        fun load(room: ConcertRoom, rate: Int) {
            count = 0
            for (p in room.paths) put(p, room.binaural, rate)
            direct = count
            for (p in room.taps) put(p, room.binaural, rate)
            val up = room.pinnaElevation
            pinna = (0.035 + 0.06 * (1 - (up + PI / 2) / PI)) * 1e-3 * rate
            torso = (0.6 + 0.6 * max(0.0, sin(up))) * 1e-3 * rate
            torsoGain = 0.15 + 0.15 * max(0.0, sin(up))
            cues = if (room.binaural) 1.0 else 0.0
        }

        private fun put(p: ConcertPath, binaural: Boolean, rate: Int) {
            if (count >= MAX || p.gain < 1e-6) return
            val i = count++
            feed[i] = p.feed
            val side = sin(p.azimuth) * cos(p.elevation)
            val base = p.delaySec * rate
            if (binaural) {
                val lateral = asin(side.coerceIn(-1.0, 1.0))
                val itd = HEAD / SPEED * (lateral + sin(lateral)) * rate
                delayL[i] = base + EAR_BASE * rate + 0.5 * itd
                delayR[i] = base + EAR_BASE * rate - 0.5 * itd
                val forward = cos(p.azimuth) * cos(p.elevation)
                val behind = min(0.0, forward)
                val mid = p.mid * (1 + 0.2 * behind)
                val top = p.top * (1 + 0.5 * behind)
                val g = p.gain * DIFFUSE
                val gl = g * (1 - 0.1 * max(0.0, side)); val gr = g * (1 - 0.1 * max(0.0, -side))
                val ml = mid * shadow(-side); val mr = mid * shadow(side)
                gainL[i] = gl; gainR[i] = gr
                shadeL[i] = gl * (ml - 1); shadeR[i] = gr * (mr - 1)
                topL[i] = gl * ml * (top - 1); topR[i] = gr * mr * (top - 1)
            } else {
                val pan = sign(side) * abs(side).pow(0.6) * 0.9
                val gl = p.gain * cos((pan + 1) * PI / 4); val gr = p.gain * sin((pan + 1) * PI / 4)
                delayL[i] = base; delayR[i] = base
                gainL[i] = gl; gainR[i] = gr
                shadeL[i] = gl * (p.mid - 1); shadeR[i] = gr * (p.mid - 1)
                topL[i] = gl * p.mid * (p.top - 1); topR[i] = gr * p.mid * (p.top - 1)
            }
        }

        fun accumulate(r: ConcertRenderer, acc: DoubleArray, weight: Double) {
            for (i in 0 until count) {
                if (i >= direct && !r.taps) break
                val buffer = r.line(feed[i])
                val xl = r.tap(buffer, delayL[i]) * weight
                val xr = if (delayR[i] == delayL[i]) xl else r.tap(buffer, delayR[i]) * weight
                val o = if (i < direct) 0 else 6
                acc[o] += gainL[i] * xl; acc[o + 1] += shadeL[i] * xl; acc[o + 2] += topL[i] * xl
                acc[o + 3] += gainR[i] * xr; acc[o + 4] += shadeR[i] * xr; acc[o + 5] += topR[i] * xr
            }
        }

        // brown and duda's spherical-head shelf, levelled so a source straight ahead stays flat: up to +4 dB facing it, -17 dB in its shadow
        private fun shadow(towardEar: Double): Double {
            val theta = Math.toDegrees(acos(towardEar.coerceIn(-1.0, 1.0)))
            return min(1.6, (1.05 + 0.95 * cos(Math.toRadians(theta / 150 * 180))) / FRONTAL)
        }
    }

    companion object {
        private const val MAX = 40
        private const val FADE_SEC = 0.025
        private const val HEAD = 0.0875
        private const val SPEED = 343.0
        private const val EAR_BASE = 0.0004
        private const val PINNA = 0.35
        private val FRONTAL = 1.05 + 0.95 * cos(Math.toRadians(90.0 / 150 * 180))
        private const val EAR_LINE = 512
        // a source heard by two ears carries as much power as one panned between two speakers
        private val DIFFUSE = 1 / kotlin.math.sqrt(2.0)
        private const val C_CROSS = 0; private const val C_AMP_LP = 5; private const val C_AMP_HP = 10; private const val C_BASS = 15
        private const val C_SHADE = 20; private const val C_TOP = 25; private const val COEFS = 6
        private const val S_CROSS_L = 0; private const val S_CROSS_R = 2; private const val S_AMP_LP_L = 4; private const val S_AMP_LP_R = 6
        private const val S_AMP_HP_L = 8; private const val S_AMP_HP_R = 10; private const val S_BASS = 12
        private const val S_DSHADE_L = 14; private const val S_DSHADE_R = 16; private const val S_DTOP_L = 18; private const val S_DTOP_R = 20
        private const val S_TSHADE_L = 22; private const val S_TSHADE_R = 24; private const val S_TTOP_L = 26; private const val S_TTOP_R = 28
        private const val STATES = 15
    }
}
