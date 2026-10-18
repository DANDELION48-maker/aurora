package com.aurora.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aurora.music.ui.testing.EdtScene
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Test

class SliderThumbTest {
    // vertical centre of the pixels that pass the colour test, in pixels
    private fun centre(bitmap: Bitmap, match: (Int) -> Boolean): Float {
        val rows = (0 until bitmap.height).filter { y -> (0 until bitmap.width).any { x -> match(bitmap.getColor(x, y)) } }
        return (rows.first() + rows.last()) / 2f
    }

    @Test fun theVolumeThumbSitsOnTheTrackCentre() {
        listOf(1f, 1.25f, 1.5f, 1.75f, 2f).forEach { scale ->
            EdtScene(400, 120) {
                CompositionLocalProvider(LocalDensity provides Density(scale)) {
                    MaterialTheme(darkColorScheme(onSurface = Color.Green, onSurfaceVariant = Color.Blue, background = Color.Black)) {
                        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                            VolumeControl(0.5f, {}, {}, 160.dp, fill = Color.Red)
                        }
                    }
                }
            }.use { scene ->
                val bitmap = Bitmap.makeFromImage(scene.frame(500))
                val thumb = centre(bitmap) { (it shr 8 and 0xff) > 180 && (it shr 16 and 0xff) < 80 }
                val track = centre(bitmap) { (it shr 16 and 0xff) > 180 && (it shr 8 and 0xff) < 80 }
                assertEquals("scale $scale", track, thumb, 0.5f)
            }
        }
    }
}
