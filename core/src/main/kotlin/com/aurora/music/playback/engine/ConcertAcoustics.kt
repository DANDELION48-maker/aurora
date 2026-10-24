package com.aurora.music.playback.engine

import com.aurora.music.data.ConcertPrefs
import com.aurora.music.data.ConcertVenue
import com.aurora.music.data.ReverbPrefs
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

// direct gains map input channel to ear as [l->l, r->l, l->r, r->r]; taps are in seconds after the direct sound
class ConcertRoom(
    val late: ReverbPrefs,
    val lateGain: Double,
    val directGain: Double,
    val direct: DoubleArray,
    val airHz: Double,
    val boomDb: Double,
    val tapSec: DoubleArray,
    val tapLeft: DoubleArray,
    val tapRight: DoubleArray,
    val distanceM: Double,
)

// image-source early reflections, sabine critical distance for the direct-to-reverb ratio, line-array pa stacks
object ConcertAcoustics {
    const val MAX_TAP_SEC = 0.26
    private const val SPEED = 343.0
    private const val EAR = 1.7
    // floor, ceiling, left, right, front, back, back-left, back-right: 0 none, 1 low wall, 2 high wall
    private val MIRROR = arrayOf(intArrayOf(0, 0, 1), intArrayOf(0, 0, 2), intArrayOf(1, 0, 0), intArrayOf(2, 0, 0),
        intArrayOf(0, 1, 0), intArrayOf(0, 2, 0), intArrayOf(1, 2, 0), intArrayOf(2, 2, 0))

    fun room(prefs: ConcertPrefs): ConcertRoom {
        val c = prefs.clamped()
        val v = ConcertVenue.ALL[c.venue]
        val open = c.openness.toDouble()
        val w = v.widthM(c.size).toDouble(); val l = v.lengthM(c.size).toDouble(); val h = v.heightM(c.size).toDouble()
        val sx = c.stageX * w; val sy = c.stageY * l; val sz = min(v.speakerHeightM.toDouble(), h * 0.8)
        val px = c.listenerX * w; val py = c.listenerY * l; val pz = EAR + c.elevation * 0.45 * h

        // the stage faces the middle of the floor, a centre stage faces whoever listens
        val horizontal = max(hypot(px - sx, py - sy), 1e-6)
        val toListenerX = (px - sx) / horizontal; val toListenerY = (py - sy) / horizontal
        val centreX = w / 2 - sx; val centreY = l / 2 - sy; val centre = hypot(centreX, centreY)
        val facing = (centre / (0.2 * min(w, l))).coerceIn(0.0, 1.0)
        val (fx, fy) = unit(facing * (if (centre > 1e-9) centreX / centre else 0.0) + (1 - facing) * toListenerX,
            facing * (if (centre > 1e-9) centreY / centre else 1.0) + (1 - facing) * toListenerY, 0.0, 1.0)

        // listeners look between the stage and the way the crowd faces, so side seats hear it off-centre
        val crowd = max(0.0, (-toListenerX) * (-fx) + (-toListenerY) * (-fy))
        val (lx, ly) = unit(-toListenerX - fx * crowd, -toListenerY - fy * crowd, -toListenerX, -toListenerY)
        val rightX = -ly; val rightY = lx
        fun lateral(x: Double, y: Double, z: Double): Double {
            val dx = x - px; val dy = y - py; val d = sqrt(dx * dx + dy * dy + (z - pz) * (z - pz))
            val s = if (d < 1e-9) 0.0 else (dx * rightX + dy * rightY) / d
            return sign(s) * abs(s).pow(0.6) * 0.9
        }
        fun front(ex: Double, ey: Double): Double {
            val d = hypot(ex, ey)
            return if (d < 1e-9) 1.0 else ((1 + (ex * fx + ey * fy) / d) / 2).coerceIn(0.0, 1.0)
        }
        fun aim(ex: Double, ey: Double) = 0.35 + 0.65 * front(ex, ey).pow(1.5)

        val distance = sqrt((px - sx) * (px - sx) + (py - sy) * (py - sy) + (pz - sz) * (pz - sz)).coerceAtLeast(1.0)
        val aimed = aim(px - sx, py - sy)

        val half = v.stageWidthM(c.size) / 2.0
        val stackX = doubleArrayOf(sx - half * fy, sx + half * fy); val stackY = doubleArrayOf(sy + half * fx, sy - half * fx)
        val stackGain = DoubleArray(2) { k ->
            val d = sqrt((px - stackX[k]).pow(2) + (py - stackY[k]).pow(2) + (pz - sz).pow(2)).coerceAtLeast(1.0)
            1 / sqrt(d)
        }
        val stackNorm = sqrt(2 / (stackGain[0] * stackGain[0] + stackGain[1] * stackGain[1]))
        val direct = DoubleArray(4)
        for (k in 0..1) {
            val p = lateral(stackX[k], stackY[k], sz)
            direct[k] = stackGain[k] * stackNorm * cos((p + 1) * PI / 4)
            direct[2 + k] = stackGain[k] * stackNorm * sin((p + 1) * PI / 4)
        }

        val rt = (v.decaySec(c.size) * (1 - 0.7 * open)).coerceIn(0.2, 12.0)
        val volume = w * l * h
        val critical = 0.057 * sqrt(v.directivity * volume / rt)
        val lateEnergy = (distance / critical).pow(2)

        val wall = v.walls.toDouble()
        val reflect = doubleArrayOf(0.3, wall * (1 - open), wall * (1 - 0.85 * open), wall * (1 - 0.85 * open),
            0.5 * wall * (1 - 0.5 * open), wall * (1 - 0.7 * open), 0.0, 0.0)
        reflect[6] = reflect[2] * reflect[5]; reflect[7] = reflect[3] * reflect[5]
        val tapSec = DoubleArray(8); val tapGain = DoubleArray(8); val tapPan = DoubleArray(8)
        var firstWall = MAX_TAP_SEC
        for (t in 0 until 8) {
            val m = MIRROR[t]
            val ix = mirror(sx, w, m[0]); val iy = mirror(sy, l, m[1]); val iz = mirror(sz, h, m[2])
            val path = sqrt((px - ix).pow(2) + (py - iy).pow(2) + (pz - iz).pow(2))
            tapSec[t] = MAX_TAP_SEC * tanh((path - distance).coerceAtLeast(0.0) / SPEED / MAX_TAP_SEC)
            val ex = if (m[0] == 0) px - ix else ix - px; val ey = if (m[1] == 0) py - iy else iy - py
            tapGain[t] = reflect[t] * distance / max(path, distance) * aim(ex, ey)
            tapPan[t] = lateral(ix, iy, iz)
            if (t > 0) firstWall = min(firstWall, tapSec[t])
        }

        val total = aimed * aimed + tapGain.sumOf { it * it } + lateEnergy
        val norm = 1 / sqrt(total)
        for (k in 0..3) direct[k] *= aimed * norm
        val scale = ((cbrt(volume) - 10) / 80).coerceIn(0.0, 1.0)
        val late = ReverbPrefs(
            enabled = true, decaySec = rt.toFloat(), size = scale.toFloat(),
            predelayMs = (firstWall * 1000).coerceAtMost(200.0).toFloat(),
            damping = (0.3 + 0.4 * scale + 0.2 * open).toFloat(), lowCutHz = (35 + 100 * open).toFloat(),
            highCutHz = (12000 - 5000 * scale - 2500 * open).toFloat(), width = (1.25 - 0.35 * open).toFloat(),
            early = 0f, modulation = 0.3f,
        )
        return ConcertRoom(
            late = late,
            lateGain = sqrt(lateEnergy) * norm,
            directGain = aimed * norm,
            direct = direct,
            airHz = (50_000 / sqrt(distance * (1 + 0.5 * open)) * (0.35 + 0.65 * front(px - sx, py - sy))).coerceIn(2000.0, 24_000.0),
            boomDb = v.boomDb * (1 - open) * (0.5 + 0.5 * c.size),
            tapSec = tapSec,
            tapLeft = DoubleArray(8) { tapGain[it] * norm * cos((tapPan[it] + 1) * PI / 4) },
            tapRight = DoubleArray(8) { tapGain[it] * norm * sin((tapPan[it] + 1) * PI / 4) },
            distanceM = distance,
        )
    }

    private fun mirror(at: Double, extent: Double, side: Int) = when (side) { 1 -> -at; 2 -> 2 * extent - at; else -> at }

    private fun unit(x: Double, y: Double, fallbackX: Double, fallbackY: Double): Pair<Double, Double> {
        val d = hypot(x, y)
        return if (d < 1e-9) fallbackX to fallbackY else x / d to y / d
    }
}
