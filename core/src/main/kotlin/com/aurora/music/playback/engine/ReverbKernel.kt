package com.aurora.music.playback.engine

import com.aurora.music.data.ReverbPrefs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// stereo diffuser into an 8-line householder fdn with frequency-dependent decay, modulated lines and early reflections;
// the processor's own instance can also render concert mode (its delay lines are only allocated when asked for)
class ReverbKernel(concertCapable: Boolean = false) {
    @Volatile var settings: ReverbPrefs = ReverbPrefs()
    // set off the audio thread when concert mode uses a measured room instead of the modelled one
    @Volatile var measured: MeasuredRoom? = null
    val active: Boolean get() = rate > 0 && !idle
    val tailFrames: Int get() = if (active) tailLimit else 0

    private var rate = 0
    @Volatile private var idle = true
    private var applied: ReverbPrefs? = null

    private var pre = Array(2) { DoubleArray(1) }
    private var preMask = 0
    private var prePos = 0
    private val diffusion = Array(STEPS * LINES) { DoubleArray(1) }
    private var diffusionMask = 0
    private var diffusionPos = 0
    private val lines = Array(LINES) { DoubleArray(1) }
    private var lineMask = 0
    private var linePos = 0

    private val channels = DoubleArray(LINES)
    private val absorbed = DoubleArray(LINES)
    private val b0 = DoubleArray(LINES)
    private val a1 = DoubleArray(LINES)
    private val oscCos = DoubleArray(LINES)
    private val oscSin = DoubleArray(LINES)
    private val stepCos = DoubleArray(LINES)
    private val stepSin = DoubleArray(LINES)
    private val filter = DoubleArray(35)
    private val filterState = DoubleArray(28)
    private val gain = DoubleArray(GAINS)
    private val gainTarget = DoubleArray(GAINS)
    private val renderer = if (concertCapable) ConcertRenderer() else null
    private val bandNow = DoubleArray(3)
    private val bandTarget = DoubleArray(3)
    private val loopMid = DoubleArray(LINES)
    private val loopLow = DoubleArray(LINES)
    private val loopHigh = DoubleArray(LINES)
    private val lowState = DoubleArray(LINES)
    private val highState = DoubleArray(LINES)
    private var lowK = 0.0; private var highK = 0.0; private var sideK = 0.0
    private var sideLow = 0.0
    private var binaural = false
    private var inUse: MeasuredRoom? = null
    private var measuredGain = 0.0
    private var directGain = 0.0
    private val roomIn = DoubleArray(MeasuredRoom.BLOCK * 2)
    private val roomOut = DoubleArray(MeasuredRoom.BLOCK * 2)
    private var roomPos = 0
    private val squeeze = LiveCompressor()
    private var duck = 0.0
    private val pa = PaDrive()
    private val crowd = CrowdNoise()
    private var concert = false
    private var fade = 0.0; private var fadeTarget = 0.0

    private var decaySec = 2.4
    private var hfRatio = 0.6
    private var decayNow = 2.4
    private var hfNow = 0.6
    private var depth = 0.0
    private var wetNorm = 0.0
    private var tailLimit = 0
    private var silentFrames = 0
    private var smoothing = 0.0
    private var scale = 1.0; private var scaleTarget = 1.0
    private var predelay = 0.0; private var predelayTarget = 0.0
    private var send = 0.0; private var sendTarget = 0.0
    private var dry = 1.0; private var dryTarget = 1.0
    private var wet = 0.0; private var wetTarget = 0.0
    private var early = 0.0; private var earlyTarget = 0.0
    private var width = 1.0; private var widthTarget = 1.0

    fun configure(sampleRate: Int) {
        if (sampleRate != rate) {
            rate = sampleRate
            pre = Array(2) { DoubleArray(pow2((MAX_PREDELAY_SEC + ER_LEFT.last().coerceAtLeast(ER_RIGHT.last()) * MAX_SCALE) * rate + 8)) }
            preMask = pre[0].size - 1
            val diffusionSize = pow2(DIFFUSION_SEC * STEP_RANGE.max() * MAX_SCALE * rate + 8)
            for (i in diffusion.indices) diffusion[i] = DoubleArray(diffusionSize)
            diffusionMask = diffusionSize - 1
            val lineSize = pow2((LINE_SEC.max() * MAX_SCALE + MAX_DEPTH_SEC) * rate + 8)
            for (i in lines.indices) lines[i] = DoubleArray(lineSize)
            lineMask = lineSize - 1
            smoothing = 1.0 - kotlin.math.exp(-1.0 / (0.03 * rate))
            for (i in 0 until LINES) {
                stepCos[i] = cos(2 * PI * MOD_HZ[i] / rate); stepSin[i] = sin(2 * PI * MOD_HZ[i] / rate)
            }
            biquad(10, BOOM_HZ, highPass = false)
            biquad(EQ_FILTER, 90.0, highPass = false)
            bandPass(EQ_FILTER + 5, 350.0)
            bandPass(EQ_FILTER + 10, 3000.0)
            biquad(EQ_FILTER + 15, min(10_000.0, rate * 0.4), highPass = true)
            pa.configure(rate); crowd.configure(rate); renderer?.configure(rate)
            lowK = 1 - exp(-2 * PI * 300.0 / rate); highK = 1 - exp(-2 * PI * 3000.0 / rate); sideK = 1 - exp(-2 * PI * 250.0 / rate)
        }
        reset()
    }

    fun reset() {
        pre.forEach { it.fill(0.0) }; diffusion.forEach { it.fill(0.0) }; lines.forEach { it.fill(0.0) }
        prePos = 0; diffusionPos = 0; linePos = 0
        absorbed.fill(0.0); filterState.fill(0.0)
        for (i in 0 until LINES) { oscCos[i] = cos(i * 0.77); oscSin[i] = sin(i * 0.77) }
        send = 0.0; dry = 1.0; silentFrames = 0
        gain.fill(0.0); fade = 0.0; lowState.fill(0.0); highState.fill(0.0); sideLow = 0.0
        squeeze.reset(); duck = 0.0; pa.reset(); crowd.reset(); renderer?.reset()
        inUse?.convolver?.reset(); roomPos = 0; roomOut.fill(0.0); renderer?.taps = inUse == null
        applied = null; idle = true
    }

    fun copyStateFrom(previous: ReverbKernel) {
        if (previous.rate != rate || previous.idle) return
        for (i in pre.indices) previous.pre[i].copyInto(pre[i])
        for (i in diffusion.indices) previous.diffusion[i].copyInto(diffusion[i])
        for (i in lines.indices) previous.lines[i].copyInto(lines[i])
        prePos = previous.prePos; diffusionPos = previous.diffusionPos; linePos = previous.linePos
        previous.absorbed.copyInto(absorbed); previous.filterState.copyInto(filterState)
        previous.oscCos.copyInto(oscCos); previous.oscSin.copyInto(oscSin)
        send = previous.send; dry = previous.dry; wet = previous.wet; early = previous.early; width = previous.width
        scale = previous.scale; predelay = previous.predelay; silentFrames = previous.silentFrames; idle = false
        previous.gain.copyInto(gain); fade = previous.fade; previous.bandNow.copyInto(bandNow)
        previous.lowState.copyInto(lowState); previous.highState.copyInto(highState); sideLow = previous.sideLow
        decayNow = previous.decayNow; hfNow = previous.hfNow
        squeeze.copyStateFrom(previous.squeeze); duck = previous.duck; pa.copyStateFrom(previous.pa); crowd.copyStateFrom(previous.crowd)
        previous.renderer?.let { renderer?.copyStateFrom(it) }
    }

    fun process(block: AudioBlock) {
        if (rate == 0) return
        val current = settings
        if (idle && !current.enabled) return
        if (current !== applied) apply(current, idle)
        if (current.enabled) silentFrames = 0
        idle = false
        val frames = block.frameCount
        if (frames == 0) return
        val glide = min(1.0, frames / (GLIDE_SEC * rate))
        val scaleStep = (scaleTarget - scale) * glide / frames
        val predelayStep = (predelayTarget - predelay) * glide / frames
        val live = concert || fade > FADE_FLOOR
        val bands = concert
        if (!live) { fade = 0.0; decayNow = decaySec; hfNow = hfRatio }
        else {
            decayNow += (decaySec - decayNow) * glide; hfNow += (hfRatio - hfNow) * glide
            for (j in 0..2) bandNow[j] += (bandTarget[j] - bandNow[j]) * glide
        }
        updateAbsorption(scale + scaleStep * frames * 0.5, bands)
        val measuredNow = if (bands) measured else null
        if (measuredNow !== inUse) {
            inUse = measuredNow; roomPos = 0; roomOut.fill(0.0); measuredNow?.convolver?.reset(); renderer?.taps = measuredNow == null
        }
        measuredGain = directGain / (measuredNow?.peak ?: 1.0)
        val samples = block.samples
        val k = smoothing
        val r = rate.toDouble()
        val f = filter
        val fs = filterState
        for (frame in 0 until frames) {
            send += (sendTarget - send) * k; dry += (dryTarget - dry) * k; wet += (wetTarget - wet) * k
            early += (earlyTarget - early) * k; width += (widthTarget - width) * k
            scale += scaleStep; predelay += predelayStep
            val inL = samples[frame * 2]; val inR = samples[frame * 2 + 1]
            var feedL = inL; var feedR = inR
            var paL = inL; var paR = inR
            var crowdL = 0.0; var crowdR = 0.0
            if (live) {
                // desk compressor, then the pushed pa; the crowd is in the room but not in the pa
                duck += ((if (concert) squeeze.next(inL, inR) else 0.0) - duck) * k
                val drive = exp(duck * DB_TO_LN)
                pa.process(inL * drive, inR * drive, gain[DRIVE], squeeze.level())
                paL = pa.left; paR = pa.right
                if (gain[CROWD] > 1e-5) { crowd.next(inL, inR, gain[CROWD]); crowdL = crowd.left; crowdR = crowd.right }
                feedL = paL + crowdL; feedR = paR + crowdR
            }

            var l = feedL * send
            var y = f[0] * l + fs[0]; fs[0] = f[1] * l - f[3] * y + fs[1]; fs[1] = f[2] * l - f[4] * y; l = y
            y = f[5] * l + fs[2]; fs[2] = f[6] * l - f[8] * y + fs[3]; fs[3] = f[7] * l - f[9] * y; l = y
            var rr = feedR * send
            y = f[0] * rr + fs[4]; fs[4] = f[1] * rr - f[3] * y + fs[5]; fs[5] = f[2] * rr - f[4] * y; rr = y
            y = f[5] * rr + fs[6]; fs[6] = f[6] * rr - f[8] * y + fs[7]; fs[7] = f[7] * rr - f[9] * y; rr = y
            pre[0][prePos] = l; pre[1][prePos] = rr

            var erL = 0.0; var erR = 0.0
            val erScale = scale * r
            if (!live || early > EARLY_FLOOR) for (tap in 0 until ER_TAPS) {
                erL += ER_GAIN[tap] * linear(pre[tap and 1], preMask, prePos, predelay + ER_LEFT[tap] * erScale)
                erR += ER_GAIN[tap] * linear(pre[(tap + 1) and 1], preMask, prePos, predelay + ER_RIGHT[tap] * erScale)
            }
            if (live) {
                for (g in 0 until GAINS) gain[g] += (gainTarget[g] - gain[g]) * k
                fade += (fadeTarget - fade) * k
            }
            val pl = linear(pre[0], preMask, prePos, predelay)
            val pr = linear(pre[1], preMask, prePos, predelay)
            prePos = (prePos + 1) and preMask

            for (c in 0 until LINES) channels[c] = if (c and 1 == 0) pl else pr
            for (step in 0 until STEPS) {
                for (c in 0 until LINES) {
                    val index = step * LINES + c
                    val buffer = diffusion[index]
                    buffer[diffusionPos] = channels[c]
                    channels[c] = FLIP[index] * linear(buffer, diffusionMask, diffusionPos, max(1.0, DIFFUSION_TIMES[index] * scale * r))
                }
                hadamard(channels)
            }
            diffusionPos = (diffusionPos + 1) and diffusionMask

            var outL = 0.0; var outR = 0.0; var sum = 0.0
            for (i in 0 until LINES) {
                val c = oscCos[i] * stepCos[i] - oscSin[i] * stepSin[i]
                oscSin[i] = oscSin[i] * stepCos[i] + oscCos[i] * stepSin[i]; oscCos[i] = c
                val delayed = hermite(lines[i], lineMask, linePos, LINE_SEC[i] * scale * r + depth * oscSin[i])
                val z = if (!bands) b0[i] * delayed + a1[i] * absorbed[i] else {
                    // three-band loss per line so lows, mids and highs each die at their own rt60
                    lowState[i] += lowK * (delayed - lowState[i]); highState[i] += highK * (delayed - highState[i])
                    loopMid[i] * (delayed + loopLow[i] * lowState[i] + loopHigh[i] * (delayed - highState[i]))
                }
                absorbed[i] = z; sum += z
                outL += OUT_LEFT[i] * z; outR += OUT_RIGHT[i] * z
            }
            sum *= 2.0 / LINES
            for (i in 0 until LINES) lines[i][linePos] = absorbed[i] - sum + channels[i]
            linePos = (linePos + 1) and lineMask
            if (linePos and 4095 == 0) for (i in 0 until LINES) {
                val norm = (3.0 - oscCos[i] * oscCos[i] - oscSin[i] * oscSin[i]) * 0.5
                oscCos[i] *= norm; oscSin[i] *= norm
            }

            val wl = outL * wetNorm + erL * early
            val wr = outR * wetNorm + erR * early
            val mid = (wl + wr) * 0.5; val side = (wl - wr) * 0.5 * width
            if (!live) {
                samples[frame * 2] = inL * dry + (mid + side) * wet
                samples[frame * 2 + 1] = inR * dry + (mid - side) * wet
            } else {
                // concert: every source and reflection placed around your head, a boomy tail, crossfaded against the studio mix
                val tm = (outL + outR) * 0.5 * wetNorm; var ts = (outL - outR) * 0.5 * wetNorm * width
                // at the ears a diffuse field is near mono below a few hundred hertz
                if (binaural) { sideLow += sideK * (ts - sideLow); ts -= sideLow }
                var tl = tm + ts; var tr = tm - ts
                y = f[10] * tl + fs[8]; fs[8] = f[11] * tl - f[13] * y + fs[9]; fs[9] = f[12] * tl - f[14] * y; tl += y * gain[BOOM]
                y = f[10] * tr + fs[10]; fs[10] = f[11] * tr - f[13] * y + fs[11]; fs[11] = f[12] * tr - f[14] * y; tr += y * gain[BOOM]
                val venue = renderer!!
                venue.render(paL * send, paR * send, inL * send, inR * send)
                var roomL: Double; var roomR: Double
                val measuredRoom = inUse
                if (measuredRoom == null) { roomL = venue.left + tl * gain[LATE]; roomR = venue.right + tr * gain[LATE] }
                else {
                    // the measured response runs in fixed blocks, so its output never depends on how the host splits audio
                    roomL = venue.left + roomOut[roomPos * 2] * measuredGain; roomR = venue.right + roomOut[roomPos * 2 + 1] * measuredGain
                    val mono = (feedL + feedR) * 0.5 * send
                    roomIn[roomPos * 2] = mono; roomIn[roomPos * 2 + 1] = mono
                    if (++roomPos == MeasuredRoom.BLOCK) {
                        measuredRoom.convolver.queueInput(roomIn, 0, MeasuredRoom.BLOCK)
                        measuredRoom.convolver.readOutput(roomOut, 0, MeasuredRoom.BLOCK)
                        roomPos = 0
                    }
                }
                roomL += crowdL * send; roomR += crowdR * send
                // the band's rig: parallel fixed bands with smoothed gains, so switching genre never jumps a coefficient
                var venueOutL = roomL; var venueOutR = roomR
                for (b in 0 until EQ_BANDS) {
                    val c = EQ_FILTER + b * 5; val st = EQ_STATE + b * 4; val g = gain[EQ + b]
                    y = f[c] * roomL + fs[st]; fs[st] = f[c + 1] * roomL - f[c + 3] * y + fs[st + 1]; fs[st + 1] = f[c + 2] * roomL - f[c + 4] * y; venueOutL += y * g
                    y = f[c] * roomR + fs[st + 2]; fs[st + 2] = f[c + 1] * roomR - f[c + 3] * y + fs[st + 3]; fs[st + 3] = f[c + 2] * roomR - f[c + 4] * y; venueOutR += y * g
                }
                val studioL = mid + side; val studioR = mid - side
                samples[frame * 2] = soften(inL * dry + (studioL + (venueOutL - studioL) * fade) * wet)
                samples[frame * 2 + 1] = soften(inR * dry + (studioR + (venueOutR - studioR) * fade) * wet)
            }
        }
        if (!current.enabled) {
            silentFrames += frames
            if (silentFrames > tailLimit) reset()
        }
    }

    private fun apply(raw: ReverbPrefs, snap: Boolean) {
        val p = raw.clamped()
        val venue = p.concertPrefs.takeIf { it.enabled && renderer != null }
        val room = venue?.let { ConcertAcoustics.room(it) }
        val s = room?.late ?: p
        scaleTarget = 0.3 + 1.2 * s.size
        predelayTarget = s.predelayMs / 1000.0 * rate
        decaySec = s.decaySec.toDouble()
        hfRatio = 1.0 - 0.85 * s.damping
        depth = s.modulation * MAX_DEPTH_SEC * rate
        sendTarget = if (p.enabled) 1.0 else 0.0
        dryTarget = if (!p.enabled) 1.0 else if (venue != null) 1.0 - venue.amount else cos(p.mix * PI / 2)
        wetTarget = venue?.amount?.toDouble() ?: sin(p.mix * PI / 2)
        earlyTarget = s.early * ER_NORM
        widthTarget = s.width.toDouble()
        val longest = room?.decayBands?.max() ?: decaySec
        tailLimit = ((longest * 1.25 + LINE_SEC.max() * MAX_SCALE + MAX_PREDELAY_SEC) * rate).toInt()
        biquad(0, s.lowCutHz.toDouble(), highPass = true)
        biquad(5, min(s.highCutHz.toDouble(), rate * 0.45), highPass = false)
        concert = room != null
        fadeTarget = if (concert) 1.0 else 0.0
        if (room != null) {
            renderer!!.setRoom(room, snap)
            binaural = room.binaural
            directGain = room.directGain
            val b = room.decayBands
            bandTarget[0] = (b[0] + b[1]) / 2; bandTarget[1] = (b[2] + b[3] + b[4]) / 3; bandTarget[2] = (b[5] + b[6]) / 2
            // the tail is normalised for white noise, music-like spectra ring about 4.5 dB hotter
            gainTarget[LATE] = room.lateGain * LATE_TRIM
            gainTarget[BOOM] = 10.0.pow(room.boomDb / 20) - 1
            for (b in 0 until EQ_BANDS) gainTarget[EQ + b] = 10.0.pow(room.eqDb[b] / 20) - 1
            squeeze.configure(rate, room.compRatio, room.compHeadroomDb, room.compReleaseSec, room.compMakeupDb)
            gainTarget[DRIVE] = room.drive; gainTarget[CROWD] = room.crowd
            crowd.personality(room.rig)
        } else {
            gainTarget[DRIVE] = 0.0; gainTarget[CROWD] = 0.0
        }
        if (snap) {
            scale = scaleTarget; predelay = predelayTarget; wet = wetTarget; early = earlyTarget; width = widthTarget
            fade = fadeTarget; gainTarget.copyInto(gain); bandTarget.copyInto(bandNow); decayNow = decaySec; hfNow = hfRatio
        }
        applied = raw
    }


    private fun updateAbsorption(scaleNow: Double, bands: Boolean) {
        var loop = 0.0
        if (bands) {
            for (i in 0 until LINES) {
                val length = LINE_SEC[i] * scaleNow
                val mid = 10.0.pow(-3.0 * length / bandNow[1])
                loopMid[i] = mid
                loopLow[i] = 10.0.pow(-3.0 * length / bandNow[0]) / mid - 1
                loopHigh[i] = 10.0.pow(-3.0 * length / bandNow[2]) / mid - 1
                loop += mid * mid
            }
            wetNorm = OUTPUT_GAIN * sqrt(1.0 - loop / LINES)
            return
        }
        for (i in 0 until LINES) {
            val length = LINE_SEC[i] * scaleNow
            val g0 = 10.0.pow(-3.0 * length / decayNow)
            val gHigh = 10.0.pow(-3.0 * length / (decayNow * hfNow))
            val a = (g0 - gHigh) / (g0 + gHigh)
            a1[i] = a; b0[i] = g0 * (1 - a)
            loop += g0 * g0
        }
        wetNorm = OUTPUT_GAIN * sqrt(1.0 - loop / LINES)
    }

    private fun biquad(at: Int, hz: Double, highPass: Boolean) {
        val w = 2 * PI * hz / rate
        val alpha = sin(w) / (2 * 0.7071)
        val c = cos(w)
        val a0 = 1 + alpha
        val edge = if (highPass) (1 + c) / 2 else (1 - c) / 2
        filter[at] = edge / a0
        filter[at + 1] = (if (highPass) -(1 + c) else 1 - c) / a0
        filter[at + 2] = edge / a0
        filter[at + 3] = -2 * c / a0
        filter[at + 4] = (1 - alpha) / a0
    }

    // concert output only: a soft knee above -0.9 dBFS so boosted rigs never clip the sink
    private fun soften(x: Double): Double {
        val a = kotlin.math.abs(x)
        return if (a <= KNEE) x else kotlin.math.sign(x) * (KNEE + (1 - KNEE) * kotlin.math.tanh((a - KNEE) / (1 - KNEE)))
    }

    private fun bandPass(at: Int, hz: Double) {
        val w = 2 * PI * hz / rate
        val alpha = sin(w) / (2 * 0.9)
        val a0 = 1 + alpha
        filter[at] = alpha / a0; filter[at + 1] = 0.0; filter[at + 2] = -alpha / a0
        filter[at + 3] = -2 * cos(w) / a0; filter[at + 4] = (1 - alpha) / a0
    }

    private fun linear(buffer: DoubleArray, mask: Int, write: Int, delay: Double): Double {
        val position = write - delay
        val index = floor(position)
        val frac = position - index
        val i = index.toInt()
        val a = buffer[i and mask]
        return a + (buffer[(i + 1) and mask] - a) * frac
    }

    private fun hermite(buffer: DoubleArray, mask: Int, write: Int, delay: Double): Double {
        val position = write - delay
        val index = floor(position)
        val t = position - index
        val i = index.toInt()
        val y0 = buffer[(i - 1) and mask]; val y1 = buffer[i and mask]
        val y2 = buffer[(i + 1) and mask]; val y3 = buffer[(i + 2) and mask]
        val c1 = 0.5 * (y2 - y0)
        val c2 = y0 - 2.5 * y1 + 2.0 * y2 - 0.5 * y3
        val c3 = 0.5 * (y3 - y0) + 1.5 * (y1 - y2)
        return ((c3 * t + c2) * t + c1) * t + y1
    }

    private fun hadamard(x: DoubleArray) {
        var h = 1
        while (h < LINES) {
            var i = 0
            while (i < LINES) {
                for (j in i until i + h) { val a = x[j]; val b = x[j + h]; x[j] = a + b; x[j + h] = a - b }
                i += h * 2
            }
            h *= 2
        }
        for (i in 0 until LINES) x[i] *= HADAMARD_NORM
    }

    companion object {
        fun storageSamples(rate: Int): Long =
            2L * pow2((MAX_PREDELAY_SEC + ER_RIGHT.last() * MAX_SCALE) * rate + 8) +
                STEPS * LINES * pow2(DIFFUSION_SEC * STEP_RANGE.max() * MAX_SCALE * rate + 8).toLong() +
                LINES * pow2((LINE_SEC.max() * MAX_SCALE + MAX_DEPTH_SEC) * rate + 8).toLong()
        private fun pow2(frames: Double): Int {
            var size = 1
            while (size < frames) size = size shl 1
            return size
        }
        private const val LINES = 8
        private const val STEPS = 4
        private const val ER_TAPS = 8
        private const val MAX_SCALE = 1.5
        private const val MAX_PREDELAY_SEC = 0.2
        private const val MAX_DEPTH_SEC = 0.0008
        private const val DIFFUSION_SEC = 0.045
        private const val GLIDE_SEC = 0.08
        private const val OUTPUT_GAIN = 2.1
        private const val ER_NORM = 0.55
        private const val GAINS = 8
        private const val LATE = 0
        private const val BOOM = 1
        private const val EQ = 2
        private const val EQ_BANDS = 4
        private const val EQ_FILTER = 15
        private const val EQ_STATE = 12
        private const val DRIVE = 6
        private const val CROWD = 7
        private const val KNEE = 0.9
        private val DB_TO_LN = kotlin.math.ln(10.0) / 20
        private const val LATE_TRIM = 0.6
        private const val BOOM_HZ = 100.0
        private const val FADE_FLOOR = 1e-7
        private const val EARLY_FLOOR = 1e-9
        private val HADAMARD_NORM = 1.0 / sqrt(LINES.toDouble())
        private val LINE_SEC = doubleArrayOf(0.0371, 0.0419, 0.0473, 0.0533, 0.0593, 0.0661, 0.0739, 0.0827)
        private val MOD_HZ = doubleArrayOf(0.31, 0.37, 0.43, 0.53, 0.61, 0.71, 0.83, 0.97)
        private val OUT_LEFT = doubleArrayOf(1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 1.0, -1.0).map { it * HADAMARD_NORM }.toDoubleArray()
        private val OUT_RIGHT = doubleArrayOf(1.0, 1.0, -1.0, -1.0, 1.0, 1.0, -1.0, -1.0).map { it * HADAMARD_NORM }.toDoubleArray()
        private val STEP_RANGE = doubleArrayOf(0.1, 0.17, 0.28, 0.45)
        private val ER_LEFT = doubleArrayOf(0.0047, 0.0091, 0.0139, 0.0197, 0.0251, 0.0313, 0.0389, 0.0461)
        private val ER_RIGHT = doubleArrayOf(0.0053, 0.0101, 0.0127, 0.0211, 0.0239, 0.0331, 0.0373, 0.0479)
        private val ER_GAIN = DoubleArray(ER_TAPS) { k -> 0.85.pow(k) * if (k in setOf(1, 4, 6)) -1.0 else 1.0 }
        // fixed seed so every device builds the same room
        private val DIFFUSION_TIMES: DoubleArray
        private val FLIP: DoubleArray
        init {
            var seed = 0x2545F491L
            fun next(): Double { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 11) and 0xFFFFF).toDouble() / 0x100000 }
            DIFFUSION_TIMES = DoubleArray(STEPS * LINES) { index ->
                DIFFUSION_SEC * STEP_RANGE[index / LINES] * ((index % LINES) + 0.15 + 0.7 * next()) / LINES
            }
            FLIP = DoubleArray(STEPS * LINES) { if (next() < 0.5) -1.0 else 1.0 }
        }
    }
}
