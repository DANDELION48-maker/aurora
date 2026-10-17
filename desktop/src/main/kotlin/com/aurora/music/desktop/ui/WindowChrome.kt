package com.aurora.music.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.aurora.music.desktop.natives.WindowNative
import com.aurora.music.desktop.platform.HostPlatform

@Composable
fun WindowChrome(windowHandle: Long, translucent: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.onBackground.luminance() > 0.5f
    val caption = colors.background.toArgb()
    val text = colors.onBackground.toArgb()
    val border = colors.outline.toArgb()
    LaunchedEffect(windowHandle, dark, caption, text, border, translucent) {
        if (windowHandle == 0L || !HostPlatform.isWindows) return@LaunchedEffect
        WindowNative.setDarkMode(windowHandle, dark)
        if (translucent) WindowNative.clearCaptionColor(windowHandle) else WindowNative.setCaptionColor(windowHandle, caption)
        WindowNative.setTextColor(windowHandle, text)
        WindowNative.setBorderColor(windowHandle, border)
    }
}
