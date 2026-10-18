package com.aurora.music.data

import com.aurora.music.desktop.audio.decode.FfmpegDecoder
import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// a kotlin port of the chromaprint 1.4 default algorithm (test2) the android app compiles natively,
// so desktop fingerprints match acoustid without a native toolchain. stage for stage the same as the c++
object Chromaprint {
    val available: Boolean get() = true

    private const val MAX_SECONDS = 120

    data class RawFingerprint(val itemDurationMs: Int, val delayMs: Int, val values: IntArray)

    fun fingerprint(path: String): String? = raw(path, MAX_SECONDS)?.let { encode(it.values) }

    fun rawFingerprint(path: String, seconds: Int, http: com.aurora.music.desktop.audio.decode.HttpOptions = com.aurora.music.desktop.audio.decode.HttpOptions(),
        cancelled: () -> Boolean = { false }): RawFingerprint? = raw(path, seconds, cancelled, http)

    private fun raw(path: String, seconds: Int, cancelled: () -> Boolean = { false },
        http: com.aurora.music.desktop.audio.decode.HttpOptions = com.aurora.music.desktop.audio.decode.HttpOptions()): RawFingerprint? = runCatching {
        FfmpegDecoder.open(path, http).use { decoder ->
            val rate = decoder.info.sampleRate
            val fingerprinter = Fingerprinter(rate, 2)
            val buffer = DoubleArray(8192)
            val pcm = ShortArray(buffer.size)
            var frames = 0L
            while (frames < seconds.toLong() * rate && !cancelled()) {
                val read = decoder.read(buffer)
                if (read <= 0) break
                for (i in 0 until read * 2) pcm[i] = (buffer[i] * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort()
                fingerprinter.consume(pcm, read * 2)
                frames += read
            }
            if (frames == 0L) return null
            fingerprinter.finish()
            RawFingerprint((FRAME_STEP * 1000.0 / SAMPLE_RATE).toInt(),
                ((((FILTER_COEFFICIENTS.size - 1) + (MAX_WIDTH - 1)) * FRAME_STEP + FRAME_SIZE - FRAME_STEP) * 1000.0 / SAMPLE_RATE).toInt(),
                fingerprinter.fingerprint.toIntArray())
        }
    }.getOrNull()

    fun encode(values: IntArray, algorithm: Int = 1): String {
        val normal = ArrayList<Int>(values.size)
        val exceptional = ArrayList<Int>()
        values.forEachIndexed { i, value ->
            var x = if (i == 0) value else value xor values[i - 1]
            var bit = 1
            var last = 0
            while (x != 0) {
                if (x and 1 != 0) {
                    val delta = bit - last
                    if (delta >= 7) { normal += 7; exceptional += delta - 7 } else normal += delta
                    last = bit
                }
                x = x ushr 1
                bit++
            }
            normal += 0
        }
        val bytes = ArrayList<Byte>()
        bytes += algorithm.toByte()
        bytes += (values.size shr 16).toByte()
        bytes += (values.size shr 8).toByte()
        bytes += values.size.toByte()
        bytes += pack(normal, 3)
        bytes += pack(exceptional, 5)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
    }

    // values are packed least significant bit first, the way chromaprint's int3 and int5 arrays are
    private fun pack(values: List<Int>, bits: Int): List<Byte> {
        val out = ByteArray((values.size * bits + 7) / 8)
        var position = 0
        for (value in values) {
            for (b in 0 until bits) {
                if (value shr b and 1 != 0) out[position / 8] = (out[position / 8].toInt() or (1 shl (position % 8))).toByte()
                position++
            }
        }
        return out.asList()
    }

    internal const val SAMPLE_RATE = 11025
    private const val FRAME_SIZE = 4096
    private const val FRAME_STEP = FRAME_SIZE - (FRAME_SIZE - FRAME_SIZE / 3)
    private const val MIN_FREQ = 28
    private const val MAX_FREQ = 3520
    private val FILTER_COEFFICIENTS = doubleArrayOf(0.25, 0.75, 1.0, 0.75, 0.25)

    private class Classifier(val type: Int, val y: Int, val height: Int, val width: Int, val t0: Double, val t1: Double, val t2: Double)

    private val CLASSIFIERS = listOf(
        Classifier(0, 4, 3, 15, 1.98215, 2.35817, 2.63523),
        Classifier(4, 4, 6, 15, -1.03809, -0.651211, -0.282167),
        Classifier(1, 0, 4, 16, -0.298702, 0.119262, 0.558497),
        Classifier(3, 8, 2, 12, -0.105439, 0.0153946, 0.135898),
        Classifier(3, 4, 4, 8, -0.142891, 0.0258736, 0.200632),
        Classifier(4, 0, 3, 5, -0.826319, -0.590612, -0.368214),
        Classifier(1, 2, 2, 9, -0.557409, -0.233035, 0.0534525),
        Classifier(2, 7, 3, 4, -0.0646826, 0.00620476, 0.0784847),
        Classifier(2, 6, 2, 16, -0.192387, -0.029699, 0.215855),
        Classifier(2, 1, 3, 2, -0.0397818, -0.00568076, 0.0292026),
        Classifier(5, 10, 1, 15, -0.53823, -0.369934, -0.190235),
        Classifier(3, 6, 2, 10, -0.124877, 0.0296483, 0.139239),
        Classifier(2, 1, 1, 14, -0.101475, 0.0225617, 0.231971),
        Classifier(3, 5, 6, 4, -0.0799915, -0.00729616, 0.063262),
        Classifier(1, 9, 2, 12, -0.272556, 0.019424, 0.302559),
        Classifier(3, 4, 2, 14, -0.164292, -0.0321188, 0.0846339),
    )

    internal class Fingerprinter(sampleRate: Int, private val channels: Int) {
        val fingerprint = ArrayList<Int>()
        private val buffer = ShortArray(32 * 1024)
        private var offset = 0
        private val resampled = ShortArray(32 * 1024)
        private val resampler = if (sampleRate != SAMPLE_RATE) Resampler(SAMPLE_RATE, sampleRate) else null
        private val frames = Frames()

        fun consume(input: ShortArray, length: Int) {
            var remaining = length / channels
            var position = 0
            while (remaining > 0) {
                val count = min(remaining, buffer.size - offset)
                for (i in 0 until count) {
                    buffer[offset + i] = when (channels) {
                        1 -> input[position]
                        2 -> ((input[position] + input[position + 1]) / 2).toShort()
                        else -> ((0 until channels).sumOf { input[position + it].toInt() } / channels).toShort()
                    }
                    position += channels
                }
                offset += count
                remaining -= count
                if (offset == buffer.size) {
                    resample()
                    if (offset == buffer.size) return
                }
            }
        }

        fun finish() {
            if (offset > 0) resample()
        }

        private fun resample() {
            val r = resampler
            if (r == null) {
                frames.consume(buffer, offset)
                offset = 0
                return
            }
            val result = r.resample(resampled, buffer, offset, resampled.size)
            frames.consume(resampled, min(result.produced, resampled.size))
            val left = offset - result.consumed
            if (left > 0) System.arraycopy(buffer, result.consumed, buffer, 0, left)
            offset = max(left, 0)
        }

        private inner class Frames {
            private val window = FloatArray(FRAME_SIZE) { i -> ((1.0 / Short.MAX_VALUE) * (0.54 - 0.46 * cos(i * 2.0 * PI / (FRAME_SIZE - 1)))).toFloat() }
            private var pending = ShortArray(FRAME_SIZE * 2)
            private var pendingSize = 0
            private val fft = Fft(FRAME_SIZE)
            private val chroma = Chroma()

            fun consume(samples: ShortArray, length: Int) {
                if (pendingSize + length > pending.size) pending = pending.copyOf(max(pending.size * 2, pendingSize + length))
                System.arraycopy(samples, 0, pending, pendingSize, length)
                pendingSize += length
                var start = 0
                while (pendingSize - start >= FRAME_SIZE) {
                    for (i in 0 until FRAME_SIZE) fft.real[i] = (pending[start + i] * window[i]).toDouble()
                    chroma.consume(fft.power())
                    start += FRAME_STEP
                }
                System.arraycopy(pending, start, pending, 0, pendingSize - start)
                pendingSize -= start
            }
        }

        private inner class Chroma {
            private val minIndex = max(1, index(MIN_FREQ))
            private val maxIndex = min(FRAME_SIZE / 2, index(MAX_FREQ))
            private val notes = IntArray(FRAME_SIZE).also { notes ->
                for (i in minIndex until maxIndex) {
                    val octave = ln(i.toDouble() * SAMPLE_RATE / FRAME_SIZE / (440.0 / 16.0)) / ln(2.0)
                    notes[i] = (12 * (octave - floor(octave))).toInt()
                }
            }
            private val history = Array(8) { DoubleArray(12) }
            private var historyOffset = 0
            private var historySize = 1
            private val image = IntegralImage(256)

            private fun index(freq: Int): Int = Math.round(FRAME_SIZE.toDouble() * freq / SAMPLE_RATE).toInt()

            fun consume(power: DoubleArray) {
                val features = DoubleArray(12)
                for (i in minIndex until maxIndex) features[notes[i]] += power[i]
                features.copyInto(history[historyOffset])
                historyOffset = (historyOffset + 1) % 8
                if (historySize < FILTER_COEFFICIENTS.size) {
                    historySize++
                    return
                }
                val start = (historyOffset + 8 - FILTER_COEFFICIENTS.size) % 8
                val filtered = DoubleArray(12)
                for (i in 0 until 12) for (j in FILTER_COEFFICIENTS.indices) filtered[i] += history[(start + j) % 8][i] * FILTER_COEFFICIENTS[j]
                val norm = filtered.sumOf { it * it }.let { if (it > 0) sqrt(it) else 0.0 }
                for (i in 0 until 12) filtered[i] = if (norm < 0.01) 0.0 else filtered[i] / norm
                image.add(filtered)
                if (image.rows >= MAX_WIDTH) fingerprint += subfingerprint(image.rows - MAX_WIDTH)
            }

            private fun subfingerprint(x: Int): Int {
                var bits = 0
                for (c in CLASSIFIERS) bits = (bits shl 2) or GRAY[quantize(c, apply(c, x))]
                return bits
            }

            private fun quantize(c: Classifier, value: Double): Int = when {
                value < c.t1 -> if (value < c.t0) 0 else 1
                value < c.t2 -> 2
                else -> 3
            }

            private fun apply(c: Classifier, x: Int): Double {
                val y = c.y
                val w = c.width
                val h = c.height
                fun area(r1: Int, c1: Int, r2: Int, c2: Int) = image.area(r1, c1, r2, c2)
                fun cmp(a: Double, b: Double) = ln((1.0 + a) / (1.0 + b))
                return when (c.type) {
                    0 -> cmp(area(x, y, x + w, y + h), 0.0)
                    1 -> cmp(area(x, y + h / 2, x + w, y + h), area(x, y, x + w, y + h / 2))
                    2 -> cmp(area(x + w / 2, y, x + w, y + h), area(x, y, x + w / 2, y + h))
                    3 -> cmp(area(x, y + h / 2, x + w / 2, y + h) + area(x + w / 2, y, x + w, y + h / 2),
                        area(x, y, x + w / 2, y + h / 2) + area(x + w / 2, y + h / 2, x + w, y + h))
                    4 -> cmp(area(x, y + h / 3, x + w, y + 2 * (h / 3)), area(x, y, x + w, y + h / 3) + area(x, y + 2 * (h / 3), x + w, y + h))
                    5 -> cmp(area(x + w / 3, y, x + 2 * (w / 3), y + h), area(x, y, x + w / 3, y + h) + area(x + 2 * (w / 3), y, x + w, y + h))
                    else -> 0.0
                }
            }
        }
    }

    private val MAX_WIDTH = CLASSIFIERS.maxOf { it.width }
    private val GRAY = intArrayOf(0, 1, 3, 2)

    private class IntegralImage(maxRows: Int) {
        private val capacity = maxRows + 1
        private val data = DoubleArray(capacity * 12)
        var rows = 0
            private set

        fun add(row: DoubleArray) {
            val base = (rows % capacity) * 12
            var sum = 0.0
            for (i in 0 until 12) { sum += row[i]; data[base + i] = sum }
            if (rows > 0) {
                val previous = ((rows - 1) % capacity) * 12
                for (i in 0 until 12) data[base + i] = data[previous + i] + data[base + i]
            }
            rows++
        }

        private fun at(row: Int, column: Int) = data[(row % capacity) * 12 + column]

        fun area(r1: Int, c1: Int, r2: Int, c2: Int): Double {
            if (r1 == r2 || c1 == c2) return 0.0
            return if (r1 == 0) {
                if (c1 == 0) at(r2 - 1, c2 - 1) else at(r2 - 1, c2 - 1) - at(r2 - 1, c1 - 1)
            } else {
                if (c1 == 0) at(r2 - 1, c2 - 1) - at(r1 - 1, c2 - 1)
                else at(r2 - 1, c2 - 1) - at(r1 - 1, c2 - 1) - at(r2 - 1, c1 - 1) + at(r1 - 1, c1 - 1)
            }
        }
    }

    // chromaprint's bundled libavcodec resample2: 16-bit fixed point, kaiser windowed sinc, 256 phases
    internal class Resampler(outRate: Int, inRate: Int) {
        class Result(val produced: Int, val consumed: Int)

        private val phaseShift = 8
        private val phaseCount = 1 shl phaseShift
        private val phaseMask = phaseCount - 1
        private val factor = min(outRate * 0.8 / inRate, 1.0)
        private val length = max(ceil(16 / factor).toInt(), 1)
        private val bank = ShortArray(length * (phaseCount + 1))
        private val srcIncr = outRate
        private var dstIncr = inRate * phaseCount
        private var index = -phaseCount * ((length - 1) / 2)
        private var frac = 0

        init {
            val center = (length - 1) / 2
            val tab = DoubleArray(length)
            for (ph in 0 until phaseCount) {
                var norm = 0.0
                for (i in 0 until length) {
                    var x = PI * ((i - center).toDouble() - ph.toDouble() / phaseCount) * factor
                    var y = if (x == 0.0) 1.0 else sin(x) / x
                    val w = 2.0 * x / (factor * length * PI)
                    y *= bessel(9 * sqrt(max(1 - w * w, 0.0)))
                    tab[i] = y
                    norm += y
                }
                for (i in 0 until length) {
                    val scaled = (tab[i] * (1 shl 15) / norm).toFloat()
                    bank[ph * length + i] = Math.rint(scaled.toDouble()).toLong().coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt().toShort()
                }
            }
            System.arraycopy(bank, 0, bank, length * phaseCount + 1, length - 1)
            bank[length * phaseCount] = bank[length - 1]
        }

        private fun bessel(value: Double): Double {
            var v = 1.0
            var last = 0.0
            var t = 1.0
            val x = value * value / 4
            var i = 1
            while (v != last) {
                last = v
                t *= x / (i * i)
                v += t
                i++
            }
            return v
        }

        fun resample(dst: ShortArray, src: ShortArray, srcSize: Int, dstSize: Int): Result {
            var index = index
            var frac = frac
            val incrFrac = dstIncr % srcIncr
            val incr = dstIncr / srcIncr
            var produced = 0
            while (produced < dstSize) {
                val filter = length * (index and phaseMask)
                val sample = index shr phaseShift
                var value = 0
                if (sample < 0) {
                    for (i in 0 until length) value += src[abs(sample + i) % srcSize] * bank[filter + i]
                } else if (sample + length > srcSize) {
                    break
                } else {
                    for (i in 0 until length) value += src[sample + i] * bank[filter + i]
                }
                value = (value + (1 shl 14)) shr 15
                dst[produced] = (if ((value + 32768).toUInt() > 65535u) (value shr 31) xor 32767 else value).toShort()
                frac += incrFrac
                index += incr
                if (frac >= srcIncr) {
                    frac -= srcIncr
                    index++
                }
                produced++
            }
            val consumed = max(index, 0) shr phaseShift
            if (index >= 0) index = index and phaseMask
            this.frac = frac
            this.index = index
            dstIncr = incrFrac + srcIncr * incr
            return Result(produced, consumed)
        }
    }

    private class Fft(private val size: Int) {
        val real = DoubleArray(size)
        private val re = DoubleArray(size)
        private val im = DoubleArray(size)
        private val cosTable = DoubleArray(size / 2) { cos(2 * PI * it / size) }
        private val sinTable = DoubleArray(size / 2) { sin(2 * PI * it / size) }
        private val bits = Integer.numberOfTrailingZeros(size)
        private val output = DoubleArray(size / 2 + 1)

        fun power(): DoubleArray {
            for (i in 0 until size) {
                val j = Integer.reverse(i) ushr (32 - bits)
                re[j] = real[i]
                im[j] = 0.0
            }
            var span = 2
            while (span <= size) {
                val half = span / 2
                val step = size / span
                for (start in 0 until size step span) {
                    for (k in 0 until half) {
                        val c = cosTable[k * step]
                        val s = sinTable[k * step]
                        val a = start + k
                        val b = a + half
                        val tr = re[b] * c + im[b] * s
                        val ti = im[b] * c - re[b] * s
                        re[b] = re[a] - tr
                        im[b] = im[a] - ti
                        re[a] += tr
                        im[a] += ti
                    }
                }
                span *= 2
            }
            for (i in 0..size / 2) output[i] = (re[i].toFloat() * re[i].toFloat() + im[i].toFloat() * im[i].toFloat()).toDouble()
            return output
        }
    }
}
