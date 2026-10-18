package com.aurora.music.ui.settings.general

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.AudioTags
import com.aurora.music.ui.screens.detail.TagEditScreen
import com.aurora.music.ui.screens.settings.ArtistSeparatorsScreen
import com.aurora.music.ui.screens.settings.OutputPresetBindingsScreen
import com.aurora.music.ui.screens.settings.PresetRulesScreen
import com.aurora.music.ui.screens.settings.SonicSettingsScreen
import com.aurora.music.ui.screens.settings.StorageSettingsScreen
import com.aurora.music.ui.testing.distinctColors
import com.aurora.music.viewmodel.TagEditState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortedSettingsTest {
    private val padding = PaddingValues()

    @Test fun artistSeparatorsRender() {
        GeneralSettingsScene("artist-separators") { ArtistSeparatorsScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 20) }
    }

    @Test fun storageShowsTheAudioCache() {
        GeneralSettingsScene("storage-cache", height = 1400) { StorageSettingsScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 20) }
    }

    @Test fun tagEditorShowsTheFileAndItsTags() {
        val state = TagEditState(loading = false, songId = "s1", path = "C:\\Music\\Night Signal.flac", localFile = true,
            tags = AudioTags(title = "Night Signal", artist = "Aurora", album = "Signals", year = "2026", trackNumber = "7"))
        var identified = 0
        GeneralSettingsScene("tag-edit", height = 1100) {
            TagEditScreen(padding, state, onEdit = {}, onMatch = {}, onApplyMatch = {}, onIdentify = { identified++ }, onBack = {}, confirm = {})
        }.use { scene ->
            assertTrue(scene.shot().distinctColors() > 20)
            assertEquals(0, identified)
        }
    }

    @Test fun audioRuleScreensRender() {
        GeneralSettingsScene("preset-rules", height = 1100) { PresetRulesScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 20) }
        GeneralSettingsScene("output-presets", height = 1100) { OutputPresetBindingsScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 20) }
        GeneralSettingsScene("sonic", height = 1000) { SonicSettingsScreen(padding, onBack = {}) }.use { assertTrue(it.shot().distinctColors() > 20) }
    }

    @Test fun comparisonRenders() {
        GeneralSettingsScene("comparison", height = 1000) {
            val scene = com.aurora.music.desktop.ui.LocalDesktopContainer.current
            val player = com.aurora.music.desktop.ui.LocalPlayer.current
            val model = androidx.compose.runtime.remember { com.aurora.music.viewmodel.ComparisonViewModel(scene, player) }
            com.aurora.music.ui.screens.settings.ComparisonScreen(padding, onBack = {}, onOpenPresets = {}, model = model)
        }.use { assertTrue(it.shot().distinctColors() > 20) }
    }

    @Test fun tuningProjectsRender() {
        GeneralSettingsScene("tuning", height = 1000) {
            com.aurora.music.ui.screens.settings.TuningProjectsScreen(padding, onBack = {}, onOpenRack = {})
        }.use { assertTrue(it.shot().distinctColors() > 20) }
    }

    @Test fun listeningLevelsRender() {
        GeneralSettingsScene("listening-levels", height = 1000) {
            com.aurora.music.ui.screens.settings.ListeningLevelsScreen(padding, onBack = {})
        }.use { assertTrue(it.shot().distinctColors() > 20) }
    }

    @Test fun mixStudioRenders() {
        val songs = listOf(com.aurora.music.model.Song("a", "Night Signal", "Aurora", "Signals", "", 240, streamUrl = "file:///C:/a.flac"),
            com.aurora.music.model.Song("b", "Afterglow", "Aurora", "Signals", "", 210, streamUrl = "file:///C:/b.flac"))
        GeneralSettingsScene("mix-studio", width = 1200, height = 900) {
            val container = com.aurora.music.desktop.ui.LocalDesktopContainer.current
            val model = androidx.compose.runtime.remember { com.aurora.music.viewmodel.MixViewModel(container) }
            com.aurora.music.ui.screens.player.MixScreen(model, songs, onClose = {})
        }.use { assertTrue(it.shot().distinctColors() > 20) }
    }
}
