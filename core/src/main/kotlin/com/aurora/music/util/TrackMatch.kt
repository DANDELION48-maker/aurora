package com.aurora.music.util

import java.text.Normalizer
import java.util.Locale

object TrackMatch {
    // live cuts and extended mixes differ by more than this same recording rarely does
    const val DURATION_TOLERANCE_SEC = 4

    private val FEATURE = Regex("\\((feat|ft)\\.?[^)]*\\)")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    // accents fold only on latin letters; other scripts keep every letter and mark so different titles stay apart
    fun norm(s: String): String {
        val lower = s.lowercase(Locale.ROOT)
        // ascii is already nfkc and folds to itself
        val folded = if (lower.all { it.code < 0x80 }) lower else buildString(lower.length) {
            for (c in Normalizer.normalize(lower, Normalizer.Form.NFKC)) append(fold(c))
        }
        return folded.replace(FEATURE, " ").replace(SEPARATORS, " ").trim()
    }

    private val folds = CharArray(0x250)

    private fun fold(c: Char): Char {
        if (c.code < 0x80) return c
        if (c.code < folds.size && folds[c.code] != '\u0000') return folds[c.code]
        val out = if (Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN) Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0] else c
        if (c.code < folds.size) folds[c.code] = out
        return out
    }

    // names made only of symbols would otherwise all share an empty key
    fun identity(s: String): String = norm(s).ifEmpty { s.trim().lowercase(Locale.ROOT) }

    fun key(artist: String, title: String): String = identity(artist) + "|" + identity(title)
}
