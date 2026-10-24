package com.aurora.music.playback.engine

import com.aurora.music.data.ConcertEarplugs
import com.aurora.music.data.ConcertLayout
import com.aurora.music.data.ConcertPrefs
import com.aurora.music.data.ConcertScene
import com.aurora.music.data.ConcertSeat
import com.aurora.music.data.ConcertSound
import com.aurora.music.data.ConcertVenue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class ConcertAcousticsTest {
    private fun venue(id: Int, seat: Int = ConcertSeat.MIDDLE) = ConcertPrefs(enabled = true).withVenue(id).withSeat(seat)
    private fun ConcertRoom.drrDb() = 20 * log10(directGain / lateGain)

    @Test fun directSoundFadesIntoTheRoomWithDistance() {
        val seats = listOf(ConcertSeat.FRONT, ConcertSeat.MIDDLE, ConcertSeat.BACK).map { ConcertAcoustics.room(venue(ConcertVenue.ARENA, it)) }
        seats.forEach { println("arena %.0f m: drr %.1f dB, air %.0f Hz".format(it.distanceM, it.drrDb(), it.airHz)) }
        for (i in 1 until seats.size) {
            assertTrue(seats[i].distanceM > seats[i - 1].distanceM)
            assertTrue(seats[i].drrDb() < seats[i - 1].drrDb() - 3)
            assertTrue(seats[i].airHz < seats[i - 1].airHz)
        }
    }

    @Test fun biggerRoomsArriveLaterAndRingLonger() {
        val rooms = listOf(ConcertVenue.CLUB, ConcertVenue.THEATRE, ConcertVenue.ARENA).map { ConcertAcoustics.room(venue(it)) }
        for (i in 1 until rooms.size) {
            assertTrue(rooms[i].late.predelayMs > rooms[i - 1].late.predelayMs)
            assertTrue(rooms[i].late.decaySec > rooms[i - 1].late.decaySec)
            assertTrue(rooms[i].late.size > rooms[i - 1].late.size)
        }
        val small = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(size = 0f))
        val big = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(size = 1f))
        assertTrue(big.late.decaySec > small.late.decaySec && big.boomDb > small.boomDb)
    }

    @Test fun openingTheRoofCutsTheTailAndTheBoom() {
        val closed = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(openness = 0f))
        val open = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(openness = 1f))
        assertTrue(open.late.decaySec < closed.late.decaySec * 0.5)
        assertTrue(open.lateGain < closed.lateGain)
        assertTrue(open.airHz < closed.airHz)
        assertEquals(0.0, open.tapLeft[1] + open.tapRight[1], 0.0)
        assertEquals(0.0, open.boomDb, 0.0)
        assertTrue(closed.boomDb > 2)
    }

    @Test fun sideSeatsHearTheStageOffToOneSide() {
        val left = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(listenerX = 0.1f, listenerY = 0.3f))
        val right = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(listenerX = 0.9f, listenerY = 0.3f))
        val centre = ConcertAcoustics.room(venue(ConcertVenue.ARENA))
        fun ears(r: ConcertRoom) = (r.direct[0] + r.direct[1]) to (r.direct[2] + r.direct[3])
        assertTrue(ears(left).second > ears(left).first * 1.3)
        assertTrue(ears(right).first > ears(right).second * 1.3)
        assertEquals(ears(centre).first, ears(centre).second, 1e-9)
        val front = ConcertAcoustics.room(venue(ConcertVenue.ARENA, ConcertSeat.FRONT))
        val back = ConcertAcoustics.room(venue(ConcertVenue.ARENA, ConcertSeat.BACK))
        assertTrue(front.direct[0] / front.direct[2] > 2 * back.direct[0] / back.direct[2])
    }

    @Test fun eachGenreGetsItsOwnRig() {
        fun rig(sound: Int) = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(sound = sound))
        val rap = rig(ConcertSound.HIP_HOP); val jazz = rig(ConcertSound.JAZZ); val gaze = rig(ConcertSound.SHOEGAZE)
        val metal = rig(ConcertSound.METAL); val rock = rig(ConcertSound.ROCK)
        assertTrue(rap.eqDb[0] > jazz.eqDb[0] + 5 && rap.boomDb > jazz.boomDb)
        assertTrue(gaze.drrDb() < rock.drrDb() - 3 && gaze.late.decaySec > rock.late.decaySec)
        assertTrue(jazz.drrDb() < rap.drrDb() - 6)
        assertTrue(metal.eqDb[1] < 0 && metal.eqDb[2] > rock.eqDb[2])
        ConcertSound.ALL.indices.map(::rig).forEachIndexed { id, r -> println("sound $id: drr %.1f dB, eq %s".format(r.drrDb(), r.eqDb.joinToString())) }
    }

    @Test fun theStandsLiftYouUpAndThePitchDoesNot() {
        val stadium = ConcertPrefs(enabled = true).withVenue(ConcertVenue.STADIUM)
        assertEquals(ConcertLayout.CENTRE, stadium.layout())
        val pitch = ConcertAcoustics.room(stadium.withSeat(ConcertSeat.MIDDLE))
        val lower = ConcertAcoustics.room(stadium.withSeat(ConcertSeat.STANDS))
        val upper = ConcertAcoustics.room(stadium.withSeat(ConcertSeat.UPPER))
        println("stadium pitch %.0f m up %.1f, stands %.0f m up %.1f, upper %.0f m up %.1f".format(
            pitch.distanceM, pitch.listenerHeightM, lower.distanceM, lower.listenerHeightM, upper.distanceM, upper.listenerHeightM))
        assertEquals(0.0, pitch.listenerHeightM, 0.0)
        assertTrue(lower.listenerHeightM > 3 && upper.listenerHeightM > lower.listenerHeightM + 10)
        assertTrue(upper.distanceM > lower.distanceM && lower.distanceM > pitch.distanceM)
    }

    @Test fun aCentreStageSoundsTheSameFromEverySide() {
        val v = ConcertVenue.ALL[ConcertVenue.STADIUM]
        val base = ConcertPrefs(enabled = true, venue = ConcertVenue.STADIUM, size = 0.5f, stageX = 0.5f, stageY = 0.5f)
        val north = ConcertAcoustics.room(base.copy(listenerX = 0.5f, listenerY = 0.5f - 30f / v.lengthM(0.5f)))
        val east = ConcertAcoustics.room(base.copy(listenerX = 0.5f + 30f / v.widthM(0.5f), listenerY = 0.5f))
        assertEquals(north.drrDb(), east.drrDb(), 0.75)
        assertEquals(north.direct[0] + north.direct[1], north.direct[2] + north.direct[3], 1e-6)
        assertEquals(east.direct[0] + east.direct[1], east.direct[2] + east.direct[3], 1e-6)
    }

    @Test fun aLouderShowDistortsAndSquashesMore() {
        val quiet = ConcertAcoustics.room(venue(ConcertVenue.CLUB).withSound(ConcertSound.METAL).copy(showDb = 92f))
        val loud = ConcertAcoustics.room(venue(ConcertVenue.CLUB).withSound(ConcertSound.METAL).copy(showDb = 112f))
        assertTrue(loud.drive > quiet.drive + 0.4)
        assertTrue(loud.eqDb[0] > quiet.eqDb[0] + 3 && loud.eqDb[3] > quiet.eqDb[3])
        assertTrue(loud.compHeadroomDb < quiet.compHeadroomDb)
        val jazz = ConcertAcoustics.room(venue(ConcertVenue.CLUB).withSound(ConcertSound.JAZZ))
        assertTrue(jazz.drive < 0.01)
    }

    @Test fun foamEarplugsTakeTheTopOff() {
        val open = ConcertAcoustics.room(venue(ConcertVenue.ARENA))
        val foam = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(earplugs = ConcertEarplugs.FOAM))
        val musician = ConcertAcoustics.room(venue(ConcertVenue.ARENA).copy(earplugs = ConcertEarplugs.MUSICIAN))
        assertTrue(foam.eqDb[3] < open.eqDb[3] - 10 && musician.eqDb[3] < open.eqDb[3] && musician.eqDb[3] > foam.eqDb[3])
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
            val r = ConcertAcoustics.room(ConcertPrefs(enabled = true, venue = id, stageX = sx, stageY = sy, listenerX = x, listenerY = y, showDb = 80f + 35f * y))
            val all = r.direct + r.tapSec + r.tapLeft + r.tapRight + r.eqDb + doubleArrayOf(r.lateGain, r.directGain, r.airHz, r.boomDb, r.drive, r.listenerHeightM)
            assertTrue("venue $id stage $sx,$sy at $x,$y", all.all { it.isFinite() })
            assertTrue(r.tapSec.all { it in 0.0..ConcertAcoustics.MAX_TAP_SEC } && r.late.predelayMs in 0f..200f)
            assertTrue(r.directGain > 0.05)
        }
    }
}
