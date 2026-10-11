package com.aurora.music.data

import com.aurora.music.model.LyricLine
import com.aurora.music.model.LyricWord

private val TOKEN = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}]\s*|[^\s\p{IsHan}\p{IsHiragana}\p{IsKatakana}]+\s*""")
private const val MS_PER_LETTER = 110L

// line-synced lyrics get estimated per-word timing; real word tags just get their open ends closed
fun Lyrics.withWordTiming(): Lyrics {
    if (!synced) return this
    return copy(lines = lines.mapIndexed { i, line ->
        if (line.startMs < 0 || line.displayText.isBlank()) return@mapIndexed line
        val next = lines.drop(i + 1).firstOrNull { it.startMs > line.startMs }?.startMs
        line.copy(words = if (line.words.isEmpty()) estimate(line, next) else close(line.words, next))
    })
}

private fun weight(text: String) = text.count(Char::isLetterOrDigit).coerceAtLeast(1) + 2

private fun estimate(line: LyricLine, next: Long?): List<LyricWord> {
    val matches = TOKEN.findAll(line.text).toList()
    if (matches.isEmpty()) return emptyList()
    val tokens = matches.map { it.value }.toMutableList()
    tokens[0] = line.text.substring(0, matches[0].range.first) + tokens[0]
    val weights = tokens.map(::weight)
    val total = weights.sum()
    val room = next?.let { it - line.startMs } ?: Long.MAX_VALUE
    val sung = minOf(room - minOf(250L, room / 10), total * MS_PER_LETTER + 400).coerceAtLeast(300)
    var acc = 0
    return tokens.mapIndexed { i, token ->
        val start = line.startMs + sung * acc / total
        acc += weights[i]
        LyricWord(start, line.startMs + sung * acc / total, token)
    }
}

private fun close(words: List<LyricWord>, next: Long?): List<LyricWord> = words.mapIndexed { i, w ->
    if (w.endMs > w.startMs) return@mapIndexed w
    val following = words.getOrNull(i + 1)?.startMs?.takeIf { it > w.startMs }
    val guess = w.startMs + weight(w.text) * MS_PER_LETTER + 300
    w.copy(endMs = following ?: next?.takeIf { it > w.startMs }?.let { minOf(it, guess) } ?: guess)
}

// per-character sung amount (0..1) of displayText at timeMs, with a soft two-character edge
fun LyricLine.charFill(timeMs: Long): FloatArray {
    val out = FloatArray(displayText.length)
    var offset = 0
    for (w in words) {
        val progress = ((timeMs - w.startMs).toFloat() / (w.endMs - w.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
        val letters = w.text.trimEnd().length.coerceAtLeast(1)
        for (i in w.text.indices) out[offset + i] = ((progress * (letters + 2) - i) / 2f).coerceIn(0f, 1f)
        offset += w.text.length
    }
    return out
}
