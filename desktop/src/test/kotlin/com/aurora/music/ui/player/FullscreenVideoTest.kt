package com.aurora.music.ui.player

import com.aurora.music.desktop.video.VideoPlayer
import com.aurora.music.ui.screens.player.FullscreenMusicVideo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FullscreenVideoTest {
    private fun scene(events: MutableList<String>, video: VideoPlayer) = PlayerScene("video-fullscreen", 1280, 720) {
        FullscreenMusicVideo(
            video, PlayerFixtures.playing, androidx.compose.ui.graphics.Color.Magenta,
            onAudio = { events += "audio" },
            onExit = { events += "exit" },
            onVideoQualityChange = { events += "quality" },
            onTogglePlay = { events += "play" },
            onPrevious = { events += "previous" },
            onNext = { events += "next" },
            onSeek = { events += "seek" },
        )
    }

    private fun missingVideo() = VideoPlayer(File("missing-clip.mp4").toURI().toString())

    @Test fun overlayButtonsReachTheirActions() {
        val events = mutableListOf<String>()
        missingVideo().use { video ->
            scene(events, video).use { scene ->
                scene.shot()
                scene.click(1238f, 38f)
                scene.click(42f, 38f)
                scene.click(640f, 360f)
                scene.frames(4, realMillis = 120)
            }
        }
        assertEquals(listOf("exit", "audio", "play"), events)
    }

    // the idle timer and the double-click wait run on real time, so these waits are real
    @Test fun controlsHideWhenIdleAndReturnOnMouseMove() {
        val events = mutableListOf<String>()
        missingVideo().use { video ->
            scene(events, video).use { scene ->
                scene.frames(32, realMillis = 120)
                scene.click(1238f, 38f)
                scene.frames(4, realMillis = 120)
                scene.frames(32, realMillis = 120)
                scene.hover(700f, 400f)
                scene.click(1238f, 38f)
            }
        }
        assertEquals(listOf("play", "exit"), events)
    }
}
