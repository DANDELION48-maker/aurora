package com.aurora.music.data

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

// an additive singer over a stereo band, with the exact time of every sung word
internal class SyntheticSong(private val seconds: Double, private val tempo: Double = 90.0, private val pad: Double = .025, seed: Long = 3,
    private val vibrato: Double = .015, private val gain: Double = .09) {
    val rate = 44100
    private val random = Random(seed)

    class Note(val start: Double, val end: Double, val hz: Double, val vowel: Int)
    class Word(val text: String, val notes: List<Note>) {
        val start get() = notes.first().start
        val end get() = notes.last().end
    }
    class Line(val words: List<Word>, val stampMs: Long) {
        val start get() = words.first().start
        val end get() = words.last().end
    }

    val lines = mutableListOf<Line>()
    private val leads = mutableListOf<Note>()

    inner class LineBuilder(var t: Double) {
        val words = mutableListOf<Word>()
        private var vowel = 0

        // one note per syllable; extra pitches become a run on the last syllable
        fun word(text: String, seconds: Double, vararg hz: Double, hold: Double = 0.0) {
            val count = syllables(text, true).coerceAtLeast(1)
            val each = (seconds - hold) / count
            val notes = mutableListOf<Note>()
            for (s in 0 until count) {
                val length = each + if (s == count - 1) hold else 0.0
                val pitches = if (s == count - 1 && hz.size > count) hz.drop(count - 1) else listOf(hz[s % hz.size])
                val v = vowel++ % 5
                pitches.forEachIndexed { k, p ->
                    notes += Note(t + length * k / pitches.size, t + length * (k + 1) / pitches.size, p, v)
                }
                t += length
            }
            words += Word(text, notes)
        }

        fun rest(seconds: Double) { t += seconds }
    }

    fun line(at: Double, stampShift: Double = 0.0, build: LineBuilder.() -> Unit): Line {
        val b = LineBuilder(at).apply(build)
        return Line(b.words, ((at + stampShift) * 1000).toLong()).also { lines += it }
    }

    // a centred synth melody, which a vocal detector can easily mistake for singing
    fun lead(start: Double, end: Double, hz: Double) { leads += Note(start, end, hz, 0) }

    fun lyrics(): Lyrics = Lyrics(lines.map { line ->
        LyricsRepository.timedLine(line.stampMs, line.words.joinToString(" ") { it.text })
    }, true, "synthetic")

    fun render(): ShortArray {
        val n = (seconds * rate).toInt()
        val voice = FloatArray(n)
        val notes = lines.flatMap { l -> l.words.flatMap { w -> w.notes.map { it to (it === w.notes.first()) } } }
        var phase = 0.0
        var lastHz = 0.0
        var lastEnd = -1.0
        for ((index, pair) in notes.withIndex()) {
            val (note, wordStart) = pair
            val legato = abs(note.start - lastEnd) < 1e-6
            val following = notes.getOrNull(index + 1)?.first
            val phraseEnd = following == null || following.start - note.end > 1e-6
            val from = (note.start * rate).toInt()
            val to = minOf(n, ((note.end + if (phraseEnd) .06 else 0.0) * rate).toInt())
            if (!legato) phase = 0.0
            val amps = DoubleArray(24) { h -> formant((h + 1) * note.hz, note.vowel) / (h + 1.0).pow(.6) }
            for (i in from until to) {
                val t = i.toDouble() / rate - note.start
                val glide = if (legato && t < .03) lastHz + (note.hz - lastHz) * t / .03 else note.hz
                val wobble = if (t > .25) 1 + vibrato * sin(2 * PI * 5.5 * t) else 1.0
                phase += glide * wobble / rate
                var env = if (legato) 1.0 else minOf(1.0, t / .025)
                if (wordStart && legato) env *= .35 + .65 * minOf(1.0, t / .05)
                if (phraseEnd) env *= minOf(1.0, (note.end + .06 - i.toDouble() / rate) / .06).coerceAtLeast(0.0)
                var s = 0.0
                var h = 1
                while (h <= 24 && h * glide < 3800) {
                    s += amps[h - 1] * sin(2 * PI * ((phase * h) % 1.0))
                    h++
                }
                voice[i] += (gain * env * s).toFloat()
                if (wordStart && t < .035) voice[i] += (.05 * (random.nextDouble() * 2 - 1)).toFloat()
            }
            lastHz = note.hz
            lastEnd = note.end
        }
        val combL = comb(voice, (rate * .0297).toInt())
        val combR = comb(voice, (rate * .0371).toInt())
        val out = ShortArray(n * 2)
        val beat = 60.0 / tempo
        val roots = doubleArrayOf(55.0, 49.0, 61.7, 41.2)
        val wide = arrayOf(doubleArrayOf(261.6, 392.0, 329.6, 493.9), doubleArrayOf(246.9, 370.0, 293.7, 440.0))
        val pads = arrayOf(doubleArrayOf(220.0, 277.2), doubleArrayOf(196.0, 246.9), doubleArrayOf(246.9, 311.1), doubleArrayOf(164.8, 207.7))
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val bar = (t / (beat * 4)).toInt()
            val inBeat = t % beat
            val count = (t / beat).toInt() % 4
            val kick = .25 * sin(2 * PI * 55 * inBeat) * exp(-inBeat / .08)
            val snare = if (count % 2 == 1) .15 * (random.nextDouble() * 2 - 1) * exp(-inBeat / .05) else 0.0
            val bass = .12 * sin(2 * PI * roots[bar % 4] * t)
            val chord = wide[(bar / 2) % 2]
            val left = .05 * (sin(2 * PI * chord[0] * t) + sin(2 * PI * chord[1] * t))
            val right = .05 * (sin(2 * PI * chord[2] * t) + sin(2 * PI * chord[3] * t))
            val p = pads[bar % 4]
            val centre = pad * (sin(2 * PI * p[0] * t) + sin(2 * PI * p[1] * t))
            var lead = 0.0
            for (l in leads) if (t >= l.start && t < l.end) {
                for (h in 1..6) lead += .05 * sin(2 * PI * l.hz * h * t) / h
            }
            val common = kick + snare + bass + centre + lead
            val l = voice[i] + .35 * (combL[i] - voice[i]) + common + left + .008 * random.nextGaussian()
            val r = voice[i] + .35 * (combR[i] - voice[i]) + common + right + .008 * random.nextGaussian()
            out[i * 2] = (l.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
            out[i * 2 + 1] = (r.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
        return out
    }

    fun analyze(): VocalActivity {
        val pcm = render()
        val analyzer = VocalActivityAnalyzer(rate, 2)
        var offset = 0
        while (offset < pcm.size) {
            val size = minOf(8192, pcm.size - offset)
            analyzer.feed(pcm.copyOfRange(offset, offset + size), size)
            offset += size
        }
        return analyzer.finish()
    }

    private fun comb(x: FloatArray, delay: Int): FloatArray {
        val y = FloatArray(x.size)
        for (i in x.indices) y[i] = x[i] + if (i >= delay) .5f * y[i - delay] else 0f
        return y
    }

    private fun formant(hz: Double, vowel: Int): Double {
        val f = VOWELS[vowel]
        return .2 + exp(-((hz - f[0]) / 160).pow(2)) + .7 * exp(-((hz - f[1]) / 220).pow(2)) + .4 * exp(-((hz - f[2]) / 300).pow(2))
    }

    private companion object {
        val VOWELS = arrayOf(doubleArrayOf(800.0, 1200.0, 2500.0), doubleArrayOf(400.0, 2000.0, 2600.0),
            doubleArrayOf(300.0, 2300.0, 3000.0), doubleArrayOf(450.0, 800.0, 2500.0), doubleArrayOf(325.0, 700.0, 2500.0))
    }
}
