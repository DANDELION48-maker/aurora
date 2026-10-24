package com.aurora.music.playback.engine

import com.aurora.music.data.ConcertPrefs
import com.aurora.music.data.ConcertSeat
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

    @Test fun everyVenueAndPositionGivesUsableNumbers() {
        val spots = listOf(0f, 0.5f, 1f)
        for (id in ConcertVenue.ALL.indices) for (sx in spots) for (sy in spots) for (x in spots) for (y in spots) {
            val r = ConcertAcoustics.room(ConcertPrefs(enabled = true, venue = id, stageX = sx, stageY = sy, listenerX = x, listenerY = y, elevation = y))
            val all = r.direct + r.tapSec + r.tapLeft + r.tapRight + doubleArrayOf(r.lateGain, r.directGain, r.airHz, r.boomDb)
            assertTrue("venue $id stage $sx,$sy at $x,$y", all.all { it.isFinite() })
            assertTrue(r.tapSec.all { it in 0.0..ConcertAcoustics.MAX_TAP_SEC } && r.late.predelayMs in 0f..200f)
            assertTrue(r.directGain > 0.05)
        }
    }
}
