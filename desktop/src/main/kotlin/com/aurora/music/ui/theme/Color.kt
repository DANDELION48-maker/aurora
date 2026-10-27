package com.aurora.music.ui.theme

import androidx.compose.ui.graphics.Color
import com.aurora.music.R
import com.aurora.music.localization.appString

// Sky: Children of the Light theme palette
// Ethereal Starlight Blue, Radiant Candlelight Gold & Twilight Aurora
val AuroraRose = Color(0xFF38BDF8)       // Sky Starlight Blue (Primary)
val AuroraRoseDeep = Color(0xFF0284C7)   // Deep Sky Cerulean
val AuroraCoral = Color(0xFFFBBF24)      // Golden Candle Flame (Secondary)
val AuroraMagenta = Color(0xFFA78BFA)    // Twilight Aurora Violet (Tertiary)
val AuroraBlush = Color(0xFFBAE6FD)      // Cloud Mist Tint
val AuroraAmber = Color(0xFFF59E0B)      // Winged Light Amber
val AuroraViolet = Color(0xFF818CF8)     // Celestial Star Indigo

val DarkBackground = Color(0xFF0B101E)
val DarkSurface = Color(0xFF131B2E)
val DarkSurfaceElevated = Color(0xFF1A2640)
val DarkSurfaceHigh = Color(0xFF223150)
val DarkOutline = Color(0xFF2D3F66)
val TextPrimaryDark = Color(0xFFF1F5F9)
val TextSecondaryDark = Color(0xFF94A3B8)

val LightBackground = Color(0xFFF4F8FC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceElevated = Color(0xFFE9F1F8)
val LightOutline = Color(0xFFCFDDE8)
val TextPrimaryLight = Color(0xFF0F172A)
val TextSecondaryLight = Color(0xFF475569)

data class AccentPreset(val name: String, val seed: Color)

val AccentPresets: List<AccentPreset> get() = listOf(
    AccentPreset(appString(R.string.text_rose_51ad0e), AuroraRose),
    AccentPreset(appString(R.string.text_coral_d27ca6), AuroraCoral),
    AccentPreset(appString(R.string.text_magenta_ff6912), AuroraMagenta),
    AccentPreset(appString(R.string.text_violet_ddcd2f), AuroraViolet),
    AccentPreset(appString(R.string.text_amber_27a01d), AuroraAmber),
    AccentPreset(appString(R.string.text_blue_7d44bc), Color(0xFF6366F1)),
    AccentPreset(appString(R.string.text_teal_df0ad3), Color(0xFF2DD4BF)),
    AccentPreset(appString(R.string.text_green_933bf2), Color(0xFF4ADE80)),
    AccentPreset(appString(R.string.text_sky_b3d97f), Color(0xFF7DD3FC)),
    AccentPreset(appString(R.string.text_mono_c5c553), Color(0xFFFB7185)),
)
