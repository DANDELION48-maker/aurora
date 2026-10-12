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
        val first = level == null
        level = hw.current
        muted = hw.muted
        val max = maxIndex()
        val wanted = if (hw.fraction <= 0.0) 0 else Math.round(hw.fraction * max).toInt().coerceIn(1, max)
        val phone = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        // first contact settles on the quieter side, android also refuses to raise a headset past its safe level
        if (first && phone < wanted) return push(phone, max)
        if (phone != wanted) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, wanted, 0) }
        index = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun apply() {
        if (level == null || state == null) return follow()
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (now != index) push(now, maxIndex())
    }

    private fun push(phone: Int, max: Int) {
        val hw = state ?: return
        if (!usb.setHardwareVolume(hw.levelFor(phone.toDouble() / max), phone == 0)) return forget()
        index = phone
        val after = usb.readHardwareVolume() ?: return forget()
        state = after
        level = after.current
        muted = after.muted
    }

    private fun maxIndex() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    private fun forget() {
        state = null
        level = null
        muted = false
        index = -1
    }
}
