package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.R
import kotlinx.coroutines.launch

@Composable
internal fun ArtworkLookupSettings(onOpenStorage: () -> Unit) {
    val container = (LocalContext.current.applicationContext as com.aurora.music.AuroraApplication).container
    val enabled by container.settingsStore.artworkLookupEnabled.collectAsStateWithLifecycle(initialValue = true)
    val bytes by container.artworkRepository.bytes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    SettingsSectionTitle(stringResource(R.string.artwork_title))
    SettingsGroup {
        SettingsSwitchRow(Icons.Outlined.Image, stringResource(R.string.artwork_lookup),
            stringResource(R.string.artwork_description), enabled) {
            scope.launch { container.settingsStore.setArtworkLookupEnabled(it) }
        }
        SettingsRowDivider()
        Text(stringResource(R.string.artwork_provider), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        SettingsRowDivider()
        SettingsNavRow(Icons.Outlined.Storage, stringResource(R.string.storage_cover_cache_moved),
            subtitle = stringResource(R.string.storage_cover_cache_moved_detail), value = storageSize(bytes), onClick = onOpenStorage)
    }
}
