package com.aurora.music.desktop.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.awaitApplication
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GraphicsEnvironment

class BorderlessFullscreenTest {
    @Test fun aMaximizedWindowLaysOutForItsClientAreaAfterFullScreen() = runBlocking {
        assumeTrue(BorderlessFullscreen.supported && !GraphicsEnvironment.isHeadless())
        val state = WindowState(placement = WindowPlacement.Maximized)
        var window: ComposeWindow? = null
        val app = launch(Dispatchers.Default) {
            awaitApplication {
                Window(onCloseRequest = ::exitApplication, state = state, title = "fullscreen") {
                    LaunchedEffect(Unit) { window = this@Window.window }
                    Box(Modifier.fillMaxSize().background(Color.DarkGray))
                }
            }
        }
        try {
            while (window?.isDisplayable != true) delay(50)
            delay(800)
            val frame = window!!
            val before = withContext(Dispatchers.Swing) { frame.contentPane.size }
            withContext(Dispatchers.Swing) { assertTrue(BorderlessFullscreen.enter(frame.windowHandle)) }
            delay(800)
            withContext(Dispatchers.Swing) { assertTrue(BorderlessFullscreen.exit(frame.windowHandle)) }
            delay(1000)
            withContext(Dispatchers.Swing) {
                val client = WinDef.RECT().also { User32.INSTANCE.GetClientRect(WinDef.HWND(Pointer(frame.windowHandle)), it) }
                assertEquals(WindowPlacement.Maximized, state.placement)
                assertEquals(before, frame.contentPane.size)
                assertEquals(client.bottom / frame.graphicsConfiguration.defaultTransform.scaleY, frame.contentPane.height.toDouble(), 1.0)
            }
        } finally {
            app.cancel()
        }
    }
}
