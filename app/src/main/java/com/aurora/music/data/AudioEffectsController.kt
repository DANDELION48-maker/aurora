package com.aurora.music.data

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// effect calls guarded some devices dont implement every effect
class AudioEffectsController(
    private val sessionId: Int,
    settingsStore: SettingsStore,
    scope: CoroutineScope,
) {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null

    @Volatile private var created = false
    private var bands = 5
    private var freqs: List<Int> = listOf(60, 230, 910, 3600, 14000)
    private var minMb = -1500
    private var maxMb = 1500
    private var presets: List<String> = emptyList()
    private var supported = false

    val bandCount: Int get() { ensureCreated(); return bands }
    val bandFreqsHz: List<Int> get() { ensureCreated(); return freqs }
    val minBandMb: Int get() { ensureCreated(); return minMb }
    val maxBandMb: Int get() { ensureCreated(); return maxMb }
    val presetNames: List<String> get() { ensureCreated(); return presets }
    val available: Boolean get() { ensureCreated(); return supported }

    /** Query enabled effects, rather than inferring success from their saved preferences. */
    fun activeEffectNames(): List<String> = buildList {
        if (runCatching { equalizer?.enabled == true }.getOrDefault(false)) add("System EQ")
        if (runCatching { bassBoost?.enabled == true }.getOrDefault(false)) add("System bass boost")
        if (runCatching { virtualizer?.enabled == true }.getOrDefault(false)) add("System virtualizer")
        if (runCatching { loudness?.enabled == true }.getOrDefault(false)) add("System loudness gain")
    }

    // gated here not in prefs so system effects never stack with custom dsp while ui keeps values
    @Volatile private var masterEnabled: Boolean = true
    @Volatile private var lastPrefs: AudioPrefs? = null

    init {
        scope.launch { settingsStore.audioPrefs.collect { lastPrefs = it; apply(it) } }
    }

    fun setMasterEnabled(on: Boolean) {
        if (masterEnabled == on) return
        masterEnabled = on
        lastPrefs?.let { apply(it) }
    }

    private fun ensureCreated() {
        if (created) return
        synchronized(this) {
            if (created) return
            runCatching {
                val eq = Equalizer(0, sessionId)
                equalizer = eq
                bands = eq.numberOfBands.toInt()
                freqs = (0 until bands).map { (eq.getCenterFreq(it.toShort()) / 1000) }
                val range = eq.bandLevelRange
                minMb = range[0].toInt()
                maxMb = range[1].toInt()
                presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }
                supported = true
            }
            runCatching { bassBoost = BassBoost(0, sessionId) }
            runCatching { virtualizer = Virtualizer(0, sessionId) }
            runCatching { loudness = LoudnessEnhancer(sessionId) }
            created = true
        }
    }

    // untouched effects stay uncreated until a system effect is actually wanted
    private fun wanted(prefs: AudioPrefs): Boolean = prefs.dspMode == DspMode.SYSTEM &&
        (prefs.eqEnabled || prefs.bassBoost > 0 || prefs.virtualizer > 0 || prefs.loudnessGain > 0)

    private fun apply(prefs: AudioPrefs) {
        if (!masterEnabled) {
            runCatching { equalizer?.setEnabled(false) }
            runCatching { bassBoost?.setEnabled(false) }
            runCatching { virtualizer?.setEnabled(false) }
            runCatching { loudness?.setEnabled(false) }
            return
        }
        if (!created && !wanted(prefs)) return
        ensureCreated()
        runCatching {
            equalizer?.let { eq ->
                eq.setEnabled(prefs.eqEnabled)
                if (prefs.eqEnabled) {
                    if (prefs.eqPreset in 0 until presets.size) {
                        eq.usePreset(prefs.eqPreset.toShort())
                    } else {
                        for (i in 0 until bands) {
                            val mb = prefs.eqBands.getOrElse(i) { 0 }.coerceIn(minMb, maxMb)
                            eq.setBandLevel(i.toShort(), mb.toShort())
                        }
                    }
                }
            }
        }
        runCatching {
            bassBoost?.let { bb ->
                bb.setEnabled(prefs.bassBoost > 0)
                if (bb.strengthSupported) bb.setStrength(prefs.bassBoost.coerceIn(0, 1000).toShort())
            }
        }
        runCatching {
            virtualizer?.let { v ->
                v.setEnabled(prefs.virtualizer > 0)
                if (v.strengthSupported) v.setStrength(prefs.virtualizer.coerceIn(0, 1000).toShort())
            }
        }
        runCatching {
            loudness?.let { l ->
                l.setEnabled(prefs.loudnessGain > 0)
                l.setTargetGain(prefs.loudnessGain.coerceIn(0, 2000))
            }
        }
    }

    fun presetBandLevels(preset: Int): List<Int> = runCatching {
        ensureCreated()
        val eq = equalizer ?: return emptyList()
        eq.usePreset(preset.toShort())
        (0 until bands).map { eq.getBandLevel(it.toShort()).toInt() }
    }.getOrDefault(List(bands) { 0 })
}
