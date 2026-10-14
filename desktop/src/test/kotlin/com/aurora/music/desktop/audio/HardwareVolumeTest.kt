package com.aurora.music.desktop.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

internal class FakeDac(
    @Volatile var db: Double = 0.0,
    @Volatile var muted: Boolean = false,
    private val minDb: Double = -127.0,
    private val maxDb: Double = 0.0,
    private val stepDb: Double = 0.0,
    private val log: MutableList<String>? = null,
) : HardwareVolume {
    @Volatile var gone = false
    val writes = CopyOnWriteArrayList<Pair<Double, Boolean>>()

    override fun read(): HardwareLevel? = if (gone) null else HardwareLevel(minDb, maxDb, db, muted)

    override fun write(db: Double, mute: Boolean): Boolean {
        if (gone) return false
        val clamped = db.coerceIn(minDb, maxDb)
        this.db = if (stepDb > 0) maxDb - ceil((maxDb - clamped) / stepDb - 1e-9) * stepDb else clamped
        muted = mute
        writes += db to mute
        log?.add("dac $db")
        return true
    }
}

class HardwareVolumeTest {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val log = CopyOnWriteArrayList<String>()
    private val software = CopyOnWriteArrayList<Double>()
    private val levels = CopyOnWriteArrayList<Double?>()
    private val followed = CopyOnWriteArrayList<Double>()
    private val driver = HardwareVolumeDriver(
        softwareGain = {
            software += it
            log += "software $it"
        },
        onLevel = { levels += it },
        onDacChange = { followed += it },
        pollMs = 20,
        worker = worker,
    )

    @After fun tearDown() {
        driver.close()
        worker.shutdownNow()
    }

    @Test fun curveMapsGainOntoTheTopSixtyDbAndNeverAboveZero() {
        val boosted = HardwareLevel(-127.0, 6.0, 0.0, false)
        assertEquals(0.0, HardwareVolumeCurve.top(boosted), 0.0)
        assertEquals(-60.0, HardwareVolumeCurve.floor(boosted), 0.0)
        assertEquals(-20.0, HardwareVolumeCurve.dbFor(0.1, boosted), 1e-9)
        assertEquals(0.0, HardwareVolumeCurve.dbFor(1.0, boosted), 1e-9)
        assertEquals(-60.0, HardwareVolumeCurve.dbFor(1e-6, boosted), 1e-9)
        assertEquals(-60.0, HardwareVolumeCurve.dbFor(0.0, boosted), 1e-9)

        val attenuator = HardwareLevel(-100.0, -10.0, -30.0, false)
        assertEquals(-10.0, HardwareVolumeCurve.top(attenuator), 0.0)
        assertEquals(-70.0, HardwareVolumeCurve.floor(attenuator), 0.0)
        assertEquals(0.1, HardwareVolumeCurve.gainFor(attenuator), 1e-9)

        val narrow = HardwareLevel(-30.0, 0.0, -45.0, false)
        assertEquals(-30.0, HardwareVolumeCurve.floor(narrow), 0.0)
        assertEquals(10.0.pow(-1.5), HardwareVolumeCurve.gainFor(narrow), 1e-9)
        assertEquals(0.0, HardwareVolumeCurve.gainFor(narrow.copy(muted = true)), 0.0)
        for (gain in listOf(0.002, 0.03, 0.4, 0.97)) {
            assertEquals(gain, HardwareVolumeCurve.gainFor(boosted.copy(db = HardwareVolumeCurve.dbFor(gain, boosted))), 1e-9)
        }
    }

    @Test fun aQuieterAppSetsTheDacBeforeSoftwareVolumeStepsAside() {
        val dac = FakeDac(db = 0.0, log = log)
        driver.setGain(0.1)
        driver.attach("dac") { dac }
        flush()
        assertTrue(driver.active)
        assertEquals(-20.0, dac.db, 1e-9)
        assertFalse(dac.muted)
        assertEquals(listOf("software 0.1", "dac -20.0", "software 1.0"), log.map { it.replace(Regex("(-?\\d+\\.\\d)\\d+"), "$1") })
        assertTrue(followed.isEmpty())
        assertEquals(-20.0, levels.last()!!, 1e-9)
    }

    @Test fun aQuieterDacKeepsItsLevelAndTheAppFollows() {
        val dac = FakeDac(db = -40.0)
        driver.setGain(0.5)
        driver.attach("dac") { dac }
        flush()
        assertTrue(dac.writes.isEmpty())
        assertEquals(0.01, followed.single(), 1e-9)
        assertEquals(1.0, software.last(), 0.0)
    }

    @Test fun appVolumeMovesTheDacAndSilenceStaysInSoftware() {
        val dac = FakeDac(db = 0.0, stepDb = 0.5)
        driver.attach("dac") { dac }
        flush()
        driver.setGain(0.3)
        flush()
        assertEquals(1.0, software.last(), 0.0)
        assertTrue(dac.db <= 20 * log10(0.3))
        assertEquals(floor(20 * log10(0.3) / 0.5) * 0.5, dac.db, 1e-9)
        assertEquals(dac.db, levels.last()!!, 1e-9)
        val writes = dac.writes.size
        driver.setGain(0.3)
        flush()
        assertEquals(writes, dac.writes.size)
        driver.setGain(0.0)
        flush()
        assertTrue(dac.muted)
        assertEquals(0.0, software.last(), 0.0)
        driver.setGain(0.5)
        flush()
        assertFalse(dac.muted)
        assertEquals(1.0, software.last(), 0.0)
    }

    @Test fun turningTheDacKnobComesBackAsAppVolume() {
        val dac = FakeDac(db = 0.0)
        driver.attach("dac") { dac }
        flush()
        dac.db = -0.3
        Thread.sleep(80)
        flush()
        assertTrue(followed.isEmpty())
        dac.db = -12.0
        eventually { followed.isNotEmpty() }
        assertEquals(10.0.pow(-12.0 / 20), followed.last(), 1e-9)
        assertTrue(dac.writes.isEmpty())
    }

    @Test fun reopeningTheSameDacKeepsItsLevelAndReleaseHandsItBack() {
        val dac = FakeDac(db = -6.0)
        driver.setGain(0.1)
        var opens = 0
        driver.attach("dac") { dac.also { opens++ } }
        driver.attach("dac") { dac.also { opens++ } }
        flush()
        assertEquals(1, opens)
        assertEquals(-20.0, dac.db, 1e-9)
        driver.release()
        flush()
        assertFalse(driver.active)
        assertEquals(-6.0, dac.db, 0.0)
        assertEquals(0.1, software.last(), 0.0)
        assertNull(levels.last())
    }

    @Test fun anotherDacRestoresTheFirst() {
        val first = FakeDac(db = -3.0)
        val second = FakeDac(db = 0.0)
        driver.setGain(0.5)
        driver.attach("a") { first }
        driver.attach("b") { second }
        flush()
        assertEquals(-3.0, first.db, 0.0)
        assertEquals(20 * log10(0.5), second.db, 1e-9)
    }

    @Test fun aVanishedDacFallsBackToSoftwareWithoutWritingIt() {
        val dac = FakeDac(db = 0.0)
        driver.setGain(0.2)
        driver.attach("dac") { dac }
        flush()
        val writes = dac.writes.size
        dac.gone = true
        eventually { !driver.active }
        assertEquals(0.2, software.last(), 0.0)
        assertEquals(writes, dac.writes.size)
    }

    @Test fun aDeviceWithoutHardwareVolumeKeepsSoftwareVolume() {
        driver.setGain(0.4)
        driver.attach("dac") { null }
        flush()
        assertFalse(driver.active)
        assertEquals(0.4, software.last(), 0.0)
        driver.setGain(0.6)
        assertEquals(0.6, software.last(), 0.0)
    }

    private fun flush() = worker.submit {}.get()
}
