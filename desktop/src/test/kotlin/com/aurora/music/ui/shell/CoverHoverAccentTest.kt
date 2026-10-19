package com.aurora.music.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import com.aurora.music.data.AccentMode
import com.aurora.music.data.ThemeMode
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.desktop.ui.AuroraRoot
import com.aurora.music.ui.components.coverHover
import com.aurora.music.ui.testing.EdtScene
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CoverHoverAccentTest {
    @Test fun hoveringACoverTintsTheAccentAndLeavingRestoresIt() {
        val root = Files.createTempDirectory("aurora-hover").toFile()
        val container = DesktopContainer(DesktopPaths(File(root, "Roaming"), File(root, "Local")))
        SingletonImageLoader.setUnsafe(container.imageLoader)
        // a dark custom accent on a dark theme is what readableAccent would lighten
        runBlocking {
            container.settingsStore.setThemeMode(ThemeMode.DARK)
            container.settingsStore.setAccentMode(AccentMode.CUSTOM)
            container.settingsStore.setAccentColor(0xFF0078D4)
        }
        val art = ShellFixtures.queue[1].artworkUrl
        var primary = Color.Unspecified
        EdtScene(400, 300) {
            AuroraRoot(container, ShellPlayer()) {
                primary = MaterialTheme.colorScheme.primary
                Box(Modifier.size(100.dp).coverHover(art))
                Box(Modifier.padding(start = 200.dp).size(100.dp).coverHover(File(root, "missing.png").absolutePath))
            }
        }.use { scene ->
            fun settle(realMillis: Long) {
                val until = System.currentTimeMillis() + realMillis
                while (System.currentTimeMillis() < until) {
                    Thread.sleep(15)
                    scene.frame(32)
                }
            }
            fun move(x: Float, y: Float) = scene.input { sendPointerEvent(PointerEventType.Move, Offset(x, y), timeMillis = scene.millis) }
            settle(400)
            val accent = primary
            move(50f, 50f)
            settle(1500)
            assertNotEquals("hovering the cover takes its colour", accent, primary)
            move(300f, 250f)
            settle(1500)
            assertEquals("leaving it brings the set accent back", accent, primary)
            move(250f, 50f)
            settle(1500)
            assertEquals("a cover with no artwork leaves the accent alone", accent, primary)
        }
    }
}
