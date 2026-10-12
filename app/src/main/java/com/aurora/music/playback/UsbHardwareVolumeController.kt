package com.aurora.music.playback

import android.content.Context
import android.media.AudioManager
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbHardwareVolume
import java.util.concurrent.Executors

// direct usb: the phone's media volume drives the dac's own volume and follows changes made on the dac
class UsbHardwareVolumeController(context: Context) {
    private val usb = UsbAudioDevice.getInstance(context)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "aurora-usb-volume") }
    @Volatile var enabled = false
        set(value) {
            if (field == value) return
            field = value
            if (value) poll() else submit(::forget)
        }
    @Volatile var state: UsbHardwareVolume? = null
        private set
    // worker thread only
    private var level: Int? = null
    private var muted = false
    private var index = -1

    fun onMediaVolumeChanged() { if (enabled) submit(::apply) }
    fun poll() { if (enabled) submit(::follow) }
    fun close() { worker.shutdownNow() }

    private fun submit(task: () -> Unit) { runCatching { worker.execute(task) } }

    private fun follow() {
        val hw = usb.readHardwareVolume() ?: return forget()
        state = hw
        if (hw.current == level && hw.muted == muted) return
        level = hw.current
        muted = hw.muted
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        index = if (hw.fraction <= 0.0) 0 else Math.round(hw.fraction * max).toInt().coerceIn(1, max)
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != index)
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
    }

    private fun apply() {
        // the dac's level wins on first contact so connecting never jumps louder
        val hw = state
        if (level == null || hw == null) return follow()
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (now == index) return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        if (!usb.setHardwareVolume(hw.levelFor(now.toDouble() / max), now == 0)) return forget()
        index = now
        val after = usb.readHardwareVolume() ?: return forget()
        state = after
        level = after.current
        muted = after.muted
    }

    private fun forget() {
        state = null
        level = null
        muted = false
        index = -1
    }
}
