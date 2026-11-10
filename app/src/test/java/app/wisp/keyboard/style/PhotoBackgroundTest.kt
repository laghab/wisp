package app.wisp.keyboard.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoBackgroundTest {
    private val white = 0xFFFFFFFF.toInt()

    @Test fun darkPhotoKeepsTheRequestedDimming() {
        assertEquals(40, PhotoBackground.effectiveDim(40, 0.02, white))
    }

    @Test fun brightPhotoIsDarkenedUntilWhiteLabelsRead() {
        val d = PhotoBackground.effectiveDim(40, 0.90, white)
        assertTrue("dim $d", d > 40)
        val l = 0.90 * (1 - d / 100.0)
        assertTrue(1.05 / (l + 0.05) >= 4.5)
    }

    @Test fun dimmingIsCapped() {
        // Mid-grey labels never reach 4.5:1 on anything: the dimming stops at the cap rather than blacking the photo out.
        assertEquals(PhotoBackground.MAX_DIM, PhotoBackground.effectiveDim(0, 1.0, 0xFF808080.toInt()))
    }
}
