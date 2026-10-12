package com.aurora.music.data

import java.lang.Character.UnicodeScript
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val TOKEN = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}]\s*|[^\s\p{IsHan}\p{IsHiragana}\p{IsKatakana}]+\s*""")
private const val HOLD = 1.5
private const val MAX_STRETCH = 1.25
private const val DEFAULT_PACE = 260.0
private const val FILL_LEAD_MS = 80L
private const val ACTIVE = .5f
private const val EARLY_MS = 200L
private const val LATE_MS = 1500L
private const val PHRASE_GAP_MS = 350L
private const val PHRASE_HOLD = .75
private const val HELD_SHARE = .15
private const val LONG_WEIGHT = .8
private const val FINAL_LONG = .25
private const val PAUSE_REWARD = 1.0
private const val BREATH_REWARD = .4
private const val ONSET_REWARD = .35
private const val HELD_PENALTY = .8
private const val MARK = .75f
private const val LEAD = 2.0
@JvmField var DEBUG_ALIGN = false
@JvmField var DEBUG_TRUTH = mapOf<Long, List<Long>>()
private const val CYRILLIC_VOWELS = "аеёиоуыэюяіїєў"
private const val GREEK_VOWELS = "αεηιουω"
private const val SMALL_KANA = "ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ"
private val ENGLISH = setOf("the", "you", "i", "and", "to", "my", "me", "a", "it", "in", "your", "of", "is",
    "that", "we", "dont", "im", "on", "be", "for", "all", "so", "but", "just", "oh", "know", "like")

// line-synced lyrics get word timing paced from the song's own syllable rate, then snapped to detected singing when available
fun Lyrics.withWordTiming(vocals: VocalActivity? = null): Lyrics {
    if (!synced) return this
    val next = lines.mapIndexed { i, line ->
        (i + 1 until lines.size).map { lines[it].startMs }.firstOrNull { it > line.startMs }
    }
    val tokens = lines.map { line ->
        when {
            line.startMs < 0 || line.displayText.isBlank() -> emptyList()
            line.words.isNotEmpty() -> line.words.map { it.text }
            else -> tokenize(line.text)
        }
    }
    val bare = tokens.flatten().map { it.lowercase().filter(Char::isLetter) }.filter(String::isNotEmpty)
    val english = bare.isNotEmpty() && bare.count { it in ENGLISH } >= bare.size * .1
    val syllables = tokens.map { line -> line.map { syllables(it, english) }.toIntArray() }
    val samples = lines.indices.map { i ->
        val total = syllables[i].sum()
        val room = next[i]?.let { it - lines[i].startMs }
        if (total < 2 || room == null || room > 20_000) null else room / (total + HOLD)
    }
    val global = (samples.filterNotNull().lowQuantile() ?: DEFAULT_PACE).coerceIn(110.0, 700.0)
    val paces = lines.indices.map { i ->
        val near = (maxOf(0, i - 3)..minOf(lines.lastIndex, i + 3)).mapNotNull { samples[it] }
        (if (near.size >= 3) near.lowQuantile()!! else global).coerceIn(global * .6, global * 2)
    }
    val estimates = lines.indices.map { i ->
        if (tokens[i].isEmpty() || lines[i].words.isNotEmpty()) null
        else layout(lines[i].startMs, tokens[i], syllables[i], next[i], paces[i])
    }
    val heard = if (vocals?.reliable == true) VoiceAligner(vocals).align(lines, next, estimates, syllables, global) else null
    return copy(lines = lines.mapIndexed { i, line ->
        if (tokens[i].isEmpty()) return@mapIndexed line
        if (line.words.isNotEmpty()) return@mapIndexed line.copy(words = close(line.words, syllables[i], next[i], paces[i]))
        line.copy(words = heard?.get(i) ?: estimates[i]!!)
    })
}

// words advance only while the voice is active: each line keeps the sung phrases its syllables account for,
// word breaks prefer breaths and note starts, and a long held note belongs to one word
private class VoiceAligner(private val vocals: VocalActivity) {
    private class Run(val from: Int, val to: Int)
    private class Cut(val end: Int, val start: Int, val reward: Double, val pause: Boolean)
    private class Sung(val phrases: List<List<Run>>, val stamp: Long, val joined: Boolean, val open: Boolean)

    private val frame = vocals.frameMs.toLong()
    private val spacing = noteSpacing()

    fun align(lines: List<LyricLine>, next: List<Long?>, estimates: List<List<LyricWord>?>, syllables: List<IntArray>,
        pace: Double): List<List<LyricWord>?> {
        val units = syllables.map { it.sum() + HOLD }
        val sung = lines.indices.map { i ->
            val words = estimates[i] ?: return@map null
            val estimated = (words.last().endMs - lines[i].startMs).coerceAtLeast(frame)
            window(lines[i].startMs, next[i] ?: (lines[i].startMs + estimated + 2000), estimated)
        }
        val voicedPace = lines.indices.mapNotNull { i ->
            sung[i]?.let { s -> voiced(s.phrases.take(keep(s.phrases, units[i] * pace)).flatten()) / units[i] }
        }.lowQuantile() ?: pace
        return lines.indices.map { i ->
            val s = sung[i] ?: return@map null
            place(s.phrases.take(keep(s.phrases, units[i] * voicedPace)).flatten(), s, syllables[i], estimates[i]!!)
        }
    }

    private fun frameOf(ms: Long) = Math.floorDiv(ms, frame).toInt().coerceAtLeast(0)

    private fun active(f: Int) = vocals.level(f) >= ACTIVE

    private fun peak(f: Int) = if (spacing > 0) vocals.onset(f) else 0f

    private fun voiced(runs: List<Run>): Double = runs.sumOf { r -> (r.from until r.to).sumOf { vocals.level(it).toDouble() } } * frame

    // the line's singing from its first entry up to the next line, dropping a note held over from the previous line
    private fun window(start: Long, limit: Long, estimated: Long): Sung? {
        val first = frameOf(start - EARLY_MS)
        val last = frameOf(limit - 60)
        val late = frameOf(minOf(start + maxOf(LATE_MS, minOf((limit - start) / 3, 3000)), limit - 300))
        var runs = mutableListOf<Run>()
        var f = first
        while (f < last) {
            if (!active(f)) { f++; continue }
            val from = f
            while (f < last && active(f)) f++
            runs += Run(from, f)
        }
        val opening = runs.firstOrNull() ?: return null
        var joined = opening.from == first && first > 0 && active(first - 1)
        if (joined && runs.size > 1) {
            var since = first
            while (since > 0 && first - since < 50 && active(since - 1)) since--
            val held = first - since >= 600 / frame && opening.to <= frameOf(start + 800) &&
                (runs[1].from - opening.to) * frame >= 150 && (first until opening.to - 2).none { vocals.onset(it) >= .5f }
            if (held) { runs = runs.drop(1).toMutableList(); joined = false }
        }
        if (runs.first().from > late) return null
        val cap = minOf(last, runs.first().from + ((maxOf(estimated * 2, 1200)) / frame).toInt())
        runs = runs.filter { it.from < cap }.map { Run(it.from, minOf(it.to, cap)) }.toMutableList()
        val open = runs.last().to >= cap
        if ((runs.last().to - runs.first().from) * frame < 250) return null
        val phrases = mutableListOf(mutableListOf(runs.first()))
        for (r in runs.drop(1)) {
            if ((r.from - phrases.last().last().to) * frame < PHRASE_GAP_MS) phrases.last() += r else phrases += mutableListOf(r)
        }
        return Sung(phrases, start, joined, open)
    }

    // later phrases join while they bring the sung time closer to what the syllables need; missing words cost more
    // than held notes, and long silences in between cost extra
    private fun keep(phrases: List<List<Run>>, expected: Double): Int {
        var voiced = 0.0
        var silence = 0.0
        var best = 1
        var lowest = Double.MAX_VALUE
        phrases.forEachIndexed { j, p ->
            if (j > 0) silence += (p.first().from - phrases[j - 1].last().to) * frame - PHRASE_GAP_MS
            voiced += voiced(p)
            val miss = ln(voiced.coerceAtLeast(1.0) / expected)
            val cost = (if (miss < 0) -1.5 * miss else .5 * miss) + .25 * silence / 1000
            if (cost < lowest) { lowest = cost; best = j + 1 }
        }
        return best
    }

    // a dynamic programme over word breaks: durations follow the syllables at the singer's note rate, breaks are drawn
    // to breaths and note starts and kept out of held notes, and one long word is likelier than many slightly long ones
    private fun place(runs: List<Run>, line: Sung, syllables: IntArray, words: List<LyricWord>): List<LyricWord>? {
        val n = syllables.count { it > 0 }
        if (n == 0) return null
        val from = runs.first().from
        val size = runs.last().to - from
        val sung = DoubleArray(size + 1)
        val held = DoubleArray(size + 1)
        val holding = BooleanArray(size)
        val within = BooleanArray(size)
        if (spacing > 0) for (r in runs) {
            var mark = r.from
            for (f in r.from + 1..r.to) {
                if (f < r.to && peak(f) < MARK) continue
                if (f - mark > maxOf(30, spacing * 5 / 2)) {
                    for (h in mark + 2 until f) within[h - from] = true
                    for (h in mark + maxOf(15, spacing * 3 / 2) until f) holding[h - from] = true
                }
                mark = f
            }
        }
        // a short dip inside a phrase may be a quiet syllable rather than a breath, so it still counts as singing
        val counted = BooleanArray(size)
        runs.forEachIndexed { k, r ->
            val gap = if (k > 0) r.from - runs[k - 1].to else 0
            val soft = k > 0 && gap * frame < PHRASE_GAP_MS
            for (f in (if (soft) runs[k - 1].to else r.from) until r.to) counted[f - from] = true
        }
        for (i in 0 until size) {
            val level = if (counted[i]) 1.0 else 0.0
            sung[i + 1] = sung[i] + level
            held[i + 1] = held[i] + if (holding[i]) level else 0.0
        }
        val units = syllables.filter { it > 0 }.mapIndexed { j, s -> s + if (j == n - 1 && !line.open) HOLD else 0.0 }
        val even = (sung[size] - (1 - HELD_SHARE) * held[size]) / units.sum()
        if (even <= 0 || sung[size] * frame < syllables.sum() * 50) return null
        val pace = if (spacing > 0) spacing.toDouble().coerceIn(even * .5, even) else even
        val stride = maxOf(1, runs.sumOf { it.to - it.from } / 100)
        val cuts = mutableListOf<Cut>()
        runs.forEachIndexed { k, r ->
            if (k > 0) {
                val gap = runs[k - 1].to until r.from + 1
                val pause = (r.from - runs[k - 1].to) * frame >= PHRASE_GAP_MS
                cuts += Cut(gap.first - from, gap.last - from, if (pause) PAUSE_REWARD else BREATH_REWARD, pause)
                if (!pause) for (f in gap) if (f == gap.first || f == gap.last || (f - gap.first) % stride == 0) cuts += Cut(f - from, f - from, 0.0, false)
            }
            for (f in r.from + 1 until r.to - 1) {
                val onset = peak(f)
                if (onset <= 0f && (f - r.from) % stride != 0) continue
                val reward = if (onset > 0f) ONSET_REWARD * (2 * onset - 1) else 0.0
                cuts += Cut(f - from, f - from, reward - if (within[f - from]) HELD_PENALTY else 0.0, false)
            }
        }
        cuts.sortBy { it.end }
        cuts += Cut(size, size, 0.0, false)
        val m = cuts.size
        fun cost(j: Int, start: Int, cut: Cut): Double {
            val length = sung[cut.end] - sung[start]
            val effective = length - (1 - HELD_SHARE) * (held[cut.end] - held[start])
            if (length <= 0 || effective <= 0) return Double.MAX_VALUE
            val last = j == n - 1
            val expected = (units[j] + if (cut.pause && !last) PHRASE_HOLD else 0.0) * pace
            val r = ln(effective / expected)
            val shape = when {
                r < 0 -> when {
                    last && line.open -> .25 * r * r
                    cut.pause -> .5 * r * r
                    else -> r * r
                }
                last && !line.open || cut.pause -> FINAL_LONG * r
                else -> LONG_WEIGHT * r
            }
            return shape - if (cut.end < size) cut.reward else 0.0
        }
        // singing that runs straight into the line can start at any note near its timestamp
        val heads = mutableListOf(0 to 0.0)
        val choose = line.joined && spacing > 0
        if (choose) {
            val latest = frameOf(line.stamp + 400) - from
            cuts.filter { it.end in 1..latest && !it.pause }.forEach { heads += it.start to -it.reward }
        }
        val lead = heads.map { (start, extra) -> extra + if (choose) LEAD * abs((from + start) * frame - line.stamp) / 1000 else 0.0 }
        val best = Array(n) { DoubleArray(m) { Double.MAX_VALUE } }
        val back = Array(n) { IntArray(m) { -1 } }
        for (b in (if (n == 1) m - 1 else 0) until (if (n == 1) m else m - 1)) for (h in heads.indices) {
            if (heads[h].first >= cuts[b].end) continue
            val step = cost(0, heads[h].first, cuts[b])
            if (step != Double.MAX_VALUE && lead[h] + step < best[0][b]) { best[0][b] = lead[h] + step; back[0][b] = h }
        }
        for (j in 1 until n) for (b in j until m) {
            if (j == n - 1 && b != m - 1 || j < n - 1 && b == m - 1) continue
            for (a in j - 1 until b) {
                val prior = best[j - 1][a]
                if (prior == Double.MAX_VALUE || cuts[a].start >= cuts[b].end) continue
                val step = cost(j, cuts[a].start, cuts[b])
                if (step != Double.MAX_VALUE && prior + step < best[j][b]) { best[j][b] = prior + step; back[j][b] = a }
            }
        }
        if (best[n - 1][m - 1] == Double.MAX_VALUE) return null
        if (DEBUG_ALIGN) println("line ${line.stamp} from=${from * frame} spacing=$spacing even=$even pace=$pace cuts=" + cuts.filter { it.reward > 0.1 || it.reward < -0.1 }.joinToString { "${(from + it.end) * frame}:${"%.2f".format(it.reward)}" })
        val starts = IntArray(n)
        val stops = IntArray(n)
        var b = m - 1
        for (j in n - 1 downTo 0) {
            stops[j] = cuts[b].end
            if (j > 0) { b = back[j][b]; starts[j] = cuts[b].start } else starts[0] = heads[back[0][b]].first
        }
        DEBUG_TRUTH[line.stamp]?.let { truth ->
            var total = 0.0
            var prev = 0
            for (q in 0 until n) {
                val endCut = if (q == n - 1) cuts.last() else cuts.filter { it.end > prev }.minBy { abs((from + it.start) * frame - truth[q + 1]) / 150.0 - it.reward }
                val c = cost(q, prev, endCut)
                println("  truth $q ${(from + prev) * frame}-${(from + endCut.end) * frame} len=${"%.1f".format(sung[endCut.end] - sung[prev])} held=${"%.1f".format(held[endCut.end] - held[prev])} cost=${"%.3f".format(c)}")
                total += c
                prev = endCut.start
            }
            println("  truth total ${"%.3f".format(total)} chosen total ${"%.3f".format(best[n - 1][m - 1])}")
        }
        if (DEBUG_ALIGN) for (q in 0 until n) {
            val cut = cuts.first { it.end == stops[q] }
            println("  word $q ${(from + starts[q]) * frame}-${(from + stops[q]) * frame} len=${"%.1f".format(sung[stops[q]] - sung[starts[q]])} cost=${"%.3f".format(cost(q, starts[q], cut))}")
        }
        var j = 0
        return words.mapIndexed { k, w ->
            if (syllables[k] > 0) j++
            val (start, stop) = when {
                syllables[k] > 0 -> starts[j - 1] to stops[j - 1]
                j < n -> starts[j] to starts[j]
                else -> stops[n - 1] to stops[n - 1]
            }
            LyricWord((from + start) * frame, (from + stop) * frame, w.text)
        }
    }

    // typical frames between clear note starts within a phrase, or 0 when they don't look like singing
    private fun noteSpacing(): Int {
        if (vocals.onsets.isEmpty()) return 0
        val gaps = mutableListOf<Int>()
        var last = -1
        var broken = true
        for (f in vocals.onsets.indices) {
            if (!active(f) && !active(f + 1) && !active(f + 2)) broken = true
            if (vocals.onset(f) < MARK) continue
            if (!broken && last >= 0) gaps += f - last
            last = f
            broken = false
        }
        if (gaps.size < 20) return 0
        return gaps.sorted()[gaps.size / 2].takeIf { it in 6..45 } ?: 0
    }
}

private fun List<Double>.lowQuantile(): Double? = if (isEmpty()) null else sorted()[((size - 1) * .35).roundToInt()]

private fun tokenize(text: String): List<String> {
    val matches = TOKEN.findAll(text).toList()
    if (matches.isEmpty()) return emptyList()
    val tokens = matches.map { it.value }.toMutableList()
    tokens[0] = text.substring(0, matches[0].range.first) + tokens[0]
    return tokens
}

// singers keep a steady syllable rate, hold the phrase's last word, then breathe
private fun layout(start: Long, tokens: List<String>, syllables: IntArray, next: Long?, pace: Double): List<LyricWord> {
    val last = syllables.indexOfLast { it > 0 }
    if (last < 0) return emptyList()
    val span = DoubleArray(tokens.size) { k -> (syllables[k] + if (k == last) HOLD else 0.0) * pace }
    val natural = span.sum()
    val avail = next?.let { (it - start).toDouble() }?.let { it - minOf(250.0, it / 10) }
    if (avail != null) {
        if (natural >= avail) for (k in span.indices) span[k] *= avail / natural
        else {
            val stretch = minOf(avail / natural, MAX_STRETCH)
            for (k in span.indices) span[k] *= stretch
            span[last] += minOf(avail - natural * stretch, span[last])
        }
    }
    var t = start.toDouble()
    return tokens.mapIndexed { k, token ->
        val from = t
        t += span[k]
        LyricWord(from.roundToLong(), t.roundToLong(), token)
    }
}

private fun close(words: List<LyricWord>, syllables: IntArray, next: Long?, pace: Double): List<LyricWord> = words.mapIndexed { i, w ->
    if (w.endMs > w.startMs) return@mapIndexed w
    val following = words.getOrNull(i + 1)?.startMs?.takeIf { it > w.startMs }
    val guess = w.startMs + ((syllables.getOrElse(i) { 1 } + HOLD) * pace).roundToLong()
    w.copy(endMs = following ?: next?.takeIf { it > w.startMs }?.let { minOf(it, guess) } ?: guess)
}

internal fun syllables(token: String, english: Boolean): Int {
    var count = 0
    var inVowel = false
    var other = 0
    var letters = 0
    val latin = StringBuilder()
    for (c in token) {
        if (!c.isLetterOrDigit()) { inVowel = false; continue }
        letters++
        when (val script = UnicodeScript.of(c.code)) {
            UnicodeScript.LATIN, UnicodeScript.GREEK, UnicodeScript.CYRILLIC -> {
                val base = if (script == UnicodeScript.CYRILLIC) c.lowercaseChar()
                    else Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar()
                val vowel = when (script) {
                    UnicodeScript.LATIN -> { latin.append(base); base in "aeiouyæøœ" }
                    UnicodeScript.GREEK -> base in GREEK_VOWELS
                    else -> base in CYRILLIC_VOWELS
                }
                if (vowel && !inVowel) count++
                inVowel = vowel
            }
            UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.HANGUL, UnicodeScript.COMMON -> {
                inVowel = false
                if (c !in SMALL_KANA) count++
            }
            else -> { inVowel = false; other++ }
        }
    }
    val w = latin.toString()
    if (english && count > 1 && w.length > 2) {
        val before = w[w.length - 3]
        val consonantLe = w.endsWith("le") && before !in "aeiouy"
        if (w.endsWith("e") && !w.endsWith("ee") && !consonantLe) count--
        else if (w.endsWith("ed") && before !in "td") count--
        else if (w.endsWith("es") && before !in "sxzcgh") count--
    }
    count += (other + 1) / 2
    return if (letters > 0) count.coerceAtLeast(1) else 0
}

// per-character sung amount (0..1) of displayText at timeMs; lights slightly ahead so it reads in time
fun LyricLine.charFill(timeMs: Long): FloatArray {
    val out = FloatArray(displayText.length)
    val t = timeMs + FILL_LEAD_MS
    var offset = 0
    for (w in words) {
        val progress = ((t - w.startMs).toFloat() / (w.endMs - w.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
        val letters = w.text.trimEnd().length.coerceAtLeast(1)
        for (i in w.text.indices) out[offset + i] = (progress * letters - i).coerceIn(0f, 1f)
        offset += w.text.length
    }
    return out
}
