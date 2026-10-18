package com.aurora.music.desktop.platform

import com.aurora.music.data.DetailedPlay
import com.aurora.music.data.JsonLinesDetailedListeningStore
import com.aurora.music.data.Place
import com.aurora.music.data.PlaceZones
import com.aurora.music.data.PlayContext
import com.aurora.music.data.PlayedTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlacesStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun parsesWindowsLocationOutput() {
        val found = WindowsLocation.parse("noise\r\nFIX 51.5072 -0.1276 35\r\n") as LocationResult.Found
        assertEquals(51.5072, found.fix.latitude, 1e-9)
        assertEquals(-0.1276, found.fix.longitude, 1e-9)
        assertEquals(35f, found.fix.accuracyM)
        assertEquals(LocationResult.Denied, WindowsLocation.parse("DENIED"))
        assertEquals(LocationResult.Unavailable, WindowsLocation.parse("NONE"))
        assertEquals(LocationResult.Unavailable, WindowsLocation.parse("FIX 120 0 5"))
        assertEquals(LocationResult.Unavailable, WindowsLocation.parse("FIX 51,5 -0,12 5"))
        assertEquals(LocationResult.Unavailable, WindowsLocation.parse(""))
    }

    private fun awaitSample(store: PlacesStore, zone: Boolean, point: Boolean): Pair<String?, LocationFix?> {
        store.sample(zone, point)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val result = store.sample(zone, point)
            if (result.first != null || result.second != null) return result
            Thread.sleep(20)
        }
        return store.sample(zone, point)
    }

    @Test fun labelsPlaysWithTheSavedPlaceTheyHappenedAt() {
        val here = LocationFix(48.8584, 2.2945, 20f, System.currentTimeMillis())
        val store = PlacesStore(temp.root) { LocationResult.Found(here.copy(at = System.currentTimeMillis())) }
        store.update { it.copy(home = Place(48.8584, 2.2945, 250)) }
        val (zone, fix) = awaitSample(store, zone = true, point = true)
        assertEquals(PlaceZones.HOME, zone)
        assertEquals(48.8584, fix!!.latitude, 1e-9)
        val reopened = PlacesStore(temp.root) { LocationResult.Unavailable }
        assertTrue(reopened.places.value.configured)
        reopened.clear()
        assertFalse(PlacesStore(temp.root) { LocationResult.Unavailable }.places.value.configured)
    }

    @Test fun reportsWhenWindowsDeniesLocation() = runBlocking {
        val store = PlacesStore(temp.root) { LocationResult.Denied }
        assertEquals(LocationResult.Denied, store.currentLocation())
        assertTrue(store.denied.value)
        assertFalse(PlacesStore(temp.root, source = null).supported)
        assertNull(PlacesStore(temp.root, source = null).sample(zone = true, point = true).second)
    }

    @Test fun storeListsAndForgetsLocatedPlays() = runBlocking {
        val store = JsonLinesDetailedListeningStore(temp.newFile("plays.jsonl"), "windows")
        fun play(id: String, at: Long, located: Boolean) = DetailedPlay(id, at, at + 60_000, 60_000, PlayedTrack("song-$id", "Title $id", "Artist", "", "", "", 180),
            PlayContext(latitude = if (located) 10.0 else null, longitude = if (located) 20.0 else null, locationAccuracyM = if (located) 15f else null, zone = "HOME"))
        store.save(play("a", 1_000, true))
        store.save(play("b", 2_000, false))
        store.save(play("c", 3_000, true))
        assertEquals(2, store.locationCount())
        assertEquals(listOf("a", "c"), store.located().map { it.id })
        assertEquals(listOf("c"), store.located(from = 2_500).map { it.id })
        assertEquals(listOf("a"), store.located(songId = "song-a").map { it.id })
        store.clearLocations()
        assertEquals(0, store.locationCount())
        assertEquals(3, store.summary().plays)
        assertEquals("HOME", store.plays(0, Long.MAX_VALUE).first().context.zone)
        val reopened = JsonLinesDetailedListeningStore(temp.root.resolve("plays.jsonl"), "windows")
        assertEquals(0, reopened.locationCount())
        assertEquals(3, reopened.summary().plays)
    }
}
