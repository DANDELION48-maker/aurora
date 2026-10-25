package com.aurora.music.playback

import android.os.SystemClock
import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.model.Song
import com.aurora.music.viewmodel.PlayerViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class QueueReplacementDeviceTest {
    private val fixture = PrecisionPlaybackDeviceTest()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AuroraApplication

    @Before fun setup() = fixture.keepTargetForegroundForAudioFocus()
    @After fun cleanup() = fixture.removeFixturesAndFinishActivity()

    @Test fun unchangedLargeQueueStillFillsBeforeAndAfterTheStartItem() = withQueue { vm, controller, songs ->
        val startIndex = 1_290
        fixture.main { vm.playAll(songs, startIndex) }
        fixture.await("start track selected", controller) { fixture.main {
            if (controller.currentMediaItem?.mediaId == songs[startIndex].id) {
                controller.pause()
                true
            } else false
        } }
        awaitQueue(controller, songs.map { it.id })
        fixture.main {
            controller.pause()
            assertEquals(startIndex, controller.currentMediaItemIndex)
            assertEquals(songs[startIndex].id, controller.currentMediaItem?.mediaId)
        }
    }

    @Test fun smallReplacementDoesNotReceivePreviousQueueChunks() = withQueue { vm, controller, songs ->
        val replacement = songs.first().copy(id = "queue-small-replacement")
        fixture.main {
            vm.playAll(songs)
            assertTrue(vm.state.value.queue.size < songs.size)
            vm.play(replacement)
        }
        awaitQueue(controller, listOf(replacement.id))
        assertStableQueue(controller, listOf(replacement.id))
        assertEquals(listOf(replacement.id), vm.state.value.queue.map { it.id })
    }

    @Test fun stoppingDuringBackfillKeepsThePlayerEmpty() = withQueue { vm, controller, songs ->
        fixture.main {
            vm.playAll(songs)
            assertTrue(vm.state.value.queue.size < songs.size)
            vm.stopPlayback()
        }
        awaitQueue(controller, emptyList())
        assertStableQueue(controller, emptyList())
        fixture.main { assertFalse(controller.playWhenReady) }
        assertTrue(vm.state.value.queue.isEmpty())
        assertFalse(vm.state.value.hasTrack)
    }

    @Test fun anotherControllerReplacementStopsPreviousBackfill() = withQueue { vm, controller, songs ->
        fixture.main { vm.playAll(songs) }
        fixture.await("large queue reaches the service", controller) {
            fixture.main { controller.mediaItemCount >= 120 }
        }
        fixture.main {
            assertTrue("backfill must still be pending", controller.mediaItemCount < songs.size)
            controller.setMediaItem(MediaItem.Builder().setMediaId("queue-external-replacement")
                .setUri(songs.first().streamUrl).build())
            controller.pause()
        }
        awaitQueue(controller, listOf("queue-external-replacement"))
        assertStableQueue(controller, listOf("queue-external-replacement"))
    }

    @Test fun replacementRetainingTheSameItemTokenStillStopsPreviousBackfill() = withQueue { vm, controller, songs ->
        fixture.main { vm.playAll(songs) }
        fixture.await("large queue reaches the service", controller) { fixture.main { controller.mediaItemCount >= 120 } }
        val retained = fixture.main {
            assertTrue("backfill must still be pending", controller.mediaItemCount < songs.size)
            controller.getMediaItemAt(0).buildUpon().setUri(songs.first().streamUrl).build().also {
                controller.setMediaItem(it)
                controller.pause()
            }
        }
        awaitQueue(controller, listOf(retained.mediaId))
        assertStableQueue(controller, listOf(retained.mediaId))
    }

    @Test fun completedShuffledBackfillRetainsItsOriginalRestoreOrder() = withQueue(songCount = 480) { vm, controller, songs ->
        fixture.main { vm.shufflePlay(songs) }
        fixture.await("shuffled queue completes", controller) { fixture.main { controller.mediaItemCount == songs.size } }
        val current = fixture.main {
            controller.pause()
            assertTrue(controller.shuffleModeEnabled)
            assertEquals(songs.map { it.id }.toSet(), (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }.toSet())
            controller.currentMediaItem?.mediaId
        }
        val command = SessionCommand(PlaybackService.CMD_SHUFFLE, Bundle().apply { putInt("target", 0) })
        val result = fixture.main { controller.sendCustomCommand(command, Bundle.EMPTY) }.get(10, TimeUnit.SECONDS)
        assertEquals(0, result.resultCode)
        awaitQueue(controller, songs.map { it.id })
        fixture.main {
            assertFalse(controller.shuffleModeEnabled)
            assertEquals(current, controller.currentMediaItem?.mediaId)
        }
    }

    @Test fun smallShuffledQueueRestoresItsOriginalOrder() = withQueue(songCount = 3) { vm, controller, songs ->
        fixture.main { vm.shufflePlay(songs) }
        fixture.await("small shuffle reaches the service", controller) {
            fixture.main { controller.mediaItemCount == songs.size && controller.shuffleModeEnabled }
        }
        val current = fixture.main { controller.currentMediaItem?.mediaId }
        val command = SessionCommand(PlaybackService.CMD_SHUFFLE, Bundle().apply { putInt("target", 0) })
        val result = fixture.main { controller.sendCustomCommand(command, Bundle.EMPTY) }.get(10, TimeUnit.SECONDS)
        assertEquals(0, result.resultCode)
        awaitQueue(controller, songs.map { it.id })
        fixture.main {
            assertFalse(controller.shuffleModeEnabled)
            assertEquals(current, controller.currentMediaItem?.mediaId)
        }
    }

    @Test fun hugeQueueStaysResponsiveAndShuffleKeepsWorkingAfterIt() = withQueue(songCount = 5_000) { vm, controller, base ->
        // distinct uris so the reorder replaces media sources instead of updating them in place
        val songs = base.mapIndexed { i, song -> song.copy(streamUrl = "${song.streamUrl}?n=$i") }
        val ids = songs.map { it.id }
        fixture.main { vm.playAll(songs); controller.pause() }
        val stall = worstMainStallMs { fixture.main { controller.mediaItemCount } == songs.size }
        awaitQueue(controller, ids)
        val started = SystemClock.elapsedRealtime()
        shuffle(controller, 1)
        val took = SystemClock.elapsedRealtime() - started
        android.util.Log.i("AuroraQueueTest", "5000 tracks: worst main stall ${stall}ms, shuffle ${took}ms")
        assertTrue("main thread stalled ${stall}ms while the queue loaded", stall < 1_000)
        assertTrue("shuffling 5000 tracks took ${took}ms", took < 3_000)
        val shuffled = queueIds(controller)
        assertEquals(ids.first(), shuffled.first())
        assertEquals(ids.toSet(), shuffled.toSet())
        assertTrue(shuffled != ids)
        shuffle(controller, 0)
        awaitQueue(controller, ids)

        val small = songs.take(4).map { it.copy(id = "small-${it.id}") }
        fixture.main { vm.playAll(small); controller.pause() }
        awaitQueue(controller, small.map { it.id })
        fixture.main { vm.toggleShuffle() }
        fixture.await("small queue shuffles", controller) { fixture.main { controller.shuffleModeEnabled } }
        fixture.main { vm.toggleShuffle() }
        fixture.await("small queue unshuffles", controller) { fixture.main { !controller.shuffleModeEnabled } }
        awaitQueue(controller, small.map { it.id })
    }

    private fun shuffle(controller: MediaController, target: Int) {
        val command = SessionCommand(PlaybackService.CMD_SHUFFLE, Bundle().apply { putInt("target", target) })
        assertEquals(0, fixture.main { controller.sendCustomCommand(command, Bundle.EMPTY) }.get(10, TimeUnit.SECONDS).resultCode)
    }

    private fun worstMainStallMs(done: () -> Boolean): Long {
        var worst = 0L
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!done() && SystemClock.elapsedRealtime() < deadline) {
            val posted = SystemClock.elapsedRealtime()
            fixture.main { }
            worst = maxOf(worst, SystemClock.elapsedRealtime() - posted)
            SystemClock.sleep(16)
        }
        return worst
    }

    private fun withQueue(songCount: Int = 2_400, block: (PlayerViewModel, MediaController, List<Song>) -> Unit) {
        fixture.withProcessingFixture(0) { controller, _ ->
            val audio = fixture.wav("queue-replacement", 48_000, 48_000 * 3) { _, _ -> 0 }
            val uri = audio.toURI().toString()
            fixture.main {
                controller.setMediaItem(MediaItem.Builder().setMediaId("queue-connection-seed").setUri(uri).build())
            }
            val lifecycle = ViewModelStore()
            val vm = fixture.main { PlayerViewModel(app).also { lifecycle.put("queue", it) } }
            try {
                fixture.await("view model controller connects", controller) {
                    vm.state.value.current.id == "queue-connection-seed"
                }
                val songs = List(songCount) { index ->
                    Song("queue-large-$index", "Queue $index", "", "", "", 3, streamUrl = uri)
                }
                block(vm, controller, songs)
            } finally {
                fixture.main { vm.stopPlayback(); lifecycle.clear() }
            }
        }
    }

    private fun queueIds(controller: MediaController): List<String> = fixture.main {
        (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }
    }

    private fun awaitQueue(controller: MediaController, ids: List<String>) =
        fixture.await("replacement queue reaches the service", controller) { queueIds(controller) == ids }

    private fun assertStableQueue(controller: MediaController, ids: List<String>) {
        val deadline = SystemClock.elapsedRealtime() + 1_200
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(40)
            assertEquals("cancelled backfill must not append old tracks", ids, queueIds(controller))
        }
    }
}
