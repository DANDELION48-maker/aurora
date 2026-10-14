package com.aurora.music.desktop.audio

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

data class HardwareLevel(val minDb: Double, val maxDb: Double, val db: Double, val muted: Boolean)

// a dac's own volume control, the desktop counterpart of android's direct usb volume
interface HardwareVolume {
    fun read(): HardwareLevel?
    fun write(db: Double, mute: Boolean): Boolean
}

// the same attenuation software volume would apply, kept inside the dac's top 60 dB and never above 0 dB
object HardwareVolumeCurve {
    const val USABLE_DB = 60.0

    fun top(level: HardwareLevel): Double = if (level.maxDb > 0 && level.minDb < 0) 0.0 else level.maxDb
    fun floor(level: HardwareLevel): Double = maxOf(level.minDb, top(level) - USABLE_DB)

    fun dbFor(gain: Double, level: HardwareLevel): Double =
        if (gain <= 0.0) floor(level) else (20 * log10(gain) + top(level)).coerceIn(floor(level), top(level))

    fun gainFor(level: HardwareLevel): Double =
        if (level.muted) 0.0 else 10.0.pow((level.db.coerceIn(floor(level), top(level)) - top(level)) / 20)
}

// while an exclusive stream plays, the app's volume moves the dac and leaves the samples untouched; turns of the
// dac's own knob come back as app volume. every dac call runs on one background thread
internal class HardwareVolumeDriver(
    private val softwareGain: (Double) -> Unit,
    private val onLevel: (Double?) -> Unit,
    private val onDacChange: (Double) -> Unit,
    private val pollMs: Long = 1_000,
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "aurora-dac-volume").apply { isDaemon = true }
    },
) : AutoCloseable {
    // guards the hand-over between software gain and the dac so the two never both attenuate
    private val lock = Any()
    @Volatile private var gain = 1.0
    @Volatile var active = false
        private set

    // worker thread only
    private var device: String? = null
    private var control: HardwareVolume? = null
    private var original: HardwareLevel? = null
    private var written: HardwareLevel? = null
    private var requested: Pair<Double, Boolean>? = null
    private var polling: ScheduledFuture<*>? = null

    fun setGain(value: Double) {
        synchronized(lock) {
            gain = value
            if (!active) {
                softwareGain(value)
                return
            }
            engage()
        }
        run(::push)
    }

    // a reopen on the same dac (a new sample rate) keeps its level; a new dac starts from the quieter of the two
    fun attach(deviceId: String, open: () -> HardwareVolume?): Future<*>? = run {
        if (deviceId != device) {
            release(restore = true)
            val candidate = runCatching(open).getOrNull()
            val level = candidate?.let { runCatching { it.read() }.getOrNull() }
            if (candidate == null || level == null) {
                softwareGain(gain)
                onLevel(null)
                return@run
            }
            device = deviceId
            control = candidate
            original = level
            written = level
            val dacGain = HardwareVolumeCurve.gainFor(level)
            // a quieter dac keeps its level, so first contact never gets louder
            if (dacGain < gain) {
                synchronized(lock) { gain = dacGain }
                onDacChange(dacGain)
            } else {
                push()
            }
        } else if (control != null) {
            follow()
            push()
        }
        if (control == null) return@run
        synchronized(lock) {
            active = true
            engage()
        }
        push()
        onLevel(written?.db)
        if (polling == null) polling = worker.scheduleWithFixedDelay({ follow() }, pollMs, pollMs, TimeUnit.MILLISECONDS)
    }

    // leaving exclusive mode, changing device or turning the feature off hands the dac back as it was found; software
    // volume returns at once so nothing rendered from here on is louder than asked
    fun release() {
        disengage()
        run { release(restore = true) }
    }

    override fun close() {
        run { release(restore = true) }
        worker.shutdown()
        runCatching { worker.awaitTermination(2, TimeUnit.SECONDS) }
    }

    private fun run(task: () -> Unit): Future<*>? = runCatching { worker.submit(task) }.getOrNull()

    private fun push() {
        val control = control ?: return
        val level = runCatching { control.read() }.getOrNull() ?: return lose()
        val db = HardwareVolumeCurve.dbFor(gain, level)
        val mute = gain <= 0.0
        val last = written
        // a dac snaps to its own steps, so asking again for the same level is not a change
        val unchanged = last != null && abs(level.db - last.db) < STEP_EPSILON_DB && level.muted == last.muted &&
            requested?.let { (askedDb, askedMute) -> abs(askedDb - db) < STEP_EPSILON_DB && askedMute == mute } == true
        if (mute == level.muted && abs(db - level.db) < STEP_EPSILON_DB || unchanged) {
            written = level
            return
        }
        if (!runCatching { control.write(db, mute) }.getOrDefault(false)) return lose()
        requested = db to mute
        written = runCatching { control.read() }.getOrNull() ?: return lose()
        if (active) onLevel(written?.db)
    }

    private fun follow() {
        val control = control ?: return
        val level = runCatching { control.read() }.getOrNull() ?: return lose()
        val last = written ?: return
        if (abs(level.db - last.db) <= FOLLOW_TOLERANCE_DB && level.muted == last.muted) return
        written = level
        val dacGain = HardwareVolumeCurve.gainFor(level)
        synchronized(lock) {
            gain = dacGain
            if (active) engage()
        }
        onDacChange(dacGain)
        if (active) onLevel(level.db)
    }

    // silence stays in software too, since not every dac can mute and its floor is still audible
    private fun engage() = softwareGain(if (gain <= 0.0) 0.0 else 1.0)

    private fun disengage() = synchronized(lock) {
        if (active) {
            active = false
            softwareGain(gain)
        }
    }

    // the dac vanished or stopped answering; software volume takes over without touching it again
    private fun lose() {
        release(restore = false)
    }

    private fun release(restore: Boolean) {
        polling?.cancel(false)
        polling = null
        val previous = control
        val back = original
        device = null
        control = null
        original = null
        written = null
        requested = null
        disengage()
        if (restore && previous != null && back != null) runCatching { previous.write(back.db, back.muted) }
        onLevel(null)
    }

    private companion object {
        const val STEP_EPSILON_DB = 0.01
        const val FOLLOW_TOLERANCE_DB = 0.5
    }
}
