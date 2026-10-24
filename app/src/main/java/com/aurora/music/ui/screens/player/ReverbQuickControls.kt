package com.aurora.music.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.ReverbPrefs
import com.aurora.music.data.ReverbPreset
import com.aurora.music.localization.appString
import java.text.NumberFormat
import kotlinx.coroutines.launch

internal fun reverbPresetName(id: Int): String = appString(when (id) {
    ReverbPreset.ROOM -> R.string.reverb_preset_room
    ReverbPreset.HALL -> R.string.reverb_preset_hall
    ReverbPreset.PLATE -> R.string.reverb_preset_plate
    ReverbPreset.CATHEDRAL -> R.string.reverb_preset_cathedral
    ReverbPreset.AMBIENCE -> R.string.reverb_preset_ambience
    ReverbPreset.LUSH -> R.string.reverb_preset_lush
    else -> R.string.reverb_custom
})

@Composable
internal fun ReverbPresetChips(selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) =
    ChoiceChips(ReverbPreset.ALL.indices.map(::reverbPresetName), selected, modifier, onPick)

@Composable
internal fun ReverbQuickControls(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as AuroraApplication).container.settingsStore }
    val prefs by store.reverbPrefs.collectAsStateWithLifecycle(initialValue = ReverbPrefs())
    val scope = rememberCoroutineScope()
    var dragMix by remember { mutableStateOf<Float?>(null) }
    val accent = MaterialTheme.colorScheme.secondary
    val concert = prefs.concertPrefs
    fun save(next: ReverbPrefs) { scope.launch { store.setReverb(next) } }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Waves, null, tint = accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(appString(R.string.reverb_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (!prefs.enabled) appString(R.string.reverb_summary)
                    else if (concert.enabled) "${appString(R.string.reverb_mode_concert)} · ${concertVenueName(concert.venue)} · ${concertSoundName(concert.sound)}"
                    else reverbPresetName(prefs.preset),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = prefs.enabled,
                onCheckedChange = { on -> scope.launch { store.setReverb(prefs.copy(enabled = on)) } },
                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onSecondary, checkedTrackColor = accent),
            )
        }
        if (prefs.enabled) {
            val mix = dragMix ?: if (concert.enabled) concert.amount else prefs.mix
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(appString(R.string.reverb_amount), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(NumberFormat.getPercentInstance().format(mix), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = mix,
                onValueChange = { v -> dragMix = v; save(if (concert.enabled) prefs.copy(concert = concert.copy(amount = v)) else prefs.copy(mix = v)) },
                onValueChangeFinished = { dragMix = null },
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReverbModeChips(concert.enabled) { on -> save(prefs.copy(concert = concert.copy(enabled = on))) }
                if (concert.enabled) {
                    ConcertVenuePicker(concert) { save(prefs.copy(concert = it)) }
                    ConcertPositionPicker(concert) { save(prefs.copy(concert = it)) }
                } else ReverbPresetChips(prefs.preset) { id -> save(prefs.withPreset(id)) }
            }
        }
        TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 4.dp)) { Text(appString(R.string.reverb_more)) }
    }
}
