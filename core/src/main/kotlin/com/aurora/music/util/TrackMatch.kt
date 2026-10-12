package com.aurora.music.util

import java.text.Normalizer
import java.util.Locale

object TrackMatch {
    // live cuts and extended mixes differ by more than this same recording rarely does
    const val DURATION_TOLERANCE_SEC = 4

    private val FEATURE = Regex("\\((feat|ft)\\.?[^)]*\\)")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    // accents fold only on latin letters; other scripts keep every letter and mark so different titles stay apart
    fun norm(s: String): String = buildString {
        for (c in Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFKC)) {
            append(if (Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN) Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0] else c)
        }
    }.replace(FEATURE, " ").replace(SEPARATORS, " ").trim()

    // names made only of symbols would otherwise all share an empty key
    fun identity(s: String): String = norm(s).ifEmpty { s.trim().lowercase(Locale.ROOT) }

    fun key(artist: String, title: String): String = identity(artist) + "|" + identity(title)
}
