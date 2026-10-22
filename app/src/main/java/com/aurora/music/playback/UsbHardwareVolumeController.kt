package com.aurora.music.playback

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbHardwareVolume
import java.util.concurrent.Executors

// direct usb: the phone's media volume drives the dac's own volume and follows changes made on the dac
class UsbHardwareVolumeController(context: Context) {
    private val usb = UsbAudioDevice.getInstance(context)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = context.getSharedPreferences("usb_hardware_volume", Context.MODE_PRIVATE)
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
    private var connectedAt = 0L

    fun onMediaVolumeChanged() { if (enabled) submit(::apply) }
    fun poll() { if (enabled) submit(::follow) }
    fun close() { worker.shutdownNow() }

    private fun submit(task: () -> Unit) { runCatching { worker.execute(task) } }

    private fun follow() {
        val before = state
        val hw = usb.readHardwareVolume() ?: return forget()
        state = hw
        if (hw.current == level && hw.muted == muted) return
        val first = level == null
        level = hw.current
        muted = hw.muted
        val max = maxIndex()
        val wanted = indexFor(hw.fraction, max)
        val phone = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (first) {
            connectedAt = SystemClock.elapsedRealtime()
            val start = startIndex(phone, prefs.getInt(SAVED_INDEX, -1).takeIf { it in 0..max }, wanted)
            if (start != phone) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, start, 0) }
            return push(audio.getStreamVolume(AudioManager.STREAM_MUSIC), max)
        }
        if (SystemClock.elapsedRealtime() - connectedAt < SETTLE_MS || isReset(before, hw)) return push(phone, max)
        if (phone != wanted) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, wanted, 0) }
        remember(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    private fun apply() {
        if (level == null || state == null) return follow()
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (now != index) push(now, maxIndex())
    }

    private fun push(phone: Int, max: Int) {
        val hw = state ?: return
        if (!usb.setHardwareVolume(hw.levelFor(phone.toDouble() / max), phone == 0)) return forget()
        remember(phone)
        val after = usb.readHardwareVolume() ?: return forget()
        state = after
        level = after.current
        muted = after.muted
    }

    private fun remember(phone: Int) {
        index = phone
        prefs.edit().putInt(SAVED_INDEX, phone).apply()
    }

    private fun maxIndex() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    private fun forget() {
        state = null
        level = null
        muted = false
        index = -1
    }

    companion object {
        private const val SAVED_INDEX = "media_index"
        // dacs often power up at full volume and some reset again once the stream's sample rate is set
        private const val SETTLE_MS = 4_000L

        fun indexFor(fraction: Double, max: Int): Int =
            if (fraction <= 0.0) 0 else Math.round(fraction * max).toInt().coerceIn(1, max)

        // a reconnect settles on the quietest of the phone, the level last used with the dac and the dac itself;
        // android also refuses to raise a headset past its safe level, so starting low is the only safe direction
        fun startIndex(phone: Int, saved: Int?, dac: Int): Int = minOf(phone, saved ?: phone, dac)

        // a dac that jumps from well below to its very top was reset, not turned up by hand
        fun isReset(before: UsbHardwareVolume?, after: UsbHardwareVolume): Boolean =
            before != null && !after.muted && after.fraction >= 0.999 && before.fraction < 0.9
    }
}
