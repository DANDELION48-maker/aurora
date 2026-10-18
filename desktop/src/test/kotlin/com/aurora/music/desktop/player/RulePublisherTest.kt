package com.aurora.music.desktop.player

import com.aurora.music.data.SettingsStore
import com.aurora.music.data.routes.OutputDeviceCategory
import com.aurora.music.data.routes.ProcessingRouteKind
import com.aurora.music.data.rules.RuleSource
import com.aurora.music.desktop.audio.EnginePhase
import com.aurora.music.desktop.audio.EngineState
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.desktop.audio.OutputInfo
import com.aurora.music.desktop.audio.QueueEntry
import com.aurora.music.desktop.audio.decode.SampleKind
import com.aurora.music.desktop.audio.decode.SourceSampleFormat
import com.aurora.music.desktop.audio.decode.StreamInfo
import com.aurora.music.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.first

class RulePublisherTest {
    @get:org.junit.Rule val temp = org.junit.rules.TemporaryFolder()
    private val publisher by lazy {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        RulePublisher(SettingsStore(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) {
            java.io.File(temp.root, "aurora_settings.preferences_pb") }, temp.root, temp.root))
    }

    private fun output(name: String?, id: String? = "{0.0.0.00000000}.{abc}") =
        OutputInfo(id, name, exclusive = false, sampleRate = 48_000, encoding = OutputEncoding.values().first(), followsDefault = false)

    @Test fun outputsBecomeDeviceRoutesKeyedByTheirId() {
        val route = publisher.route(output("Headphones (Realtek USB Audio)"))
        assertEquals(ProcessingRouteKind.ANDROID, route.kind)
        assertEquals(RulePublisher.routeKey("{0.0.0.00000000}.{abc}"), route.key)
        assertTrue(com.aurora.music.data.routes.ProcessingRouteCodec.validKey(route.key!!))
        assertEquals("Headphones (Realtek USB Audio)", route.label)
        assertEquals(OutputDeviceCategory.USB, route.category)
        assertTrue(route.bindsDeviceEq)
        assertEquals(OutputDeviceCategory.BLUETOOTH, publisher.route(output("WH-1000XM5 Hands-Free")).category)
        assertEquals(OutputDeviceCategory.SPEAKER, publisher.route(output("Speakers (Realtek(R) Audio)")).category)
        assertEquals(RulePublisher.routeKey("Speakers"), publisher.route(output("Speakers", id = null)).key)
        assertFalse(route.key == publisher.route(output("Headphones", id = "{0.0.0.00000000}.{def}")).key)
        assertEquals(ProcessingRouteKind.IDLE, publisher.route(null).kind)
    }

    // windows calls usb and wireless headsets "Speakers (...)", so the endpoint's bus decides first
    @Test fun theEndpointBusOutranksTheName() {
        val buses = mapOf("{0.0.0.00000000}.{usb}" to com.aurora.music.desktop.platform.WindowsAudioEndpoints.Bus.USB,
            "{0.0.0.00000000}.{bt}" to com.aurora.music.desktop.platform.WindowsAudioEndpoints.Bus.BLUETOOTH,
            "{0.0.0.00000000}.{hda}" to com.aurora.music.desktop.platform.WindowsAudioEndpoints.Bus.HD_AUDIO)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val store = SettingsStore(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) {
            java.io.File(temp.root, "bus_settings.preferences_pb") }, temp.root, temp.root)
        val publisher = RulePublisher(store) { buses[it] }
        assertEquals(OutputDeviceCategory.USB, publisher.route(output("Speakers (Wireless Gaming Headset)", "{0.0.0.00000000}.{usb}")).category)
        assertEquals(OutputDeviceCategory.BLUETOOTH, publisher.route(output("Speakers (WH-1000XM5)", "{0.0.0.00000000}.{bt}")).category)
        assertEquals(OutputDeviceCategory.HEADPHONES, publisher.route(output("Headphones (High Definition Audio Device)", "{0.0.0.00000000}.{hda}")).category)
        assertEquals(OutputDeviceCategory.SPEAKER, publisher.route(output("Speakers (Realtek(R) Audio)", "{0.0.0.00000000}.{hda}")).category)
        assertEquals(com.aurora.music.desktop.platform.WindowsAudioEndpoints.Bus.USB,
            com.aurora.music.desktop.platform.WindowsAudioEndpoints.busOf("""{1}.USB\VID_03F0&PID_02CC&MI_00\9&12942230&0&0000"""))
        assertEquals(com.aurora.music.desktop.platform.WindowsAudioEndpoints.Bus.BLUETOOTH,
            com.aurora.music.desktop.platform.WindowsAudioEndpoints.busOf("""{2}.\\?\bthhfenum#{0000111e}"""))
    }

    // the shared stores only take android-shaped keys, so a desktop output has to pass them to be bound at all
    @Test fun outputsCanBeBoundToPresetsAndLevels() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val store = SettingsStore(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) {
            java.io.File(temp.root, "bind_settings.preferences_pb") }, temp.root, temp.root)
        val publisher = RulePublisher(store)
        publisher.publish(EngineState(output = output("Headphones (Realtek USB Audio)")))
        store.saveProcessingPreset("Test").getOrThrow()
        val preset = store.processingPresetLibrary.first().presets.single()
        store.bindCurrentRoute(preset.id, "").getOrThrow()
        val rules = store.processingRouteRules.first()
        assertEquals(store.processingRoutes.current.route.key, rules.bindings.single().routeKey)
        assertTrue(Regex("android:[0-9]+:[a-f0-9]{64}").matches(rules.bindings.single().routeKey))
    }

    @Test fun playingTracksDescribeTheirSourceAndFormat() {
        val song = Song("s1", "Night Signal", "Aurora", "Signals", "", 200, streamUrl = "file:///C:/Music/a.flac", genre = "Synthwave", suffix = "flac")
        val state = EngineState(entries = listOf(QueueEntry(1, song)), index = 0, playWhenReady = true, phase = EnginePhase.READY,
            source = StreamInfo("FLAC", "flac", 96_000, 2, SourceSampleFormat(SampleKind.INTEGER, 24), 0, 200_000, true))
        val context = publisher.context(state)
        assertTrue(context.active)
        assertEquals("s1", context.mediaId)
        assertEquals(RuleSource.LOCAL_FILE, context.source)
        assertEquals(96_000, context.sampleRateHz)
        assertEquals("flac", context.codec)
        assertEquals("flac", context.container)
        assertEquals(setOf("Synthwave"), context.genres)
        assertFalse(publisher.context(state.copy(playWhenReady = false, phase = EnginePhase.IDLE)).active)
        assertNull(publisher.context(EngineState()).mediaId)
    }
}
