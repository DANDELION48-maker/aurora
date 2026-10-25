package com.aurora.music.playback.engine

import com.aurora.music.data.ConcertEarplugs
import com.aurora.music.data.ConcertLayout
import com.aurora.music.data.ConcertPrefs
import com.aurora.music.data.ConcertScene
import com.aurora.music.data.ConcertSeat
import com.aurora.music.data.ConcertSound
import com.aurora.music.data.ConcertSubs
import com.aurora.music.data.ConcertVenue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class ConcertAcousticsTest {
    private fun venue(id: Int, seat: Int = ConcertSeat.MIDDLE) = ConcertPrefs(enabled = true).withVenue(id).withSeat(seat)
    private fun ConcertRoom.drrDb() = 20 * log10(directGain / lateGain)
    private fun ConcertRoom.mains() = paths.filter { it.feed == ConcertAcoustics.PA_L || it.feed == ConcertAcoustics.PA_R }
    private fun ConcertRoom.of(vararg feeds: Int) = paths.filter { it.feed in feeds }
    private fun energy(list: List<ConcertPath>) = list.sumOf { it.gain * it.gain }
    private fun ConcertRoom.stacks() = mains().sortedByDescending { it.gain }.take(2).sortedBy { it.feed }

    @Test fun airAbsorptionFollowsIso9613() {
        fun km(hz: Double, t: Double = 20.0, rh: Double = 50.0) = AirAbsorption.dbPerMeter(hz, t, rh) * 1000
        println("air at 20 C / 50%%: 1k %.1f, 4k %.1f, 8k %.1f dB/km".format(km(1000.0), km(4000.0), km(8000.0)))
        assertTrue(km(1000.0) in 3.0..7.0)
        assertTrue(km(4000.0) in 18.0..40.0)
        assertTrue(km(8000.0) in 60.0..140.0)
        assertTrue(abs(km(8000.0, 12.0, 80.0) - km(8000.0, 30.0, 30.0)) > 0.1 * km(8000.0))
    }

    @Test fun roomsRingLikeRealOnes() {
        fun mid(r: ConcertRoom) = (r.decayBands[2] + r.decayBands[3] + r.decayBands[4]) / 3
        val rooms = ConcertVenue.ALL.indices.associateWith { ConcertAcoustics.room(venue(it)) }
        rooms.forEach { (id, r) -> println("${ConcertVenue.ALL[id].name}: rt60 ${r.decayBands.joinToString { "%.2f".format(it) }}") }
        assertTrue(mid(rooms.getValue(ConcertVenue.BASEMENT)) in 0.3..0.9)
        assertTrue(mid(rooms.getValue(ConcertVenue.CLUB)) in 0.4..1.2)
        assertTrue(mid(rooms.getValue(ConcertVenue.WAREHOUSE)) in 1.2..3.5)
        assertTrue(mid(rooms.getValue(ConcertVenue.ARENA)) in 1.5..4.0)
        assertTrue(mid(rooms.getValue(ConcertVenue.CHURCH)) in 3.0..9.0)
        assertTrue(mid(rooms.getValue(ConcertVenue.FESTIVAL)) < 0.5)
        val smallHall = ConcertAcoustics.room(venue(ConcertVenue.THEATRE).copy(size = 0.1f, occupancy = 0.9f))
        assertTrue(mid(smallHall) in 0.7..1.6)
        val empty = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(occupancy = 0f))
        val packed = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(occupancy = 1f))
        assertTrue(mid(empty) > mid(packed) * 1.3)
        assertTrue(packed.decayBands[6] < mid(packed))
    }

    @Test fun directSoundFadesIntoTheRoomWithDistance() {
        val seats = listOf(ConcertSeat.FRONT, ConcertSeat.MIDDLE, ConcertSeat.BACK).map { ConcertAcoustics.room(venue(ConcertVenue.ARENA, it)) }
        seats.forEach { println("arena %.0f m: drr %.1f dB, top %.2f".format(it.distanceM, it.drrDb(), it.stacks()[0].top)) }
        for (i in 1 until seats.size) {
            assertTrue(seats[i].distanceM > seats[i - 1].distanceM)
            assertTrue(seats[i].drrDb() < seats[i - 1].drrDb() - 2)
            assertTrue(seats[i].stacks()[0].top < seats[i - 1].stacks()[0].top)
        }
    }

    @Test fun humidAndColdAirEatsTheTopDifferently() {
        val far = venue(ConcertVenue.FESTIVAL, ConcertSeat.BACK)
        val cold = ConcertAcoustics.room(far.copy(temperatureC = 12f, humidity = 80f)).stacks()[0].top
        val hot = ConcertAcoustics.room(far.copy(temperatureC = 30f, humidity = 30f)).stacks()[0].top
        println("festival back: top band left %.3f in cold damp air, %.3f in hot dry air".format(cold, hot))
        assertTrue(abs(cold - hot) > 0.02)
    }

    @Test fun openingTheRoofCutsTheTailAndTheBoom() {
        val closed = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(openness = 0f))
        val open = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(openness = 1f))
        assertTrue(open.late.decaySec < closed.late.decaySec * 0.5)
        assertTrue(open.lateGain < closed.lateGain)
        assertEquals(0.0, open.boomDb, 0.0)
        assertTrue(closed.boomDb > 2)
    }

    @Test fun sideSeatsHearTheStageOffToOneSide() {
        fun lean(r: ConcertRoom) = r.mains().sumOf { it.gain * it.gain * sin(it.azimuth) } / energy(r.mains())
        val left = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(listenerX = 0.25f, listenerY = 0.3f))
        val right = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(listenerX = 0.75f, listenerY = 0.3f))
        val centre = ConcertAcoustics.room(venue(ConcertVenue.ARENA))
        assertTrue(lean(left) > 0.05 && lean(right) < -0.05)
        val (l, r) = centre.stacks()
        assertEquals(-l.azimuth, r.azimuth, 1e-6)
        val front = ConcertAcoustics.room(venue(ConcertVenue.ARENA, ConcertSeat.FRONT)).stacks()
        val back = ConcertAcoustics.room(venue(ConcertVenue.ARENA, ConcertSeat.BACK)).stacks()
        assertTrue(abs(front[0].azimuth - front[1].azimuth) > 2 * abs(back[0].azimuth - back[1].azimuth))
    }

    @Test fun subsInterfereLikeRealArrays() {
        val split = venue(ConcertVenue.FESTIVAL).copy(subLayout = ConcertSubs.LEFT_RIGHT)
        val onAxis = ConcertAcoustics.room(split).of(ConcertAcoustics.SUB)
        val offAxis = ConcertAcoustics.room(split.copy(listenerX = 0.3f)).of(ConcertAcoustics.SUB)
        assertEquals(2, onAxis.size)
        assertEquals(onAxis[0].delaySec, onAxis[1].delaySec, 1e-6)
        assertTrue(abs(offAxis[0].delaySec - offAxis[1].delaySec) > 0.002)
        val cardioid = venue(ConcertVenue.ARENA).copy(subLayout = ConcertSubs.CARDIOID)
        val ahead = ConcertAcoustics.room(cardioid).of(ConcertAcoustics.SUB).single()
        val behind = ConcertAcoustics.room(cardioid.copy(listenerY = 0.01f)).of(ConcertAcoustics.SUB).single()
        val omniBehind = ConcertAcoustics.room(cardioid.copy(listenerY = 0.01f, subLayout = ConcertSubs.CENTRE)).of(ConcertAcoustics.SUB).single()
        assertTrue(behind.gain < omniBehind.gain * 0.5 && ahead.gain > behind.gain)
    }

    @Test fun fillsCoverTheFrontAndTowersTheBack() {
        val front = ConcertAcoustics.room(venue(ConcertVenue.ARENA, ConcertSeat.FRONT))
        assertTrue(front.mains().size == 4)
        val field = venue(ConcertVenue.FESTIVAL).copy(size = 1f)
        val far = ConcertAcoustics.room(field.copy(listenerY = 0.6f))
        val near = ConcertAcoustics.room(field.copy(listenerY = 0.12f))
        assertTrue(far.mains().size > near.mains().size)
        val tower = far.mains().filter { it.delaySec > 0.005 }.maxByOrNull { it.gain }
        assertTrue(tower != null && tower.gain > 0.3 * far.stacks()[0].gain)
    }

    @Test fun smallRoomsHearTheStageAndStadiumsThePa() {
        fun stageDb(r: ConcertRoom) = 10 * log10(energy(r.of(ConcertAcoustics.DRUMS, ConcertAcoustics.AMP_L, ConcertAcoustics.AMP_R)) / energy(r.mains()))
        val basement = ConcertAcoustics.room(venue(ConcertVenue.BASEMENT, ConcertSeat.FRONT).withSound(ConcertSound.PUNK))
        val stadium = ConcertAcoustics.room(venue(ConcertVenue.STADIUM).withSound(ConcertSound.PUNK))
        println("stage against pa: basement %.1f dB, stadium %.1f dB".format(stageDb(basement), stageDb(stadium)))
        assertTrue(stageDb(basement) > -6 && stageDb(stadium) < -15)
        val orchestra = ConcertAcoustics.room(venue(ConcertVenue.THEATRE).withSound(ConcertSound.CLASSICAL))
        assertTrue(energy(orchestra.of(ConcertAcoustics.RAW_L, ConcertAcoustics.RAW_R)) > 10 * energy(orchestra.mains()))
    }

    @Test fun reflectionsComeOffTheSurfaces() {
        val r = ConcertAcoustics.room(venue(ConcertVenue.CLUB).copy(listenerX = 0.08f))
        assertEquals(ConcertAcoustics.MAX_TAPS, r.taps.size)
        assertTrue(r.taps.zipWithNext().all { (a, b) -> a.gain >= b.gain })
        assertTrue(r.taps.all { it.delaySec > 0 && it.delaySec <= ConcertAcoustics.MAX_TAP_SEC })
        // next to the left wall the reflections pull further left than the stage itself
        fun lean(list: List<ConcertPath>) = list.sumOf { it.gain * it.gain * sin(it.azimuth) } / energy(list)
        assertTrue(lean(r.taps) < lean(r.mains()))
    }

    @Test fun headphonesPutASourceWhereItIs() {
        fun arrivals(binaural: Boolean): DoubleArray {
            val path = ConcertPath(ConcertAcoustics.PA_L, 0.0, 1.0, 1.0, 1.0, -Math.PI / 3, 0.0)
            val room = ConcertRoom(com.aurora.music.data.ReverbPrefs(), 0.0, DoubleArray(7) { 1.0 }, 1.0, listOf(path), emptyList(), 0.0, binaural,
                0.0, DoubleArray(4), 0.0, 0.0, ConcertSound.ALL[0], 1.0, 12.0, 0.1, 0.0, 10.0, 0.0)
            val r = ConcertRenderer().apply { configure(48_000); setRoom(room, snap = true) }
            val left = DoubleArray(400); val right = DoubleArray(400)
            for (i in 0 until 400) { r.render(if (i == 10) 1.0 else 0.0, 0.0, 0.0, 0.0); left[i] = r.left; right[i] = r.right }
            fun first(x: DoubleArray) = x.indices.first { abs(x[it]) > x.maxOf { v -> abs(v) } * 0.3 }
            return doubleArrayOf(first(left).toDouble(), first(right).toDouble(), left.sumOf { it * it }, right.sumOf { it * it })
        }
        val ears = arrivals(true)
        val speakers = arrivals(false)
        println("source 60 degrees left: left ear sample %.0f, right ear %.0f; speakers %.0f / %.0f".format(ears[0], ears[1], speakers[0], speakers[1]))
        assertTrue(ears[1] - ears[0] in 15.0..30.0)
        assertTrue(ears[2] > ears[3] && speakers[2] > speakers[3])
        assertEquals(speakers[0], speakers[1], 0.0)
    }

    @Test fun eachGenreGetsItsOwnRig() {
        fun rig(sound: Int) = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(sound = sound))
        val rap = rig(ConcertSound.HIP_HOP); val jazz = rig(ConcertSound.JAZZ); val gaze = rig(ConcertSound.SHOEGAZE)
        val metal = rig(ConcertSound.METAL); val rock = rig(ConcertSound.ROCK)
        assertTrue(rap.eqDb[0] > jazz.eqDb[0] + 4 && rap.boomDb > jazz.boomDb)
        assertTrue(gaze.drrDb() < rock.drrDb() && gaze.late.decaySec > rock.late.decaySec)
        assertTrue(metal.eqDb[1] < 0 && metal.eqDb[2] > rock.eqDb[2])
    }

    @Test fun theStandsLiftYouUpAndThePitchDoesNot() {
        val stadium = ConcertPrefs(enabled = true).withVenue(ConcertVenue.STADIUM)
        assertEquals(ConcertLayout.CENTRE, stadium.layout())
        val pitch = ConcertAcoustics.room(stadium.withSeat(ConcertSeat.MIDDLE))
        val lower = ConcertAcoustics.room(stadium.withSeat(ConcertSeat.STANDS))
        val upper = ConcertAcoustics.room(stadium.withSeat(ConcertSeat.UPPER))
        assertEquals(0.0, pitch.listenerHeightM, 0.0)
        assertTrue(lower.listenerHeightM > 3 && upper.listenerHeightM > lower.listenerHeightM + 10)
        assertTrue(upper.distanceM > lower.distanceM && lower.distanceM > pitch.distanceM)
        assertTrue(upper.pinnaElevation < pitch.pinnaElevation)
    }

    @Test fun aCentreStageSoundsTheSameFromEverySide() {
        val v = ConcertVenue.ALL[ConcertVenue.STADIUM]
        val base = ConcertPrefs(enabled = true, venue = ConcertVenue.STADIUM, size = 0.5f, stageX = 0.5f, stageY = 0.5f)
        val north = ConcertAcoustics.room(base.copy(listenerX = 0.5f, listenerY = 0.5f - 30f / v.lengthM(0.5f)))
        val east = ConcertAcoustics.room(base.copy(listenerX = 0.5f + 30f / v.widthM(0.5f), listenerY = 0.5f))
        assertEquals(north.drrDb(), east.drrDb(), 0.75)
        for (r in listOf(north, east)) { val (l, rr) = r.stacks(); assertEquals(-l.azimuth, rr.azimuth, 1e-6) }
    }

    @Test fun aLouderShowDistortsAndSquashesMore() {
        val quiet = ConcertAcoustics.room(venue(ConcertVenue.CLUB).withSound(ConcertSound.METAL).copy(showDb = 92f))
        val loud = ConcertAcoustics.room(venue(ConcertVenue.CLUB).withSound(ConcertSound.METAL).copy(showDb = 112f))
        assertTrue(loud.drive > quiet.drive + 0.4)
        assertTrue(loud.eqDb[0] > quiet.eqDb[0] + 3 && loud.eqDb[3] >= quiet.eqDb[3])
        assertTrue(loud.compHeadroomDb < quiet.compHeadroomDb)
        assertTrue(ConcertAcoustics.room(venue(ConcertVenue.CLUB).withSound(ConcertSound.JAZZ)).drive < 0.01)
    }

    @Test fun foamEarplugsTakeTheTopOff() {
        val open = ConcertAcoustics.room(venue(ConcertVenue.ARENA))
        val foam = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(earplugs = ConcertEarplugs.FOAM))
        val musician = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(earplugs = ConcertEarplugs.MUSICIAN))
        assertTrue(foam.eqDb[3] < open.eqDb[3] - 8 && musician.eqDb[3] < open.eqDb[3] && musician.eqDb[3] > foam.eqDb[3])
    }

    @Test fun everySeatAndSceneLandsWhereItSays() {
        for (id in ConcertVenue.ALL.indices) for (layout in listOf(ConcertLayout.END, ConcertLayout.CENTRE)) for (size in listOf(0f, 0.5f, 1f)) {
            val p = ConcertPrefs(enabled = true).withVenue(id).copy(size = size).withLayout(layout)
            assertEquals("venue $id layout $layout", layout, p.layout())
            for (seat in ConcertSeat.available(id)) {
                val at = p.withSeat(seat)
                assertEquals("venue $id layout $layout size $size seat $seat", seat, at.seat())
                val raised = ConcertVenue.ALL[id].tierHeightM(size, at.listenerX, at.listenerY)
                if (seat >= ConcertSeat.STANDS) assertTrue("venue $id seat $seat in the stands", raised > 0f)
                else if (seat != ConcertSeat.SIDE) assertEquals("venue $id seat $seat on the floor", 0f, raised, 0f)
            }
        }
        ConcertScene.ALL.forEachIndexed { i, scene ->
            val p = scene.applyTo(ConcertPrefs(enabled = true))
            assertTrue("scene $i", scene.matches(p) && ConcertScene.ALL.indexOfFirst { it.matches(p) } == i)
        }
    }

    @Test fun everyVenueAndPositionGivesUsableNumbers() {
        val spots = listOf(0f, 0.5f, 1f)
        for (id in ConcertVenue.ALL.indices) for (sx in spots) for (sy in spots) for (x in spots) for (y in spots) {
            val r = ConcertAcoustics.room(ConcertPrefs(enabled = true, venue = id, stageX = sx, stageY = sy, listenerX = x, listenerY = y,
                showDb = 80f + 35f * y, occupancy = x, subLayout = (id % 3) + 1))
            val numbers = (r.paths + r.taps).flatMap { listOf(it.delaySec, it.gain, it.mid, it.top, it.azimuth, it.elevation) } +
                r.decayBands.toList() + r.eqDb.toList() + listOf(r.lateGain, r.directGain, r.boomDb, r.drive, r.listenerHeightM, r.pinnaElevation)
            assertTrue("venue $id stage $sx,$sy at $x,$y", numbers.all { it.isFinite() })
            assertTrue("venue $id stage $sx,$sy at $x,$y", r.paths.all { it.delaySec in 0.0..ConcertAcoustics.MAX_PATH_SEC } && r.taps.all { it.delaySec in 0.0..ConcertAcoustics.MAX_TAP_SEC })
            assertTrue(r.late.predelayMs in 0f..200f && r.directGain > 0.01)
        }
    }
}
