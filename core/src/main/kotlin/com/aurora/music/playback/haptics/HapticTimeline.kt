package com.aurora.music.playback.haptics

import kotlin.math.abs

data class HapticTap(val timeUs: Long, val kind: HapticKind, val strength: Float)

object HapticDetail {
    const val BEAT = 0
    const val RHYTHM = 1
    const val DETAILED = 2
}

// taps waiting for the moment they are heard; hits closer together than a vibration can tell apart become one
class HapticTimeline {
    private val taps = ArrayDeque<HapticTap>()
    private val textureTimes = LongArray(TEXTURE_POINTS)
    private val textureLevels = FloatArray(TEXTURE_POINTS)
    private var textureCount = 0
    private var textureHead = 0
    private var lastKickUs = Long.MIN_VALUE / 2

    val pending: Int get() = taps.size

    fun clear() {
        taps.clear()
        textureCount = 0
        textureHead = 0
        lastKickUs = Long.MIN_VALUE / 2
    }

    fun addTap(timeUs: Long, kind: HapticKind, strength: Float, detail: Int) {
        if (kind == HapticKind.KICK) lastKickUs = maxOf(lastKickUs, timeUs)
        val wanted = when (kind) {
            HapticKind.KICK -> true
            HapticKind.SNARE -> detail >= HapticDetail.RHYTHM || timeUs - lastKickUs > KICKLESS_US
            HapticKind.HAT -> detail >= HapticDetail.DETAILED
        }
        if (!wanted) return
        for (i in taps.indices) {
            val other = taps[i]
            if (abs(other.timeUs - timeUs) > MERGE_US) continue
            val time = when {
                rank(kind) > rank(other.kind) -> timeUs
                rank(kind) < rank(other.kind) -> other.timeUs
                else -> minOf(other.timeUs, timeUs)
            }
            taps.removeAt(i)
            insert(HapticTap(time, if (rank(kind) > rank(other.kind)) kind else other.kind, maxOf(other.strength, strength)))
            return
        }
        if (taps.size >= MAX_TAPS) taps.removeFirst()
        insert(HapticTap(timeUs, kind, strength))
    }

    private fun insert(tap: HapticTap) {
        var at = taps.size
        while (at > 0 && taps[at - 1].timeUs > tap.timeUs) at--
        taps.add(at, tap)
    }

    fun addTexture(timeUs: Long, level: Float) {
        val slot = (textureHead + textureCount) % TEXTURE_POINTS
        textureTimes[slot] = timeUs
        textureLevels[slot] = level
        if (textureCount < TEXTURE_POINTS) textureCount++ else textureHead = (textureHead + 1) % TEXTURE_POINTS
    }

    fun nextDueUs(leadUs: Long): Long? = taps.firstOrNull()?.let { it.timeUs - leadUs }

    // taps whose moment has come; ones already well past are dropped rather than played late
    inline fun due(positionUs: Long, leadUs: Long, play: (HapticTap) -> Unit) {
        while (true) {
            val next = peek() ?: return
            if (next.timeUs - leadUs > positionUs) return
            poll()
            if (next.timeUs - leadUs >= positionUs - STALE_US) play(next)
        }
    }

    fun peek(): HapticTap? = taps.firstOrNull()
    fun poll(): HapticTap? = taps.removeFirstOrNull()

    fun hasTapBetween(fromUs: Long, toUs: Long): Boolean = taps.any { it.timeUs in fromUs until toUs }

    // texture level at a media time, held from the nearest earlier point
    fun textureAt(timeUs: Long): Float {
        var best = -1
        var bestTime = Long.MIN_VALUE
        for (k in 0 until textureCount) {
            val slot = (textureHead + k) % TEXTURE_POINTS
            val t = textureTimes[slot]
            if (t <= timeUs && t > bestTime) {
                best = slot
                bestTime = t
            }
        }
        return if (best < 0 || timeUs - bestTime > TEXTURE_HOLD_US) 0f else textureLevels[best]
    }

    companion object {
        const val MERGE_US = 30_000L
        const val STALE_US = 60_000L
        const val KICKLESS_US = 1_500_000L
        const val TEXTURE_HOLD_US = 60_000L
        private const val MAX_TAPS = 512
        private const val TEXTURE_POINTS = 256

        private fun rank(kind: HapticKind) = when (kind) {
            HapticKind.KICK -> 2
            HapticKind.SNARE -> 1
            HapticKind.HAT -> 0
        }
    }
}

// the audible media position, carried forward between the sink's position reports
class HapticClock(private val maxExtrapolateUs: Long = 150_000) {
    private val lock = Any()
    private var positionUs = 0L
    private var atNanos = 0L
    private var speed = 1f
    private var playing = false

    fun update(positionUs: Long, nowNanos: Long, speed: Float) = synchronized(lock) {
        this.positionUs = positionUs
        atNanos = nowNanos
        this.speed = if (speed > 0f) speed else 1f
    }

    fun playing(value: Boolean, nowNanos: Long) = synchronized(lock) {
        if (playing && !value && atNanos != 0L) {
            positionUs = extrapolate(nowNanos)
            atNanos = nowNanos
        }
        if (!playing && value && atNanos != 0L) atNanos = nowNanos
        playing = value
    }

    fun reset() = synchronized(lock) {
        positionUs = 0L
        atNanos = 0L
    }

    fun audibleUs(nowNanos: Long): Long? = synchronized(lock) {
        if (!playing || atNanos == 0L) null else extrapolate(nowNanos)
    }

    private fun extrapolate(nowNanos: Long): Long {
        val elapsedUs = ((nowNanos - atNanos) / 1_000).coerceIn(0, maxExtrapolateUs)
        return positionUs + (elapsedUs * speed).toLong()
    }
}
