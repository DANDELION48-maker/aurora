package com.aurora.music.desktop.player

fun volumeGain(position: Float): Float {
    if (position.isNaN() || position <= 0f) return 0f
    if (position >= 1f) return 1f
    return position * position * position
}

fun volumePosition(gain: Float): Float {
    if (gain.isNaN() || gain <= 0f) return 0f
    if (gain >= 1f) return 1f
    return Math.cbrt(gain.toDouble()).toFloat()
}
