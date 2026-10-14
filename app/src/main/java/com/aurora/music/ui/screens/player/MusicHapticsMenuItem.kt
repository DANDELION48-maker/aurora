package com.aurora.music.ui.screens.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.MusicHapticsPrefs
import com.aurora.music.localization.appString
import com.aurora.music.playback.MusicHaptics
import kotlinx.coroutines.launch

// a toggle, so the menu stays open to show the new state
@Composable
internal fun MusicHapticsMenuItem() {
    val context = LocalContext.current
    val support = remember { MusicHaptics.support(context) }
    if (!support.available) return
    val store = remember { (context.applicationContext as AuroraApplication).container.settingsStore }
    val prefs by store.musicHapticsPrefs.collectAsStateWithLifecycle(initialValue = MusicHapticsPrefs())
    val scope = rememberCoroutineScope()
    DropdownMenuItem(
        text = { Text(appString(R.string.music_haptics_title)) },
        onClick = { scope.launch { store.setMusicHaptics(!prefs.enabled) } },
        leadingIcon = { Icon(Icons.Filled.Vibration, null) },
        trailingIcon = if (prefs.enabled) ({ Icon(Icons.Filled.Check, null) }) else null,
    )
}
