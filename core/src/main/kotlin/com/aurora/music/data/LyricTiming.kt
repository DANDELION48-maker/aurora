package com.aurora.music.data

import com.aurora.music.model.LyricLine
import com.aurora.music.model.LyricWord
import java.lang.Character.UnicodeScript
import java.text.Normalizer
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val TOKEN = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}]\s*|[^\s\p{IsHan}\p{IsHiragana}\p{IsKatakana}]+\s*""")
private const val HOLD = 1.5
private const val MAX_STRETCH = 1.25
private const val DEFAULT_PACE = 260.0
private const val FILL_LEAD_MS = 80L
private const val CYRILLIC_VOWELS = "аеёиоуыэюяіїєў"
private const val GREEK_VOWELS = "αεηιουω"
private const val SMALL_KANA = "ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ"
private val ENGLISH = setOf("the", "you", "i", "and", "to", "my", "me", "a", "it", "in", "your", "of", "is",
    "that", "we", "dont", "im", "on", "be", "for", "all", "so", "but", "just", "oh", "know", "like")

// line-synced lyrics get word timing paced from the song's own syllable rate; real word tags just get their open ends closed
fun Lyrics.withWordTiming(): Lyrics {
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
    return copy(lines = lines.mapIndexed { i, line ->
        if (tokens[i].isEmpty()) return@mapIndexed line
        val near = (maxOf(0, i - 3)..minOf(lines.lastIndex, i + 3)).mapNotNull { samples[it] }
        val pace = (if (near.size >= 3) near.lowQuantile()!! else global).coerceIn(global * .6, global * 2)
        line.copy(words = if (line.words.isEmpty()) layout(line.startMs, tokens[i], syllables[i], next[i], pace)
            else close(line.words, syllables[i], next[i], pace))
    })
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
