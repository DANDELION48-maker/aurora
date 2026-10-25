package com.aurora.music.data

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.tan

object ConcertLayout { const val END = 0; const val CENTRE = 1 }

object ConcertEarplugs { const val NONE = 0; const val MUSICIAN = 1; const val FOAM = 2 }

// 0 follows the venue; left/right stacks give a power alley, a cardioid array keeps the bass off the stage
object ConcertSubs { const val AUTO = 0; const val LEFT_RIGHT = 1; const val CENTRE = 2; const val CARDIOID = 3 }

// sizes in metres, each from smallest to biggest venue of its kind; surfaces set how the room rings per frequency;
// stands are raked seating along the sides set in standSides, starting tierBaseM above the floor
class ConcertVenue(
    val name: String,
    private val width: ClosedFloatingPointRange<Float>,
    private val length: ClosedFloatingPointRange<Float>,
    private val height: ClosedFloatingPointRange<Float>,
    private val stage: ClosedFloatingPointRange<Float>,
    val openness: Float,
    val directivity: Float,
    val speakerHeightM: Float,
    val wallMaterial: ConcertMaterial,
    val ceilingMaterial: ConcertMaterial,
    val floorMaterial: ConcertMaterial,
    val boomDb: Float,
    val seatMaterial: ConcertMaterial = ConcertMaterial.PLASTIC_SEATS,
    val layout: Int = ConcertLayout.END,
    private val stands: ClosedFloatingPointRange<Float> = 0f..0f,
    val standSides: Int = 0,
    val tierBaseM: Float = 0f,
    val rakeDeg: Float = 0f,
    val delayTowers: Boolean = false,
    val subs: Int = ConcertSubs.LEFT_RIGHT,
    val frontFills: Boolean = true,
) {
    fun widthM(size: Float) = lerp(width, size)
    fun lengthM(size: Float) = lerp(length, size)
    fun heightM(size: Float) = lerp(height, size)
    fun stageWidthM(size: Float) = lerp(stage, size)
    fun standsM(size: Float) = lerp(stands, size)

    // the standing floor inside the stands, as fractions: left, front, right, back
    fun floor(size: Float): FloatArray {
        val sx = standsM(size) / widthM(size); val sy = standsM(size) / lengthM(size)
        return floatArrayOf(if (standSides and LEFT != 0) sx else 0f, if (standSides and FRONT != 0) sy else 0f,
            1f - if (standSides and RIGHT != 0) sx else 0f, 1f - if (standSides and BACK != 0) sy else 0f)
    }

    // how far up the stands a spot is, in metres above the floor
    fun tierHeightM(size: Float, x: Float, y: Float): Float {
        val st = standsM(size)
        if (st <= 0f) return 0f
        val w = widthM(size); val l = lengthM(size)
        var depth = 0f
        if (standSides and FRONT != 0) depth = maxOf(depth, st - y * l)
        if (standSides and BACK != 0) depth = maxOf(depth, st - (1 - y) * l)
        if (standSides and LEFT != 0) depth = maxOf(depth, st - x * w)
        if (standSides and RIGHT != 0) depth = maxOf(depth, st - (1 - x) * w)
        return if (depth <= 0f) 0f else tierBaseM + depth * tan(Math.toRadians(rakeDeg.toDouble())).toFloat()
    }

    private fun lerp(r: ClosedFloatingPointRange<Float>, t: Float) = r.start + (r.endInclusive - r.start) * t.coerceIn(0f, 1f)

    companion object {
        const val CLUB = 0; const val THEATRE = 1; const val ARENA = 2; const val STADIUM = 3; const val FESTIVAL = 4
        const val AMPHITHEATRE = 5; const val BASEMENT = 6; const val CHURCH = 7; const val WAREHOUSE = 8
        const val FRONT = 1; const val BACK = 2; const val LEFT = 4; const val RIGHT = 8
        val ALL = listOf(
            ConcertVenue("club", 10f..26f, 14f..36f, 4f..8f, 5f..10f, openness = 0f, directivity = 8f, speakerHeightM = 3f,
                wallMaterial = ConcertMaterial.TREATED, ceilingMaterial = ConcertMaterial.CONCRETE, floorMaterial = ConcertMaterial.CONCRETE, boomDb = 3f,
                frontFills = false),
            ConcertVenue("concert hall", 22f..38f, 30f..56f, 12f..20f, 10f..18f, openness = 0f, directivity = 5f, speakerHeightM = 6f,
                wallMaterial = ConcertMaterial.PANELLED, ceilingMaterial = ConcertMaterial.PLASTER, floorMaterial = ConcertMaterial.WOOD_FLOOR, boomDb = 2f,
                seatMaterial = ConcertMaterial.UPHOLSTERED_SEATS, stands = 8f..14f, standSides = BACK, tierBaseM = 5f, rakeDeg = 22f),
            ConcertVenue("arena", 70f..110f, 90f..140f, 25f..40f, 20f..34f, openness = 0f, directivity = 10f, speakerHeightM = 14f,
                wallMaterial = ConcertMaterial.CONCRETE, ceilingMaterial = ConcertMaterial.ARENA_ROOF, floorMaterial = ConcertMaterial.CONCRETE, boomDb = 5f,
                stands = 18f..32f, standSides = BACK or LEFT or RIGHT, tierBaseM = 4f, rakeDeg = 30f, subs = ConcertSubs.CARDIOID),
            // a 105 x 68 m pitch ringed by stands, centre-hung pa and delay towers for the far seats
            ConcertVenue("stadium", 140f..190f, 175f..225f, 40f..60f, 34f..56f, openness = 0.55f, directivity = 12f, speakerHeightM = 20f,
                wallMaterial = ConcertMaterial.CONCRETE, ceilingMaterial = ConcertMaterial.MEMBRANE, floorMaterial = ConcertMaterial.GRASS, boomDb = 5f,
                layout = ConcertLayout.CENTRE, stands = 36f..60f, standSides = FRONT or BACK or LEFT or RIGHT, tierBaseM = 3f, rakeDeg = 32f,
                delayTowers = true, subs = ConcertSubs.CENTRE),
            ConcertVenue("festival", 70f..220f, 90f..300f, 20f..20f, 24f..48f, openness = 1f, directivity = 10f, speakerHeightM = 12f,
                wallMaterial = ConcertMaterial.PLASTER, ceilingMaterial = ConcertMaterial.OPEN, floorMaterial = ConcertMaterial.GRASS, boomDb = 0f,
                delayTowers = true),
            ConcertVenue("amphitheatre", 50f..110f, 45f..95f, 15f..30f, 14f..26f, openness = 0.6f, directivity = 6f, speakerHeightM = 8f,
                wallMaterial = ConcertMaterial.STONE, ceilingMaterial = ConcertMaterial.PANELLED, floorMaterial = ConcertMaterial.STONE, boomDb = 2f,
                seatMaterial = ConcertMaterial.STONE_SEATS, stands = 30f..72f, standSides = BACK, tierBaseM = 0.5f, rakeDeg = 18f),
            ConcertVenue("basement", 6f..12f, 8f..16f, 2.6f..3.4f, 3f..5f, openness = 0f, directivity = 3f, speakerHeightM = 2f,
                wallMaterial = ConcertMaterial.BRICK, ceilingMaterial = ConcertMaterial.CONCRETE, floorMaterial = ConcertMaterial.CONCRETE, boomDb = 6f,
                subs = ConcertSubs.CENTRE, frontFills = false),
            ConcertVenue("church", 14f..30f, 35f..75f, 14f..32f, 6f..10f, openness = 0f, directivity = 3f, speakerHeightM = 5f,
                wallMaterial = ConcertMaterial.STONE, ceilingMaterial = ConcertMaterial.STONE, floorMaterial = ConcertMaterial.PEWS, boomDb = 2f,
                subs = ConcertSubs.CENTRE, frontFills = false),
            ConcertVenue("warehouse", 25f..50f, 40f..90f, 8f..14f, 12f..20f, openness = 0f, directivity = 8f, speakerHeightM = 7f,
                wallMaterial = ConcertMaterial.CONCRETE, ceilingMaterial = ConcertMaterial.METAL_ROOF, floorMaterial = ConcertMaterial.CONCRETE, boomDb = 6f),
        )
        val ORDER = listOf(BASEMENT, CLUB, THEATRE, CHURCH, WAREHOUSE, ARENA, STADIUM, FESTIVAL, AMPHITHEATRE)
    }
}

// how the band's rig is tuned: eq in dB (bass, low mids, presence, air), how hard it drives the room,
// the desk compressor (headroom above the running level before it squashes), how hard the pa is pushed and how loud the show runs;
// stageDb is the backline and drums against the pa five metres out (acoustic bands are the stage), subDb the sub array over flat,
// and the crowd's habits for this kind of show
class ConcertSound(
    val name: String,
    val bassDb: Float, val lowMidDb: Float, val presenceDb: Float, val airDb: Float,
    val directivity: Float, val spread: Float, val wash: Float, val decay: Float, val boom: Float,
    val ratio: Float, val headroomDb: Float, val releaseMs: Float, val makeupDb: Float,
    val drive: Float, val showDb: Float,
    val stageDb: Float = -10f, val subDb: Float = 2f, val acoustic: Boolean = false,
    val chatter: Float = 0.5f, val cheer: Float = 0.8f, val singAlong: Float = 0.5f, val roar: Float = 0.5f, val movement: Float = 0.4f,
) {
    companion object {
        const val POP = 0; const val ROCK = 1; const val METAL = 2; const val SHOEGAZE = 3
        const val JAZZ = 4; const val HIP_HOP = 5; const val ELECTRONIC = 6; const val CLASSICAL = 7
        const val INDIE = 8; const val PUNK = 9; const val POST_PUNK = 10; const val ACOUSTIC = 11
        val ALL = listOf(
            ConcertSound("pop", 1.5f, 0f, 1.5f, 1f, directivity = 1f, spread = 1f, wash = 1f, decay = 1f, boom = 1f,
                ratio = 2.5f, headroomDb = 4f, releaseMs = 120f, makeupDb = 1f, drive = 0.25f, showDb = 99f,
                stageDb = -14f, subDb = 3f, chatter = 0.5f, cheer = 1f, singAlong = 0.9f, roar = 0.5f, movement = 0.3f),
            ConcertSound("rock", 2f, 1f, 2f, 0.5f, directivity = 1f, spread = 1f, wash = 1.1f, decay = 1f, boom = 1.1f,
                ratio = 3f, headroomDb = 3f, releaseMs = 150f, makeupDb = 1.5f, drive = 0.6f, showDb = 103f,
                stageDb = -7f, subDb = 2f, chatter = 0.5f, cheer = 1f, singAlong = 0.6f, roar = 0.6f, movement = 0.5f),
            ConcertSound("metal", 3f, -2.5f, 3.5f, 1f, directivity = 1.1f, spread = 1.15f, wash = 1.25f, decay = 1f, boom = 1.3f,
                ratio = 5f, headroomDb = 1.5f, releaseMs = 100f, makeupDb = 2f, drive = 0.9f, showDb = 108f,
                stageDb = -5f, subDb = 3f, chatter = 0.3f, cheer = 1f, singAlong = 0.3f, roar = 1f, movement = 0.9f),
            ConcertSound("shoegaze", 3f, 2.5f, -3f, -4.5f, directivity = 0.8f, spread = 1.3f, wash = 1.5f, decay = 1.1f, boom = 1.3f,
                ratio = 3.5f, headroomDb = 2f, releaseMs = 300f, makeupDb = 1.5f, drive = 0.75f, showDb = 105f,
                stageDb = -6f, subDb = 2f, chatter = 0.25f, cheer = 0.5f, singAlong = 0.1f, roar = 0.2f, movement = 0.15f),
            ConcertSound("jazz", -1.5f, 1f, 0f, 0.5f, directivity = 0.4f, spread = 0.6f, wash = 0.9f, decay = 1f, boom = 0.4f,
                ratio = 1.6f, headroomDb = 8f, releaseMs = 200f, makeupDb = 0f, drive = 0.05f, showDb = 88f,
                stageDb = 2f, subDb = -6f, acoustic = true, chatter = 0.45f, cheer = 0.5f, singAlong = 0f, roar = 0f, movement = 0.05f),
            ConcertSound("hip-hop", 4f, -1.5f, 2.5f, 0f, directivity = 1.2f, spread = 0.7f, wash = 0.7f, decay = 0.9f, boom = 1.6f,
                ratio = 4f, headroomDb = 3f, releaseMs = 120f, makeupDb = 1.5f, drive = 0.45f, showDb = 104f,
                stageDb = -30f, subDb = 6f, chatter = 0.5f, cheer = 1f, singAlong = 0.8f, roar = 0.8f, movement = 0.7f),
            ConcertSound("electronic", 3f, -1f, 0.5f, 2.5f, directivity = 1.2f, spread = 1.25f, wash = 1f, decay = 1f, boom = 1.6f,
                ratio = 5f, headroomDb = 2f, releaseMs = 80f, makeupDb = 2f, drive = 0.4f, showDb = 104f,
                stageDb = -40f, subDb = 6f, chatter = 0.4f, cheer = 0.8f, singAlong = 0.2f, roar = 1f, movement = 0.6f),
            ConcertSound("classical", -2f, 0f, -0.5f, 0f, directivity = 0.3f, spread = 1.2f, wash = 1.15f, decay = 1.05f, boom = 0.3f,
                ratio = 1f, headroomDb = 12f, releaseMs = 200f, makeupDb = 0f, drive = 0f, showDb = 86f,
                stageDb = 30f, subDb = -20f, acoustic = true, chatter = 0.1f, cheer = 0.6f, singAlong = 0f, roar = 0f, movement = 0f),
            ConcertSound("indie", 1f, 1.5f, 1f, 0.5f, directivity = 0.9f, spread = 1.1f, wash = 1.2f, decay = 1.05f, boom = 1f,
                ratio = 2.5f, headroomDb = 4f, releaseMs = 150f, makeupDb = 1f, drive = 0.45f, showDb = 100f,
                stageDb = -8f, subDb = 1f, chatter = 0.5f, cheer = 0.8f, singAlong = 0.6f, roar = 0.3f, movement = 0.4f),
            ConcertSound("punk", 2f, 1.5f, 3f, 0f, directivity = 1f, spread = 0.9f, wash = 1.15f, decay = 0.95f, boom = 1.2f,
                ratio = 4f, headroomDb = 2f, releaseMs = 90f, makeupDb = 2f, drive = 0.8f, showDb = 107f,
                stageDb = -3f, subDb = 2f, chatter = 0.4f, cheer = 1f, singAlong = 0.7f, roar = 0.8f, movement = 1f),
            ConcertSound("post-punk", 1.5f, 0.5f, 2f, 1.5f, directivity = 0.9f, spread = 1.15f, wash = 1.4f, decay = 1.15f, boom = 1.1f,
                ratio = 3f, headroomDb = 3f, releaseMs = 180f, makeupDb = 1.5f, drive = 0.55f, showDb = 101f,
                stageDb = -7f, subDb = 2f, chatter = 0.4f, cheer = 0.7f, singAlong = 0.4f, roar = 0.4f, movement = 0.5f),
            ConcertSound("acoustic", -1f, 0.5f, 0.5f, 1f, directivity = 0.5f, spread = 0.8f, wash = 1f, decay = 1f, boom = 0.5f,
                ratio = 2f, headroomDb = 6f, releaseMs = 200f, makeupDb = 0.5f, drive = 0.05f, showDb = 90f,
                stageDb = 0f, subDb = -6f, acoustic = true, chatter = 0.3f, cheer = 0.7f, singAlong = 0.4f, roar = 0f, movement = 0.05f),
        )
        val ORDER = listOf(POP, ROCK, INDIE, PUNK, POST_PUNK, METAL, SHOEGAZE, HIP_HOP, ELECTRONIC, JAZZ, ACOUSTIC, CLASSICAL)
    }
}

object ConcertSeat {
    const val FRONT = 0; const val MIDDLE = 1; const val BACK = 2; const val SIDE = 3; const val STANDS = 4; const val UPPER = 5
    fun available(venue: Int): List<Int> =
        if (ConcertVenue.ALL[venue].standSides == 0) listOf(FRONT, MIDDLE, BACK, SIDE) else listOf(FRONT, MIDDLE, BACK, SIDE, STANDS, UPPER)
}

// positions are fractions of the venue: x across, y from the front wall to the back
data class ConcertPrefs(
    val enabled: Boolean = false,
    val venue: Int = ConcertVenue.ARENA,
    val sound: Int = ConcertSound.POP,
    val amount: Float = 1f,
    val size: Float = 0.5f,
    val openness: Float = 0f,
    val stageX: Float = 0.5f,
    val stageY: Float = 0.06f,
    val listenerX: Float = 0.5f,
    val listenerY: Float = 0.43f,
    // primitives on purpose, so a missing json key can never null them
    val showDb: Float = 100f,
    val crowd: Float = 0f,
    val earplugs: Int = ConcertEarplugs.NONE,
    val binaural: Boolean = true,
    val occupancy: Float = 0.8f,
    val temperatureC: Float = 20f,
    val humidity: Float = 50f,
    val stageDb: Float = 0f,
    val subLayout: Int = ConcertSubs.AUTO,
    // impulse-library id of a measured room, null for the modelled one
    val roomImpulse: String? = null,
) {
    fun clamped(): ConcertPrefs = copy(
        venue = venue.coerceIn(0, ConcertVenue.ALL.lastIndex), sound = sound.coerceIn(0, ConcertSound.ALL.lastIndex), amount = amount.unit(1f), size = size.unit(0.5f),
        openness = openness.unit(0f), stageX = stageX.unit(0.5f), stageY = stageY.unit(0.06f),
        listenerX = listenerX.unit(0.5f), listenerY = listenerY.unit(0.43f),
        showDb = (if (showDb.isFinite()) showDb else 100f).coerceIn(MIN_DB, MAX_DB), crowd = crowd.unit(0f), earplugs = earplugs.coerceIn(0, 2),
        occupancy = occupancy.unit(0.8f), temperatureC = (if (temperatureC.isFinite()) temperatureC else 20f).coerceIn(-10f, 40f),
        humidity = (if (humidity.isFinite()) humidity else 50f).coerceIn(5f, 100f), stageDb = (if (stageDb.isFinite()) stageDb else 0f).coerceIn(-12f, 12f),
        subLayout = subLayout.coerceIn(ConcertSubs.AUTO, ConcertSubs.CARDIOID), roomImpulse = roomImpulse?.takeIf { it.isNotBlank() },
    )

    fun subs(): Int = if (subLayout == ConcertSubs.AUTO) ConcertVenue.ALL[venue].subs else subLayout

    fun withVenue(id: Int): ConcertPrefs = ConcertVenue.ALL.getOrNull(id)?.let {
        copy(venue = id, size = 0.5f, openness = it.openness).withLayout(it.layout)
    } ?: this

    fun withSound(id: Int): ConcertPrefs = ConcertSound.ALL.getOrNull(id)?.let { copy(sound = id, showDb = it.showDb) } ?: this

    // end stage sits a few metres into the front of the floor, centre stage in the middle of it
    fun withLayout(layout: Int): ConcertPrefs {
        val v = ConcertVenue.ALL[venue]
        val f = v.floor(size)
        val x = (f[0] + f[2]) / 2
        val y = if (layout == ConcertLayout.CENTRE) (f[1] + f[3]) / 2 else f[1] + minOf(5f, 0.07f * v.lengthM(size)) / v.lengthM(size)
        return copy(stageX = x, stageY = y).withSeat(ConcertSeat.MIDDLE)
    }

    fun layout(): Int {
        val v = ConcertVenue.ALL[venue]
        val f = v.floor(size); val w = v.widthM(size); val l = v.lengthM(size)
        val off = hypot((stageX - (f[0] + f[2]) / 2) * w, (stageY - (f[1] + f[3]) / 2) * l)
        return if (off < 0.15f * minOf((f[2] - f[0]) * w, (f[3] - f[1]) * l)) ConcertLayout.CENTRE else ConcertLayout.END
    }

    // seats are measured along the line from the stage through the floor and up the stands behind it
    fun withSeat(seat: Int): ConcertPrefs {
        val v = ConcertVenue.ALL[venue]
        val w = v.widthM(size); val l = v.lengthM(size)
        val f = v.floor(size)
        val fl = f[0] * w; val ff = f[1] * l; val fr = f[2] * w; val fb = f[3] * l
        val sx = stageX * w; val sy = stageY * l
        val centred = layout() == ConcertLayout.CENTRE
        var ax = if (centred) 0f else (fl + fr) / 2 - sx
        var ay = if (centred) 1f else (ff + fb) / 2 - sy
        val len = hypot(ax, ay)
        if (len < 1e-3f) { ax = 0f; ay = 1f } else { ax /= len; ay /= len }
        val toFloorEdge = reach(sx, sy, ax, ay, fl, fr, ff, fb)
        val toWall = reach(sx, sy, ax, ay, 0f, w, 0f, l)
        val front = minOf(toFloorEdge * 0.3f, v.stageWidthM(size) * (if (centred) 0.35f else 0.15f) + 3f)
        val along = when (seat) {
            ConcertSeat.FRONT -> front
            ConcertSeat.BACK -> front + (toFloorEdge - front) * 0.95f
            ConcertSeat.SIDE -> front + (toFloorEdge - front) * 0.3f
            ConcertSeat.STANDS -> toFloorEdge + (toWall - toFloorEdge) * 0.25f
            ConcertSeat.UPPER -> toFloorEdge + (toWall - toFloorEdge) * 0.85f
            else -> front + (toFloorEdge - front) * 0.45f
        }
        var x = sx + ax * along; var y = sy + ay * along
        if (seat == ConcertSeat.SIDE) {
            val aside = reach(x, y, -ay, ax, fl, fr, ff, fb) * 0.85f
            x -= ay * aside; y += ax * aside
        }
        return copy(listenerX = (x / w).coerceIn(0.02f, 0.98f), listenerY = (y / l).coerceIn(0.02f, 0.98f))
    }

    fun seat(): Int? = ConcertSeat.available(venue).firstOrNull { s ->
        val at = withSeat(s)
        hypot(at.listenerX - listenerX, at.listenerY - listenerY) < 0.01f
    }

    private fun reach(x: Float, y: Float, dx: Float, dy: Float, x0: Float, x1: Float, y0: Float, y1: Float): Float {
        var t = Float.MAX_VALUE
        if (dx > 1e-6f) t = minOf(t, (x1 - x) / dx) else if (dx < -1e-6f) t = minOf(t, (x0 - x) / dx)
        if (dy > 1e-6f) t = minOf(t, (y1 - y) / dy) else if (dy < -1e-6f) t = minOf(t, (y0 - y) / dy)
        return if (t == Float.MAX_VALUE) 0f else t.coerceAtLeast(0f)
    }

    private fun Float.unit(fallback: Float) = (if (isFinite()) this else fallback).coerceIn(0f, 1f)

    companion object { const val MIN_DB = 80f; const val MAX_DB = 115f }
}

// one-tap setups: a venue, a band, a spot and how loud and busy it is
class ConcertScene(
    val venue: Int, val sound: Int, val size: Float, val seat: Int, val showDb: Float, val crowd: Float, private val layout: Int = -1,
    val occupancy: Float = 0.85f, val temperatureC: Float = 20f, val humidity: Float = 50f,
) {
    private fun layoutFor() = if (layout >= 0) layout else ConcertVenue.ALL[venue].layout

    fun applyTo(p: ConcertPrefs): ConcertPrefs =
        p.copy(venue = venue, sound = sound, size = size, openness = ConcertVenue.ALL[venue].openness, showDb = showDb, crowd = crowd,
            earplugs = ConcertEarplugs.NONE, occupancy = occupancy, temperatureC = temperatureC, humidity = humidity, stageDb = 0f,
            subLayout = ConcertSubs.AUTO, roomImpulse = null)
            .withLayout(layoutFor()).withSeat(seat)

    fun matches(p: ConcertPrefs) = p.venue == venue && p.sound == sound && abs(p.size - size) < 0.01f && abs(p.showDb - showDb) < 0.5f &&
        abs(p.crowd - crowd) < 0.01f && abs(p.occupancy - occupancy) < 0.01f && p.layout() == layoutFor() && p.seat() == seat

    companion object {
        val ALL = listOf(
            ConcertScene(ConcertVenue.BASEMENT, ConcertSound.PUNK, 0.4f, ConcertSeat.FRONT, 107f, 0.5f, occupancy = 1f),
            ConcertScene(ConcertVenue.CLUB, ConcertSound.ELECTRONIC, 0.6f, ConcertSeat.MIDDLE, 104f, 0.35f),
            ConcertScene(ConcertVenue.THEATRE, ConcertSound.SHOEGAZE, 0.1f, ConcertSeat.MIDDLE, 104f, 0.4f),
            ConcertScene(ConcertVenue.CLUB, ConcertSound.POST_PUNK, 0.45f, ConcertSeat.MIDDLE, 101f, 0.35f),
            ConcertScene(ConcertVenue.ARENA, ConcertSound.POP, 0.5f, ConcertSeat.MIDDLE, 101f, 0.35f),
            ConcertScene(ConcertVenue.STADIUM, ConcertSound.ROCK, 0.6f, ConcertSeat.MIDDLE, 104f, 0.45f, ConcertLayout.CENTRE, temperatureC = 18f, humidity = 60f),
            ConcertScene(ConcertVenue.STADIUM, ConcertSound.ROCK, 0.6f, ConcertSeat.UPPER, 103f, 0.45f, ConcertLayout.CENTRE, temperatureC = 18f, humidity = 60f),
            ConcertScene(ConcertVenue.FESTIVAL, ConcertSound.ELECTRONIC, 0.6f, ConcertSeat.MIDDLE, 104f, 0.4f, temperatureC = 16f, humidity = 75f),
            ConcertScene(ConcertVenue.WAREHOUSE, ConcertSound.METAL, 0.5f, ConcertSeat.FRONT, 109f, 0.45f),
            ConcertScene(ConcertVenue.CHURCH, ConcertSound.ACOUSTIC, 0.4f, ConcertSeat.MIDDLE, 86f, 0.08f, occupancy = 0.35f),
            ConcertScene(ConcertVenue.CLUB, ConcertSound.JAZZ, 0.1f, ConcertSeat.FRONT, 88f, 0.25f, occupancy = 0.6f),
            ConcertScene(ConcertVenue.AMPHITHEATRE, ConcertSound.INDIE, 0.5f, ConcertSeat.STANDS, 99f, 0.3f, temperatureC = 24f, humidity = 40f),
            ConcertScene(ConcertVenue.THEATRE, ConcertSound.CLASSICAL, 0.9f, ConcertSeat.STANDS, 86f, 0.05f),
        )
    }
}
