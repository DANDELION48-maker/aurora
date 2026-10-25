package com.aurora.music.playback.engine

import com.aurora.music.data.ConcertEarplugs
import com.aurora.music.data.ConcertLayout
import com.aurora.music.data.ConcertMaterial
import com.aurora.music.data.ConcertPrefs
import com.aurora.music.data.ConcertSound
import com.aurora.music.data.ConcertSubs
import com.aurora.music.data.ConcertVenue
import com.aurora.music.data.ReverbPrefs
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

// one sound reaching the listener: which signal, seconds after the earliest arrival, how loud, how much of the 3 khz and the
// 10 khz band is left (directivity, surfaces, air), and where it comes from in radians (right and up are positive)
class ConcertPath(val feed: Int, val delaySec: Double, val gain: Double, val mid: Double, val top: Double, val azimuth: Double, val elevation: Double)

// eq is bass, low mids, presence, air in dB (rig + the ear at show level + earplugs); decayBands are rt60 at 125 hz to 8 khz
class ConcertRoom(
    val late: ReverbPrefs,
    val lateGain: Double,
    val decayBands: DoubleArray,
    val directGain: Double,
    val paths: List<ConcertPath>,
    val taps: List<ConcertPath>,
    val pinnaElevation: Double,
    val binaural: Boolean,
    val boomDb: Double,
    val eqDb: DoubleArray,
    val drive: Double,
    val crowd: Double,
    val rig: ConcertSound,
    val compRatio: Double,
    val compHeadroomDb: Double,
    val compReleaseSec: Double,
    val compMakeupDb: Double,
    val distanceM: Double,
    val listenerHeightM: Double,
    val roomGain: Double = 0.0,
)

// geometric acoustics for one seat: line-array mains with real coverage, front fills, delay towers, a sub array that interferes,
// the stage's own sound, image sources up to third order off the venue's surfaces, and eyring decay per octave band
object ConcertAcoustics {
    const val PA_L = 0; const val PA_R = 1; const val SUB = 2; const val DRUMS = 3; const val AMP_L = 4; const val AMP_R = 5
    const val BASS = 6; const val RAW_L = 7; const val RAW_R = 8; const val FEEDS = 9
    const val MAX_TAPS = 24
    const val MAX_TAP_SEC = 0.3
    const val MAX_PATH_SEC = 0.22
    private const val SPEED = 343.0
    private const val EAR = 1.7

    fun room(prefs: ConcertPrefs): ConcertRoom {
        val c = prefs.clamped()
        val v = ConcertVenue.ALL[c.venue]
        val rig = ConcertSound.ALL[c.sound]
        val open = c.openness.toDouble()
        val occ = c.occupancy.toDouble()
        val w = v.widthM(c.size).toDouble(); val l = v.lengthM(c.size).toDouble(); val h = v.heightM(c.size).toDouble()
        val raised = v.tierHeightM(c.size, c.listenerX, c.listenerY).toDouble()
        val sx = c.stageX * w; val sy = c.stageY * l; val sz = min(v.speakerHeightM.toDouble(), h * 0.8)
        val px = c.listenerX * w; val py = c.listenerY * l; val pz = EAR + raised
        val centred = c.layout() == ConcertLayout.CENTRE

        // the stage faces the middle of the floor, a centre stage faces whoever listens
        val horizontal = max(hypot(px - sx, py - sy), 1e-6)
        val ux = (px - sx) / horizontal; val uy = (py - sy) / horizontal
        val centreX = w / 2 - sx; val centreY = l / 2 - sy; val centre = hypot(centreX, centreY)
        val facing = (centre / (0.2 * min(w, l))).coerceIn(0.0, 1.0)
        val (fx, fy) = unit(facing * (if (centre > 1e-9) centreX / centre else 0.0) + (1 - facing) * ux,
            facing * (if (centre > 1e-9) centreY / centre else 1.0) + (1 - facing) * uy, 0.0, 1.0)
        // listeners look between the stage and the way the crowd faces, so side seats hear it off-centre
        val crowdFacing = max(0.0, ux * fx + uy * fy)
        val (lx, ly) = unit(-ux - fx * crowdFacing, -uy - fy * crowdFacing, -ux, -uy)
        val rightX = -ly; val rightY = lx
        val perpX = fy; val perpY = -fx

        val air3k = AirAbsorption.dbPerMeter(3000.0, c.temperatureC.toDouble(), c.humidity.toDouble())
        val air10k = AirAbsorption.dbPerMeter(10_000.0, c.temperatureC.toDouble(), c.humidity.toDouble())
        fun airMid(d: Double) = 10.0.pow(-air3k * d / 20)
        fun airTop(d: Double) = 10.0.pow(-(air10k - air3k) * d / 20)
        fun dist(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double) =
            sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by) + (az - bz) * (az - bz)).coerceAtLeast(0.5)
        fun front(ex: Double, ey: Double, ax: Double, ay: Double): Double {
            val d = hypot(ex, ey)
            return if (d < 1e-9) 1.0 else ((1 + (ex * ax + ey * ay) / d) / 2).coerceIn(0.0, 1.0)
        }
        fun aim(ex: Double, ey: Double) = 0.35 + 0.65 * front(ex, ey, fx, fy).pow(1.5)
        // falls 6 dB for every `slope` degrees outside +-half of the aim
        fun cover(ex: Double, ey: Double, ax: Double, ay: Double, half: Double, slope: Double): Double {
            val d = hypot(ex, ey)
            if (d < 1e-9) return 1.0
            val deg = Math.toDegrees(acos(((ex * ax + ey * ay) / d).coerceIn(-1.0, 1.0)))
            return 10.0.pow(-max(0.0, deg - half) / slope * 6 / 20).coerceAtLeast(0.05)
        }
        fun lineLaw(d: Double) = sqrt(1 / (d * d) * sqrt(max(1.0, d / 10)))
        fun pointLaw(d: Double) = 1 / d
        fun azimuthOf(x: Double, y: Double) = atan2((x - px) * rightX + (y - py) * rightY, (x - px) * lx + (y - py) * ly)
        fun elevationOf(x: Double, y: Double, z: Double) = atan2(z - pz, hypot(x - px, y - py))

        val paths = ArrayList<MutablePath>()
        val half = v.stageWidthM(c.size) * rig.spread / 2.0
        // every box stays inside the walls, or its mirror images would arrive before it does
        fun inX(x: Double) = x.coerceIn(0.5, w - 0.5)
        fun inY(y: Double) = y.coerceIn(0.5, l - 0.5)
        val stackX = doubleArrayOf(inX(sx - half * perpX), inX(sx + half * perpX)); val stackY = doubleArrayOf(inY(sy - half * perpY), inY(sy + half * perpY))
        // mains level at any point, for lining up fills, towers, subs and the stage against them
        fun mainsAt(qx: Double, qy: Double, qz: Double): Double {
            var e = 0.0
            for (k in 0..1) { val g = aim(qx - stackX[k], qy - stackY[k]) * lineLaw(dist(qx, qy, qz, stackX[k], stackY[k], sz)); e += g * g }
            return sqrt(e)
        }
        fun mainsArrival(qx: Double, qy: Double, qz: Double) =
            min(dist(qx, qy, qz, stackX[0], stackY[0], sz), dist(qx, qy, qz, stackX[1], stackY[1], sz)) / SPEED

        for (k in 0..1) {
            val d = dist(px, py, pz, stackX[k], stackY[k], sz)
            val ex = px - stackX[k]; val ey = py - stackY[k]
            val dep = Math.toDegrees(atan2(sz - pz, hypot(ex, ey)))
            val vertical = when {
                dep > 40 -> 10.0.pow(-(dep - 40) / 10 * 6 / 20)
                dep < -15 -> 10.0.pow(-(-15 - dep) / 10 * 6 / 20)
                else -> 1.0
            }
            val hf = (if (centred) 1.0 else cover(ex, ey, fx, fy, 55.0, 15.0)) * vertical.coerceAtLeast(0.05)
            paths += MutablePath(if (k == 0) PA_L else PA_R, d / SPEED, aim(ex, ey) * lineLaw(d), hf * airMid(d), airTop(d),
                azimuthOf(stackX[k], stackY[k]), elevationOf(stackX[k], stackY[k], sz), MutablePath.MAINS)
        }

        if (v.frontFills && !centred) {
            val refX = sx + fx * 6; val refY = sy + fy * 6
            for (k in 0..1) {
                val fillX = inX(sx + fx + (if (k == 0) -1 else 1) * half * 0.5 * perpX)
                val fillY = inY(sy + fy + (if (k == 0) -1 else 1) * half * 0.5 * perpY)
                val level = 0.7 * mainsAt(refX, refY, EAR) / pointLaw(dist(refX, refY, EAR, fillX, fillY, 1.0))
                val align = mainsArrival(refX, refY, EAR) - dist(refX, refY, EAR, fillX, fillY, 1.0) / SPEED
                val d = dist(px, py, pz, fillX, fillY, 1.0)
                val ex = px - fillX; val ey = py - fillY
                val hf = cover(ex, ey, fx, fy, 50.0, 15.0) * (if (raised > 2) 0.3 else 1.0)
                paths += MutablePath(if (k == 0) PA_L else PA_R, d / SPEED + max(0.0, align), level * pointLaw(d) * front(ex, ey, fx, fy),
                    hf * airMid(d), airTop(d), azimuthOf(fillX, fillY), elevationOf(fillX, fillY, 1.0), MutablePath.MAINS)
            }
        }

        if (v.delayTowers) for (reach in doubleArrayOf(70.0, 140.0)) {
            val ramp = ((horizontal - (reach - 15)) / 20).coerceIn(0.0, 1.0)
            if (ramp <= 0) continue
            val refX = sx + ux * (reach + 25); val refY = sy + uy * (reach + 25)
            for (k in 0..1) {
                val side = if (k == 0) -12.0 else 12.0
                val tx = inX(sx + ux * reach + side * uy); val ty = inY(sy + uy * reach - side * ux)
                val level = mainsAt(refX, refY, EAR) / lineLaw(dist(refX, refY, EAR, tx, ty, 10.0))
                val d = dist(px, py, pz, tx, ty, 10.0)
                val ex = px - tx; val ey = py - ty
                val electronic = hypot(tx - sx, ty - sy) / SPEED + 0.010
                val feed = if ((tx - px) * rightX + (ty - py) * rightY < 0) PA_L else PA_R
                paths += MutablePath(feed, electronic + d / SPEED, ramp * level * lineLaw(d) * (0.3 + 0.7 * front(ex, ey, ux, uy)),
                    cover(ex, ey, ux, uy, 50.0, 15.0) * airMid(d), airTop(d), azimuthOf(tx, ty), elevationOf(tx, ty, 10.0), MutablePath.MAINS)
            }
        }

        // subs line up with the mains at the mixing desk, everywhere else they drift in and out of phase
        val subsLayout = c.subs()
        val subX: DoubleArray; val subY: DoubleArray
        if (subsLayout == ConcertSubs.LEFT_RIGHT) {
            subX = doubleArrayOf(inX(sx - (half + 2) * perpX), inX(sx + (half + 2) * perpX)); subY = doubleArrayOf(inY(sy - (half + 2) * perpY), inY(sy + (half + 2) * perpY))
        } else { subX = doubleArrayOf(inX(sx + fx * 0.5)); subY = doubleArrayOf(inY(sy + fy * 0.5)) }
        fun subPattern(ex: Double, ey: Double) = if (subsLayout == ConcertSubs.CARDIOID) 0.1 + 0.9 * front(ex, ey, fx, fy) else 1.0
        val toFloor = reachFloor(v, c.size, sx, sy, fx, fy, w, l)
        val desk = max(12.0, 0.6 * toFloor)
        val deskX = sx + fx * desk; val deskY = sy + fy * desk
        var subAtDesk = 0.0; var subArrival = Double.MAX_VALUE
        for (k in subX.indices) {
            val d = dist(deskX, deskY, EAR, subX[k], subY[k], 0.6)
            subAtDesk += pointLaw(d) * subPattern(deskX - subX[k], deskY - subY[k]); subArrival = min(subArrival, d / SPEED)
        }
        val subLevel = mainsAt(deskX, deskY, EAR) / subAtDesk * 10.0.pow(rig.subDb / 20.0)
        val subAlign = mainsArrival(deskX, deskY, EAR) - subArrival
        for (k in subX.indices) {
            val d = dist(px, py, pz, subX[k], subY[k], 0.6)
            paths += MutablePath(SUB, d / SPEED + subAlign, subLevel * pointLaw(d) * subPattern(px - subX[k], py - subY[k]), 1.0, 1.0,
                azimuthOf(subX[k], subY[k]), elevationOf(subX[k], subY[k], 0.6), MutablePath.SUBS)
        }

        // the stage itself: drums, guitar amps and the bass rig, or the whole band when nothing is amplified;
        // the bigger the room the harder the pa is run over it, so the backline fades in the mix
        val refX = sx + fx * 5; val refY = sy + fy * 5
        val stageGain = 10.0.pow((rig.stageDb + c.stageDb - 20 * log10(max(1.0, desk / 10))) / 20.0)
        fun stage(feed: Int, atX: Double, atY: Double, z: Double, weight: Double, beam: Double) {
            val x = inX(atX); val y = inY(atY)
            val level = weight * stageGain * mainsAt(refX, refY, EAR) / pointLaw(dist(refX, refY, EAR, x, y, z))
            val d = dist(px, py, pz, x, y, z)
            val ex = px - x; val ey = py - y
            paths += MutablePath(feed, d / SPEED, level * pointLaw(d) * (0.6 + 0.4 * front(ex, ey, fx, fy)),
                cover(ex, ey, fx, fy, beam, 12.0) * airMid(d), airTop(d), azimuthOf(x, y), elevationOf(x, y, z), MutablePath.STAGE)
        }
        if (rig.acoustic) {
            stage(RAW_L, sx - fx * 2 - half * 0.5 * perpX, sy - fy * 2 - half * 0.5 * perpY, 1.3, 0.7, 80.0)
            stage(RAW_R, sx - fx * 2 + half * 0.5 * perpX, sy - fy * 2 + half * 0.5 * perpY, 1.3, 0.7, 80.0)
        } else {
            stage(DRUMS, sx - fx * 3, sy - fy * 3, 1.2, 1.0, 90.0)
            stage(AMP_L, sx - fx * 1.5 - half * 0.6 * perpX, sy - fy * 1.5 - half * 0.6 * perpY, 0.8, 0.8, 30.0)
            stage(AMP_R, sx - fx * 1.5 + half * 0.6 * perpX, sy - fy * 1.5 + half * 0.6 * perpY, 0.8, 0.8, 30.0)
            stage(BASS, sx - fx * 2 + half * 0.3 * perpX, sy - fy * 2 + half * 0.3 * perpY, 0.6, 0.7, 90.0)
        }

        // image sources of both mains hangs up to third order; the strongest become taps, the rest feeds the tail
        val surfaces = surfaceReflection(v, c, occ, open)
        val candidates = ArrayList<MutablePath>()
        val mirrors = order3()
        for (k in 0..1) for (m in mirrors) {
            val ix = image(stackX[k], w, m[0]); val iy = image(stackY[k], l, m[1]); val iz = image(sz, h, m[2])
            var mid = 1.0; var hf = 1.0
            for (axis in 0..2) {
                val n = m[axis]
                val low = if (n < 0) (abs(n) + 1) / 2 else abs(n) / 2
                val high = abs(n) - low
                mid *= surfaces[axis * 4].pow(low) * surfaces[axis * 4 + 2].pow(high)
                hf *= surfaces[axis * 4 + 1].pow(low) * surfaces[axis * 4 + 3].pow(high)
            }
            if (mid < 1e-4) continue
            val path = dist(px, py, pz, ix, iy, iz)
            val ex = if (m[0] % 2 == 0) px - ix else ix - px; val ey = if (m[1] % 2 == 0) py - iy else iy - py
            val coverage = if (centred) 1.0 else cover(ex, ey, fx, fy, 55.0, 15.0)
            candidates += MutablePath(if (k == 0) PA_L else PA_R, path / SPEED, mid * aim(ex, ey) * lineLaw(path), hf / mid * coverage * airMid(path),
                airTop(path), azimuthOf(ix, iy), elevationOf(ix, iy, iz), MutablePath.TAP)
        }
        candidates.sortByDescending { it.gain }
        val taps = candidates.take(MAX_TAPS)
        val residual = candidates.drop(MAX_TAPS).sumOf { it.gain * it.gain }

        val bands = decayBands(v, c, w, l, h, occ, open, rig.decay.toDouble())
        val volume = w * l * h
        val rtMid = (bands[2] + bands[3] + bands[4]) / 3
        val critical = 0.057 * sqrt(v.directivity * rig.directivity * volume / rtMid)
        val lateEnergy = (rig.wash / critical).pow(2) + residual

        // at show level the ear hears more lows and highs than at home, and a pushed pa and desk squash harder
        val loud = (c.showDb - 80.0).coerceIn(0.0, 35.0)
        val plugs = when (c.earplugs) {
            ConcertEarplugs.MUSICIAN -> doubleArrayOf(0.0, 0.0, -1.0, -2.5)
            ConcertEarplugs.FOAM -> doubleArrayOf(4.0, 2.0, -4.0, -9.0)
            else -> DoubleArray(4)
        }
        val eq = doubleArrayOf(
            min(10.0, rig.bassDb + min(8.0, 0.3 * loud)) + plugs[0], rig.lowMidDb + plugs[1],
            rig.presenceDb + plugs[2], rig.airDb + min(1.5, 0.05 * loud) + plugs[3],
        )
        val drive = rig.drive * ((c.showDb - 88.0) / 20.0).coerceIn(0.0, 1.0)
        // rough loudness match so a bass-heavy rig isn't just louder
        val loudness = 10.0.pow(-(0.25 * eq[0] + 0.3 * eq[1] + 0.3 * eq[2] + 0.1 * eq[3]) / 20)
        // each path carries one channel and splits it over two ears, the tail reaches both ears whole
        val stageWeight = if (rig.acoustic) 1.0 else 0.3
        var split = taps.sumOf { it.gain * it.gain }
        var mains = 0.0
        for (p in paths) when (p.kind) {
            MutablePath.MAINS -> { split += p.gain * p.gain; if (p.feed == PA_L || p.feed == PA_R) mains += p.gain * p.gain }
            MutablePath.STAGE -> split += stageWeight * p.gain * p.gain
        }
        val total = lateEnergy + split / 2
        val norm = loudness / sqrt(total)

        val earliest = paths.minOf { it.delay }
        fun finish(p: MutablePath, cap: Double): ConcertPath {
            val t = max(0.0, p.delay - earliest)
            return ConcertPath(p.feed, if (t <= cap * 0.6) t else cap * 0.6 + cap * 0.4 * tanh((t - cap * 0.6) / (cap * 0.4)),
                p.gain * norm, p.mid.coerceIn(0.0, 2.0), p.top.coerceIn(0.0, 1.0), p.azimuth, p.elevation)
        }
        val strongest = paths.filter { it.kind == MutablePath.MAINS }.maxByOrNull { it.gain } ?: paths.first()
        val firstTap = taps.minOfOrNull { it.delay - earliest } ?: 0.02
        val scale = ((cbrt(volume) - 10) / 80).coerceIn(0.0, 1.0)
        val late = ReverbPrefs(
            enabled = true, decaySec = rtMid.toFloat(), size = scale.toFloat(),
            predelayMs = (firstTap * 1000).coerceIn(0.0, 200.0).toFloat(),
            damping = 0.4f, lowCutHz = (35 + 100 * open).toFloat(),
            highCutHz = (12000 - 5000 * scale - 2500 * open).toFloat(), width = (1.25 - 0.35 * open).toFloat(),
            early = 0f, modulation = 0.3f,
        )
        return ConcertRoom(
            late = late,
            lateGain = sqrt(lateEnergy) * norm,
            decayBands = bands,
            directGain = sqrt(mains) * norm,
            paths = paths.map { finish(it, MAX_PATH_SEC) },
            taps = taps.map { finish(it, MAX_TAP_SEC) },
            pinnaElevation = strongest.elevation,
            binaural = c.binaural,
            boomDb = v.boomDb * (1 - open) * (0.5 + 0.5 * c.size) * rig.boom,
            eqDb = eq,
            drive = drive,
            crowd = c.crowd.toDouble(),
            rig = rig,
            compRatio = rig.ratio.toDouble(),
            compHeadroomDb = (rig.headroomDb - 0.12 * (c.showDb - 95.0).coerceAtLeast(0.0)).coerceAtLeast(0.5),
            compReleaseSec = rig.releaseMs / 1000.0,
            compMakeupDb = rig.makeupDb.toDouble(),
            distanceM = hypot(horizontal, sz - pz),
            listenerHeightM = raised,
            roomGain = sqrt(taps.sumOf { it.gain * it.gain } / 2 + lateEnergy) * norm,
        )
    }

    // eyring with air absorption, per octave band from 125 hz to 8 khz; the crowd covers the floor and the stands
    fun decayBands(v: ConcertVenue, c: ConcertPrefs, w: Double, l: Double, h: Double, occ: Double, open: Double, stretch: Double = 1.0): DoubleArray {
        val f = v.floor(c.size)
        val floorArea = (f[2] - f[0]) * w * (f[3] - f[1]) * l
        val standsArea = (w * l - floorArea) / cos(Math.toRadians(v.rakeDeg.toDouble()))
        val ceilingArea = w * l
        val wallArea = 2 * (w + l) * h
        val volume = w * l * h
        val total = floorArea + standsArea + ceilingArea + wallArea
        return DoubleArray(ConcertMaterial.BANDS_HZ.size) { b ->
            fun mix(a: ConcertMaterial, z: ConcertMaterial, t: Double) = a.alpha[b] + (z.alpha[b] - a.alpha[b]) * t
            val sabins = floorArea * mix(v.floorMaterial, ConcertMaterial.AUDIENCE, occ) +
                standsArea * mix(v.seatMaterial, ConcertMaterial.AUDIENCE, occ) +
                ceilingArea * mix(v.ceilingMaterial, ConcertMaterial.OPEN, open) +
                wallArea * mix(v.wallMaterial, ConcertMaterial.OPEN, 0.85 * open)
            val mean = (sabins / total).coerceIn(1e-4, 0.95)
            val m = AirAbsorption.dbPerMeter(ConcertMaterial.BANDS_HZ[b], c.temperatureC.toDouble(), c.humidity.toDouble()) / 4.343
            (0.161 * volume / (-total * ln(1 - mean) + 4 * m * volume) * stretch).coerceIn(0.15, 12.0)
        }
    }

    // reflection factor of each surface at 1 khz and 4 khz: x low, x high, y low, y high, z low, z high
    private fun surfaceReflection(v: ConcertVenue, c: ConcertPrefs, occ: Double, open: Double): DoubleArray {
        fun side(bit: Int) = if (v.standSides and bit != 0) v.seatMaterial else v.wallMaterial
        fun beta(m: ConcertMaterial, band: Int, toAudience: Double, gone: Double): Double {
            val a = m.alpha[band] + (ConcertMaterial.AUDIENCE.alpha[band] - m.alpha[band]) * toAudience
            return sqrt(1 - a.coerceIn(0.0, 0.99)) * (1 - gone)
        }
        val walls = arrayOf(side(ConcertVenue.LEFT), side(ConcertVenue.RIGHT), side(ConcertVenue.FRONT), side(ConcertVenue.BACK))
        val out = DoubleArray(12)
        for (i in 0..3) {
            val crowded = if (v.standSides and intArrayOf(ConcertVenue.LEFT, ConcertVenue.RIGHT, ConcertVenue.FRONT, ConcertVenue.BACK)[i] != 0) occ else 0.0
            val gone = if (i == 2) 0.5 * open else 0.85 * open
            val axis = i / 2; val high = i % 2
            out[axis * 4 + high * 2] = beta(walls[i], 3, crowded, gone)
            out[axis * 4 + high * 2 + 1] = beta(walls[i], 5, crowded, gone)
        }
        out[8] = beta(v.floorMaterial, 3, occ, 0.0); out[9] = beta(v.floorMaterial, 5, occ, 0.0)
        out[10] = beta(v.ceilingMaterial, 3, 0.0, open); out[11] = beta(v.ceilingMaterial, 5, 0.0, open)
        return out
    }

    private fun order3(): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (x in -3..3) for (y in -3..3) for (z in -3..3) {
            val n = abs(x) + abs(y) + abs(z)
            if (n in 1..3) out += intArrayOf(x, y, z)
        }
        return out
    }

    // image coordinate after n reflections between the walls at 0 and extent
    private fun image(at: Double, extent: Double, n: Int) = if (n % 2 == 0) n * extent + at else (n + 1) * extent - at

    private fun reachFloor(v: ConcertVenue, size: Float, sx: Double, sy: Double, fx: Double, fy: Double, w: Double, l: Double): Double {
        val f = v.floor(size)
        var t = Double.MAX_VALUE
        if (fx > 1e-6) t = min(t, (f[2] * w - sx) / fx) else if (fx < -1e-6) t = min(t, (f[0] * w - sx) / fx)
        if (fy > 1e-6) t = min(t, (f[3] * l - sy) / fy) else if (fy < -1e-6) t = min(t, (f[1] * l - sy) / fy)
        return if (t == Double.MAX_VALUE) 20.0 else t.coerceAtLeast(5.0)
    }

    private fun unit(x: Double, y: Double, fallbackX: Double, fallbackY: Double): Pair<Double, Double> {
        val d = hypot(x, y)
        return if (d < 1e-9) fallbackX to fallbackY else x / d to y / d
    }

    private class MutablePath(val feed: Int, val delay: Double, val gain: Double, val mid: Double, val top: Double,
        val azimuth: Double, val elevation: Double, val kind: Int) {
        companion object { const val MAINS = 0; const val SUBS = 1; const val STAGE = 2; const val TAP = 3 }
    }
}
