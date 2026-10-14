package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import com.aurora.music.localization.appString
import com.aurora.music.ui.screens.player.ReverbPresetChips
import java.text.NumberFormat
import kotlin.math.exp
import kotlin.math.ln
import kotlinx.coroutines.launch

@Composable
fun ReverbSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val store = (LocalContext.current.applicationContext as AuroraApplication).container.settingsStore
    val prefs by store.reverbPrefs.collectAsStateWithLifecycle(initialValue = ReverbPrefs())
    val scope = rememberCoroutineScope()
    val percent = remember { NumberFormat.getPercentInstance() }
    fun save(next: ReverbPrefs) { scope.launch { store.setReverb(next) } }
    fun shape(edit: (ReverbPrefs) -> ReverbPrefs) = save(edit(prefs).copy(preset = -1))
    fun seconds(v: Float) = appString(R.string.reverb_value_seconds, "%.1f".format(v))
    fun millis(v: Float) = appString(R.string.reverb_value_ms, "%.0f".format(v))
    fun hertz(v: Float) = if (v < 1000f) appString(R.string.reverb_value_hz, "%.0f".format(v))
        else appString(R.string.reverb_value_khz, "%.1f".format(v / 1000f))

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.reverb_title), onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Waves, appString(R.string.reverb_title), appString(R.string.reverb_summary), prefs.enabled) { on ->
                        save(prefs.copy(enabled = on))
                    }
                    SettingsRowDivider()
                    ReverbSlider(appString(R.string.reverb_amount), prefs.mix, 0f..1f, label = percent::format) { v -> save(prefs.copy(mix = v)) }
                }
            }
            item { SettingsSectionTitle(appString(R.string.reverb_section_style)) }
            item {
                SettingsGroup {
                    ReverbPresetChips(prefs.preset, Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) { id -> save(prefs.withPreset(id)) }
                }
            }
            item { SettingsSectionTitle(appString(R.string.reverb_section_space)) }
            item {
                SettingsGroup {
                    ReverbSlider(appString(R.string.reverb_decay), prefs.decaySec, 0.2f..12f, log = true, label = ::seconds) { v -> shape { it.copy(decaySec = v) } }
                    ReverbSlider(appString(R.string.reverb_size), prefs.size, 0f..1f, label = percent::format) { v -> shape { it.copy(size = v) } }
                    ReverbSlider(appString(R.string.reverb_predelay), prefs.predelayMs, 0f..200f, label = ::millis) { v -> shape { it.copy(predelayMs = v) } }
                    ReverbSlider(appString(R.string.reverb_early), prefs.early, 0f..1f, label = percent::format) { v -> shape { it.copy(early = v) } }
                }
            }
            item { SettingsSectionTitle(appString(R.string.reverb_section_tone)) }
            item {
                SettingsGroup {
                    ReverbSlider(appString(R.string.reverb_damping), prefs.damping, 0f..1f, label = percent::format) { v -> shape { it.copy(damping = v) } }
                    ReverbSlider(appString(R.string.reverb_low_cut), prefs.lowCutHz, 20f..1000f, log = true, label = ::hertz) { v -> shape { it.copy(lowCutHz = v) } }
                    ReverbSlider(appString(R.string.reverb_high_cut), prefs.highCutHz, 1500f..20000f, log = true, label = ::hertz) { v -> shape { it.copy(highCutHz = v) } }
                }
            }
            item { SettingsSectionTitle(appString(R.string.reverb_section_character)) }
            item {
                SettingsGroup {
                    ReverbSlider(appString(R.string.reverb_width), prefs.width, 0f..1.5f, label = percent::format) { v -> shape { it.copy(width = v) } }
                    ReverbSlider(appString(R.string.reverb_modulation), prefs.modulation, 0f..1f, label = percent::format) { v -> shape { it.copy(modulation = v) } }
                }
            }
            item {
                Text(appString(R.string.reverb_note), Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// drafts the thumb locally so datastore round-trips never make it jump
@Composable
private fun ReverbSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, log: Boolean = false,
    label: (Float) -> String, onChange: (Float) -> Unit) {
    var draft by remember { mutableStateOf<Float?>(null) }
    val low = range.start; val high = range.endInclusive
    fun position(v: Float) = if (log) (ln(v / low) / ln(high / low)) else (v - low) / (high - low)
    fun valueAt(p: Float) = if (log) low * exp(p * ln(high / low)) else low + p * (high - low)
    val shown = draft ?: value
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(label(shown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = position(shown.coerceIn(low, high)),
            onValueChange = { p -> valueAt(p).also { draft = it; onChange(it) } },
            onValueChangeFinished = { draft = null },
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}
