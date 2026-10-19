package com.aurora.music.desktop.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aurora.music.data.TabletSetting
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.player.PlayerController
import com.aurora.music.model.accent
import com.aurora.music.ui.components.LocalCoverHover
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.layout.WindowLayout
import com.aurora.music.ui.theme.AuroraTheme
import com.aurora.music.ui.theme.GlassLook
import com.aurora.music.ui.theme.accentSeed
import com.aurora.music.util.rememberDominantColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun AuroraRoot(
    container: DesktopContainer,
    player: PlayerController,
    initialPrefs: UiPrefs = UiPrefs(),
    systemAccent: Color? = null,
    translucent: Boolean = false,
    glassLook: GlassLook = GlassLook(),
    content: @Composable () -> Unit,
) {
    val uiPrefs by container.settingsStore.uiPrefs.collectAsState(initialPrefs)
    val followPlaying by container.desktopSettings.accentFollowsPlaying.collectAsState(false)
    val cover by remember(player) { player.state.map { it.current }.distinctUntilChanged { a, b -> a.artworkUrl == b.artworkUrl && a.accent == b.accent } }
        .collectAsState(null)
    val hover = remember { mutableStateOf<String?>(null) }
    var hovered by remember { mutableStateOf<String?>(null) }
    // settle before retinting the whole app, and bridge the gap between two neighbouring covers
    LaunchedEffect(hover) { snapshotFlow { hover.value }.collectLatest { delay(if (it == null) 250 else 120); hovered = it } }
    val playing = cover?.takeIf { followPlaying && it.artworkUrl.isNotBlank() }
    val seed = uiPrefs.accentSeed(systemAccent)
    val target = (hovered ?: playing?.artworkUrl).takeIf { uiPrefs.themeStyle == ThemeStyle.AURORA }
    val tint by rememberDominantColor(target.orEmpty(), if (hovered == null && playing != null) playing.accent else seed)
    // a cover without a palette falls back to the seed, which must stay exact rather than pass through readableAccent
    val playingAccent = tint.takeIf { it != seed }
    CompositionLocalProvider(LocalDesktopContainer provides container, LocalPlayer provides player,
        com.aurora.music.ui.components.LocalAnimatedCovers provides container.animatedCovers, LocalCoverHover provides hover) {
        AuroraTheme(uiPrefs, systemAccent, playingAccent, translucent, glassLook) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val window = WindowLayout(maxWidth.value.toInt(), maxHeight.value.toInt())
                val margin = uiPrefs.tabletPageMargin - TabletSetting.PAGE_MARGIN.default
                val gutter = (window.pageGutter + margin.dp).coerceAtLeast(0.dp)
                CompositionLocalProvider(LocalWindowLayout provides window, LocalPageGutter provides gutter) {
                    content()
                }
            }
        }
    }
}
