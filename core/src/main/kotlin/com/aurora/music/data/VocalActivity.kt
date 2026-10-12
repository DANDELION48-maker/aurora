package com.aurora.music.data

import com.aurora.music.playback.Fft
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

// per-frame likelihood (0..255) that the lead vocal is singing, and how clearly a new sung note starts there
class VocalActivity(val frameMs: Int, val levels: ByteArray, val separation: Float, val activeShare: Float,
    val onsets: ByteArray = ByteArray(0)) {
    val reliable: Boolean get() = levels.isNotEmpty() && separation >= MIN_SEPARATION && activeShare in .08f..0.95f

    fun at(ms: Long): Float = if (ms < 0) 0f else level((ms / frameMs).toInt())

    fun level(frame: Int): Float = if (frame in levels.indices) (levels[frame].toInt() and 0xFF) / 255f else 0f

    fun onset(frame: Int): Float = if (frame in onsets.indices) (onsets[frame].toInt() and 0xFF) / 255f else 0f

    fun encode(): ByteArray = ByteBuffer.allocate(20 + levels.size + onsets.size).putInt(MAGIC).putShort(VERSION)
        .putShort(frameMs.toShort()).putFloat(separation).putFloat(activeShare).putInt(onsets.size).put(levels).put(onsets).array()

    companion object {
        const val FRAME_MS = 20
        private const val MAGIC = 0x564F4341
        private const val VERSION: Short = 2
        private const val MIN_SEPARATION = 1.6f

        fun decode(bytes: ByteArray): VocalActivity? = runCatching {
            val buffer = ByteBuffer.wrap(bytes)
            if (buffer.int != MAGIC || buffer.short != VERSION) return null
            val frame = buffer.short.toInt()
            val separation = buffer.float
            val share = buffer.float
            val onsets = buffer.int
            val levels = ByteArray(buffer.remaining() - onsets).also(buffer::get)
            VocalActivity(frame, levels, separation, share, ByteArray(onsets).also(buffer::get))
        }.getOrNull()
    }
}

// lead vocals sit dead centre and sustain: keep centred steady energy in the vocal band, drop short hits and wide sounds
class VocalActivityAnalyzer(sampleRate: Int, channels: Int) {
    private val stride = channels.coerceAtLeast(1)
    private val factor = (sampleRate / TARGET_RATE).coerceAtLeast(1)
    private val rate = sampleRate.toFloat() / factor
    // the hop alternates so frames stay on an exact 20ms grid
    private val step = rate.toDouble() * VocalActivity.FRAME_MS / 1000
    private val fft = Fft(N)
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }
    private val taps = lowPass(factor)
    private val histL = FloatArray(taps.size)
    private val histR = FloatArray(taps.size)
    private var histPos = 0
    private var phase = 0
    private val frameL = FloatArray(N)
    private val frameR = FloatArray(N)
    private var filled = 0
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val lo = ceil(LOW_HZ * N / rate).toInt().coerceAtLeast(1)
    private val hi = floor(HIGH_HZ * N / rate).toInt().coerceIn(lo, N / 2 - 1)
    private val bins = hi - lo + 1
    private val history = Array(MEDIAN) { FloatArray(bins) }
    private val totals = FloatArray(MEDIAN)
    private val sorter = FloatArray(MEDIAN)
    private val sustained = Array(LAG + 1) { FloatArray(bins) }
    private val reach = IntArray(bins) { max(2, (lo + it) * 3 / 100) }
    private var frames = 0
    private var count = 0
    private var vocal = FloatArray(4096)
    private var total = FloatArray(4096)
    private var change = FloatArray(4096)

    fun feed(pcm: ShortArray, length: Int) {
        var i = 0
        while (i + stride <= length) {
            val l = pcm[i] / 32768f
            histL[histPos] = l
            histR[histPos] = if (stride > 1) pcm[i + 1] / 32768f else l
            histPos = if (histPos + 1 == taps.size) 0 else histPos + 1
            if (++phase == factor) {
                phase = 0
                push(filter(histL), filter(histR))
            }
            i += stride
        }
    }

    fun finish(): VocalActivity {
        for (c in count until frames) emit(c)
        if (count == 0) return VocalActivity(VocalActivity.FRAME_MS, ByteArray(0), 0f, 0f)
        val v = FloatArray(count) { 10 * log10(vocal[it] + 1e-10f) }
        val t = FloatArray(count) { 10 * log10(total[it] + 1e-10f) }
        val loud = percentile(t, BooleanArray(count) { true }, .95f)
        val audible = BooleanArray(count) { t[it] > loud - 40 }
        val ratio = FloatArray(count) { v[it] - t[it] }
        val zv = robust(v, audible)
        val zr = robust(ratio, audible)
        val raw = FloatArray(count) { if (audible[it]) .5f * zv[it] + .5f * zr[it] else -6f }
        val score = FloatArray(count) { i -> (max(0, i - 1)..min(count - 1, i + 1)).map { raw[it] }.average().toFloat() }
        val (threshold, separation) = split(score, audible)
        val level = FloatArray(count) { if (audible[it]) 1f / (1f + exp(-(score[it] - threshold) * 3f)) else 0f }
        val closed = erode(dilate(level, CLOSE), CLOSE)
        val share = closed.count { it >= .5f }.toFloat() / count
        return VocalActivity(VocalActivity.FRAME_MS, ByteArray(count) { (closed[it] * 255).roundToInt().toByte() }, separation, share,
            onsets(closed))
    }

    // new notes are peaks in the sustained spectrum's rise; songs without a clear split get none
    private fun onsets(voiced: FloatArray): ByteArray {
        val peaks = (0 until count).filter { c ->
            val x = change[c]
            x > 0f && (max(0, c - PEAK) until c).all { change[it] < x } && (c + 1..min(count - 1, c + PEAK)).all { change[it] <= x } &&
                (c..min(count - 1, c + 2)).any { voiced[it] >= .5f }
        }
        if (peaks.size < 10) return ByteArray(0)
        val heights = FloatArray(peaks.size) { ln(change[peaks[it]] + 1e-3f) }
        val (threshold, separation) = split(heights, BooleanArray(peaks.size) { true })
        if (separation < ONSET_SEPARATION) return ByteArray(0)
        val top = heights.filter { it > threshold }.average().toFloat()
        val out = ByteArray(count)
        peaks.forEachIndexed { i, c ->
            val above = (heights[i] - threshold) / (top - threshold).coerceAtLeast(1e-3f)
            if (above > 0) out[c] = ((.5f + .5f * above).coerceAtMost(1f) * 255).roundToInt().toByte()
        }
        return out
    }

    private fun filter(hist: FloatArray): Float {
        if (taps.size == 1) return hist[0]
        var acc = 0f
        var j = histPos
        for (tap in taps) {
            acc += tap * hist[j]
            j = if (j + 1 == hist.size) 0 else j + 1
        }
        return acc
    }

    private fun push(l: Float, r: Float) {
        frameL[filled] = l
        frameR[filled] = r
        if (++filled < N) return
        analyze()
        val hop = ((frames * step).roundToLong() - ((frames - 1) * step).roundToLong()).toInt()
        System.arraycopy(frameL, hop, frameL, 0, N - hop)
        System.arraycopy(frameR, hop, frameR, 0, N - hop)
        filled = N - hop
    }

    // one complex fft carries both channels: left in the real part, right in the imaginary part
    private fun analyze() {
        for (k in 0 until N) {
            re[k] = frameL[k] * window[k]
            im[k] = frameR[k] * window[k]
        }
        fft.transform(re, im, false)
        val slot = history[frames % MEDIAN]
        var all = 0f
        for (k in 1 until N / 2) {
            val zr = re[k]; val zi = im[k]; val ar = re[N - k]; val ai = im[N - k]
            val lr = (zr + ar) / 2; val li = (zi - ai) / 2
            val rr = (zi + ai) / 2; val ri = (ar - zr) / 2
            all += lr * lr + li * li + rr * rr + ri * ri
            if (k < lo || k > hi) continue
            val mr = lr + rr; val mi = li + ri; val sr = lr - rr; val si = li - ri
            val mid = mr * mr + mi * mi
            val centre = ((mid / (mid + sr * sr + si * si + 1e-12f) - .6f) / .4f).coerceIn(0f, 1f)
            slot[k - lo] = sqrt(mid) * .5f * centre * centre
        }
        totals[frames % MEDIAN] = all
        frames++
        if (frames - 1 - HALF >= 0) emit(frames - 1 - HALF)
    }

    // a short median over time keeps sustained notes and drops drum hits
    private fun emit(c: Int) {
        val start = max(0, c - HALF)
        val end = min(frames - 1, c + HALF)
        val n = end - start + 1
        var energy = 0f
        val now = sustained[c % (LAG + 1)]
        for (b in 0 until bins) {
            for (j in 0 until n) {
                val value = history[(start + j) % MEDIAN][b]
                var p = j
                while (p > 0 && sorter[p - 1] > value) { sorter[p] = sorter[p - 1]; p-- }
                sorter[p] = value
            }
            val m = sorter[n / 2]
            energy += m * m
            now[b] = ln(1f + m)
        }
        // rises only, against a frequency-widened earlier frame so vibrato isn't a new note
        var rise = 0f
        if (c >= LAG) {
            val before = sustained[(c - LAG) % (LAG + 1)]
            for (b in 0 until bins) {
                var reference = before[b]
                for (k in max(0, b - reach[b])..min(bins - 1, b + reach[b])) reference = max(reference, before[k])
                if (now[b] > reference) rise += now[b] - reference
            }
        }
        if (count == vocal.size) {
            vocal = vocal.copyOf(count * 2)
            total = total.copyOf(count * 2)
            change = change.copyOf(count * 2)
        }
        vocal[count] = energy
        total[count] = totals[c % MEDIAN]
        change[count] = rise
        count++
    }

    private companion object {
        const val TARGET_RATE = 11025
        const val N = 512
        const val MEDIAN = 9
        const val HALF = MEDIAN / 2
        const val LOW_HZ = 180f
        const val HIGH_HZ = 3800f
        const val CLOSE = 3
        const val LAG = 2
        const val PEAK = 3
        const val ONSET_SEPARATION = 1.2f

        fun lowPass(factor: Int): FloatArray {
            if (factor == 1) return floatArrayOf(1f)
            val size = factor * 8 + 1
            val cutoff = .45 / factor
            val mid = size / 2
            val taps = FloatArray(size) { i ->
                val x = i - mid
                val sinc = if (x == 0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val blackman = .42 - .5 * cos(2 * PI * i / (size - 1)) + .08 * cos(4 * PI * i / (size - 1))
                (sinc * blackman).toFloat()
            }
            val sum = taps.sum()
            return FloatArray(size) { taps[it] / sum }
        }

        fun percentile(x: FloatArray, mask: BooleanArray, q: Float): Float {
            val values = x.filterIndexed { i, _ -> mask[i] }.sorted()
            return if (values.isEmpty()) 0f else values[((values.size - 1) * q).roundToInt()]
        }

        fun robust(x: FloatArray, mask: BooleanArray): FloatArray {
            val median = percentile(x, mask, .5f)
            val spread = (percentile(x, mask, .8f) - percentile(x, mask, .2f)).coerceAtLeast(1f)
            return FloatArray(x.size) { (x[it] - median) / spread }
        }

        // otsu threshold, plus how far apart the two classes sit in units of their spread
        fun split(score: FloatArray, mask: BooleanArray): Pair<Float, Float> {
            val values = score.filterIndexed { i, _ -> mask[i] }.sorted()
            if (values.size < 10) return 0f to 0f
            val prefix = DoubleArray(values.size + 1)
            val squares = DoubleArray(values.size + 1)
            for (i in values.indices) {
                prefix[i + 1] = prefix[i] + values[i]
                squares[i + 1] = squares[i] + values[i].toDouble() * values[i]
            }
            var best = -1.0
            var cut = values.size / 2
            for (i in 1 until values.size) {
                val w0 = i.toDouble() / values.size
                val m0 = prefix[i] / i
                val m1 = (prefix[values.size] - prefix[i]) / (values.size - i)
                val between = w0 * (1 - w0) * (m1 - m0) * (m1 - m0)
                if (between > best) { best = between; cut = i }
            }
            val n0 = cut.toDouble()
            val n1 = (values.size - cut).toDouble()
            val m0 = prefix[cut] / n0
            val m1 = (prefix[values.size] - prefix[cut]) / n1
            val v0 = (squares[cut] / n0 - m0 * m0).coerceAtLeast(0.0)
            val v1 = ((squares[values.size] - squares[cut]) / n1 - m1 * m1).coerceAtLeast(0.0)
            val separation = (m1 - m0) / sqrt((v0 + v1) / 2 + 1e-9)
            return ((values[cut - 1] + values[cut]) / 2) to separation.toFloat()
        }

        fun dilate(x: FloatArray, r: Int) = FloatArray(x.size) { i -> (max(0, i - r)..min(x.size - 1, i + r)).maxOf { x[it] } }
        fun erode(x: FloatArray, r: Int) = FloatArray(x.size) { i -> (max(0, i - r)..min(x.size - 1, i + r)).minOf { x[it] } }
    }
}
