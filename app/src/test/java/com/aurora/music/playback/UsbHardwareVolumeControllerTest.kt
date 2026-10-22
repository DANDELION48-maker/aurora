package com.aurora.music.playback

import com.decent.usbaudio.UsbHardwareVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbHardwareVolumeControllerTest {
    // a typical feature unit: -127 dB .. 0 dB in 1/256 dB steps
    private fun dac(db: Double, muted: Boolean = false) = UsbHardwareVolume(-127 * 256, 0, 256, (db * 256).toInt(), muted)

    @Test fun aReconnectRestoresTheLevelLastUsedWithTheDac() {
        assertEquals(7, UsbHardwareVolumeController.startIndex(phone = 15, saved = 7, dac = 15))
    }

    @Test fun aReconnectNeverStartsLouderThanThePhoneOrTheDac() {
        assertEquals(4, UsbHardwareVolumeController.startIndex(phone = 4, saved = 9, dac = 15))
        assertEquals(3, UsbHardwareVolumeController.startIndex(phone = 10, saved = 9, dac = 3))
        assertEquals(10, UsbHardwareVolumeController.startIndex(phone = 10, saved = null, dac = 15))
    }

    @Test fun aDacJumpingToItsTopIsAResetNotAHandTurn() {
        assertTrue(UsbHardwareVolumeController.isReset(dac(-30.0), dac(0.0)))
        assertFalse(UsbHardwareVolumeController.isReset(dac(-1.0), dac(0.0)))
        assertFalse(UsbHardwareVolumeController.isReset(dac(-30.0), dac(-20.0)))
        assertFalse(UsbHardwareVolumeController.isReset(null, dac(0.0)))
        assertFalse(UsbHardwareVolumeController.isReset(dac(-30.0), dac(0.0, muted = true)))
    }

    @Test fun dacLevelsMapOntoTheMediaVolumeSteps() {
        assertEquals(15, UsbHardwareVolumeController.indexFor(1.0, 15))
        assertEquals(0, UsbHardwareVolumeController.indexFor(0.0, 15))
        assertEquals(1, UsbHardwareVolumeController.indexFor(0.01, 15))
    }
}
