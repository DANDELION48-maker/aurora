package com.aurora.music.data

import kotlin.math.hypot

// sizes in metres and reverb times in seconds, each from smallest to biggest venue of its kind
class ConcertVenue(
    val name: String,
    private val width: ClosedFloatingPointRange<Float>,
    private val length: ClosedFloatingPointRange<Float>,
    private val height: ClosedFloatingPointRange<Float>,
    private val decay: ClosedFloatingPointRange<Float>,
    private val stage: ClosedFloatingPointRange<Float>,
    val openness: Float,
    val directivity: Float,
    val speakerHeightM: Float,
    val walls: Float,
    val boomDb: Float,
) {
    fun widthM(size: Float) = lerp(width, size)
    fun lengthM(size: Float) = lerp(length, size)
    fun heightM(size: Float) = lerp(height, size)
    fun decaySec(size: Float) = lerp(decay, size)
    fun stageWidthM(size: Float) = lerp(stage, size)
    private fun lerp(r: ClosedFloatingPointRange<Float>, t: Float) = r.start + (r.endInclusive - r.start) * t.coerceIn(0f, 1f)

    companion object {
        const val CLUB = 0; const val THEATRE = 1; const val ARENA = 2; const val STADIUM = 3; const val FESTIVAL = 4; const val AMPHITHEATRE = 5
        val ALL = listOf(
            ConcertVenue("club", 10f..26f, 14f..36f, 4f..8f, 0.5f..1.0f, 5f..10f, openness = 0f, directivity = 8f, speakerHeightM = 3f, walls = 0.7f, boomDb = 3f),
            ConcertVenue("concert hall", 22f..38f, 28f..52f, 12f..20f, 1.5f..2.3f, 10f..18f, openness = 0f, directivity = 5f, speakerHeightM = 6f, walls = 0.8f, boomDb = 2f),
            ConcertVenue("arena", 60f..100f, 80f..130f, 25f..40f, 2.2f..3.6f, 20f..34f, openness = 0f, directivity = 10f, speakerHeightM = 14f, walls = 0.6f, boomDb = 5f),
            ConcertVenue("stadium", 110f..170f, 150f..230f, 35f..55f, 3f..5f, 34f..56f, openness = 0.55f, directivity = 12f, speakerHeightM = 18f, walls = 0.55f, boomDb = 5f),
            ConcertVenue("festival", 70f..220f, 90f..300f, 20f..20f, 1f..1.6f, 24f..48f, openness = 1f, directivity = 10f, speakerHeightM = 12f, walls = 0.3f, boomDb = 0f),
            ConcertVenue("amphitheatre", 40f..100f, 40f..90f, 15f..30f, 1f..1.8f, 14f..26f, openness = 0.6f, directivity = 6f, speakerHeightM = 8f, walls = 0.7f, boomDb = 2f),
        )
    }
}

object ConcertSeat {
    const val FRONT = 0; const val MIDDLE = 1; const val BACK = 2; const val SIDE = 3; const val BALCONY = 4
    val ALL = listOf(FRONT, MIDDLE, BACK, SIDE, BALCONY)
}

// positions are fractions of the venue floor: x across, y from the front wall to the back
data class ConcertPrefs(
    val enabled: Boolean = false,
    val venue: Int = ConcertVenue.ARENA,
    val amount: Float = 1f,
    val size: Float = 0.5f,
    val openness: Float = 0f,
    val stageX: Float = 0.5f,
    val stageY: Float = 0.06f,
    val listenerX: Float = 0.5f,
    val listenerY: Float = 0.43f,
    val elevation: Float = 0f,
) {
    fun clamped(): ConcertPrefs = copy(
        venue = venue.coerceIn(0, ConcertVenue.ALL.lastIndex), amount = amount.unit(1f), size = size.unit(0.5f),
        openness = openness.unit(0f), stageX = stageX.unit(0.5f), stageY = stageY.unit(0.06f),
        listenerX = listenerX.unit(0.5f), listenerY = listenerY.unit(0.43f), elevation = elevation.unit(0f),
    )

    fun withVenue(id: Int): ConcertPrefs = ConcertVenue.ALL.getOrNull(id)?.let {
        ConcertPrefs(enabled = enabled, venue = id, amount = amount, openness = it.openness).withSeat(ConcertSeat.MIDDLE)
    } ?: this

    fun withSeat(seat: Int): ConcertPrefs {
        val centred = hypot(1f - 2 * stageX, 1f - 2 * stageY) < 0.3f
        val dx = (if (centred) stageX else 1f - stageX) - stageX
        val dy = (if (centred) 0.96f else 1f - stageY) - stageY
        val (along, aside) = when (seat) {
            ConcertSeat.FRONT -> 0.07f to 0f
            ConcertSeat.BACK -> 0.92f to 0f
            ConcertSeat.SIDE -> 0.3f to 0.42f
            ConcertSeat.BALCONY -> 0.85f to 0f
            else -> 0.42f to 0f
        }
        val x = stageX + dx * along - dy * aside
        val y = stageY + dy * along + dx * aside
        return copy(listenerX = x.coerceIn(0.02f, 0.98f), listenerY = y.coerceIn(0.02f, 0.98f), elevation = if (seat == ConcertSeat.BALCONY) 1f else 0f)
    }

    fun seat(): Int? = ConcertSeat.ALL.firstOrNull { s ->
        val at = withSeat(s)
        hypot(at.listenerX - listenerX, at.listenerY - listenerY) < 0.01f && at.elevation == elevation
    }

    private fun Float.unit(fallback: Float) = (if (isFinite()) this else fallback).coerceIn(0f, 1f)
}
