package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.R
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.data.InsightsPrefs
import com.aurora.music.data.InsightsSummary
import com.aurora.music.localization.appString
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun DetailedStatsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenInsights: () -> Unit = {}) {
    val container = LocalDesktopContainer.current
    val store = container.settingsStore
    val prefs by store.insightsPrefs.collectAsStateWithLifecycle<InsightsPrefs?>(initialValue = null)
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(InsightsSummary()) }
    var refresh by remember { mutableIntStateOf(0) }
    var device by rememberSaveable { mutableStateOf(true) }
    var audioPath by rememberSaveable { mutableStateOf(true) }
    var importExisting by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(prefs?.enabled, refresh) {
        if (prefs?.enabled == true) summary = container.listeningDetails.summary()
    }

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(appString(R.string.insights_title), onBack)
        val current = prefs ?: return@Column
        LazyColumn(Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!current.enabled) {
                item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp)) {
                            Text(appString(R.string.insights_intro_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(appString(R.string.insights_intro_body), Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { SettingsSectionTitle(appString(R.string.insights_guide_title)) }
                item {
                    SettingsGroup {
                        GuideStep(1, appString(R.string.insights_step_recorded_title), appString(R.string.insights_step_recorded_body))
                        GuideStep(2, appString(R.string.insights_step_extras_title), appString(R.string.insights_step_extras_body))
                        GuideStep(3, appString(R.string.insights_step_private_title), appString(R.string.insights_step_private_body))
                    }
                }
                item { SettingsSectionTitle(appString(R.string.insights_extras_title)) }
                item {
                    SettingsGroup {
                        SettingsSwitchRow(Icons.Filled.Headphones, appString(R.string.insights_device), appString(R.string.insights_device_desc), device) { device = it }
                        SettingsRowDivider()
                        SettingsSwitchRow(Icons.Filled.GraphicEq, appString(R.string.insights_audio_path), appString(R.string.insights_audio_path_desc), audioPath) { audioPath = it }
                        SettingsRowDivider()
                        SettingsSwitchRow(Icons.Filled.History, appString(R.string.insights_import), appString(R.string.insights_import_desc), importExisting) { importExisting = it }
                    }
                }
                item {
                    Button(onClick = {
                        busy = true
                        scope.launch {
                            try {
                                store.setInsightsDevice(device)
                                store.setInsightsAudioPath(audioPath)
                                if (importExisting) container.listeningDetails.importHistory(container.playHistory.snapshot())
                                store.setInsightsEnabled(true)
                            } finally { busy = false }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(appString(R.string.insights_enable))
                    }
                }
            } else {
                item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp)) {
                            Text(appString(R.string.insights_recording), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(summary.since?.let { appString(R.string.insights_count, summary.plays, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))) }
                                ?: appString(R.string.insights_empty), Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SettingsRowDivider()
                        SettingsSwitchRow(Icons.Filled.Insights, appString(R.string.insights_record), appString(R.string.insights_description), true) { on ->
                            scope.launch { store.setInsightsEnabled(on) }
                        }
                    }
                }
                item {
                    Button(onClick = onOpenInsights, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(appString(R.string.insights_open))
                    }
                }
                item { SettingsSectionTitle(appString(R.string.insights_extras_title)) }
                item {
                    SettingsGroup {
                        SettingsSwitchRow(Icons.Filled.Headphones, appString(R.string.insights_device), appString(R.string.insights_device_desc), current.device) { on ->
                            scope.launch { store.setInsightsDevice(on) }
                        }
                        SettingsRowDivider()
                        SettingsSwitchRow(Icons.Filled.GraphicEq, appString(R.string.insights_audio_path), appString(R.string.insights_audio_path_desc), current.audioPath) { on ->
                            scope.launch { store.setInsightsAudioPath(on) }
                        }
                    }
                }
                item { Note(appString(R.string.insights_step_private_body)) }
                item {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(appString(R.string.insights_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(containerColor = com.aurora.music.ui.theme.solidColors().surfaceContainerHigh, 
        onDismissRequest = { confirmDelete = false },
        icon = { androidx.compose.material3.Icon(Icons.Filled.Delete, null) },
        title = { Text(appString(R.string.insights_delete)) },
        text = { Text(appString(R.string.insights_delete_body)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch {
                    container.listeningDetails.clear()
                    refresh++
                }
            }) { Text(appString(R.string.insights_delete_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(appString(R.string.text_cancel_77dfd2)) } },
    )
}

@Composable
private fun GuideStep(number: Int, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
