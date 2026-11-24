package app.pixelferry

import app.pixelferry.core.FailReason
import app.pixelferry.core.Failure
import app.pixelferry.core.Item
import app.pixelferry.core.ItemState
import app.pixelferry.core.SourceEntry
import app.pixelferry.core.VerifyFailed
import app.pixelferry.core.classify
import java.io.IOException
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = App::class)
class DiagnosticsTest {
    private fun e(n: Int) = SourceEntry("DCIM/IMG_$n.jpg|100|$n", "IMG_$n.jpg", 100, n.toLong(), "DCIM", "$n")

    @Test fun reasons_are_classified_in_plain_words() {
        assertEquals(FailReason.REFUSED, classify(IOException("The device refused to send IMG.jpg. Keep it unlocked")))
        assertEquals(FailReason.UNPLUGGED, classify(IOException("The device was unplugged")))
        assertEquals(FailReason.CHECK_FAILED, classify(VerifyFailed("IMG.jpg: size 10 != expected 12")))
        assertEquals(FailReason.INCOMPLETE_BIG, classify(VerifyFailed("VID.mp4: over 4 GB and arrived incomplete")))
    }

    @Test fun failures_are_stored_and_drop_out_once_sent() {
        val ctx = App.instance
        val dev = "usb:pad:test"
        Diagnostics.recordFailures(ctx, dev, "Sh's OnePlus Pad 3", listOf(
            Failure(e(1), FailReason.REFUSED, "refused"), Failure(e(2), FailReason.NO_ROOM, "full")))
        assertEquals(setOf("IMG_1.jpg", "IMG_2.jpg"), Diagnostics.failed(ctx, dev).map { it.name }.toSet())
        assertTrue(java.io.File(Diagnostics.publicDir(ctx), "diagnostics.log").readText().contains("FAILED"))
        // IMG_1 gets sent on retry
        Hub.ledger.insert(Item(deviceKey = dev, itemKey = e(1).itemKey, originalName = "IMG_1.jpg", size = 100, dateTakenMs = 0,
            pixelPath = "/x", sha256 = "aa".repeat(32), state = ItemState.VERIFIED))
        Diagnostics.recordFailures(ctx, dev, "Sh's OnePlus Pad 3", emptyList())
        assertEquals(listOf("IMG_2.jpg"), Diagnostics.failed(ctx, dev).map { it.name })
        assertEquals(FailReason.NO_ROOM, Diagnostics.failed(ctx, dev).single().reason)
    }

    @Test fun friendly_device_names() {
        assertEquals("OnePlus Pad 3", DeviceNames.friendly("OPD2415"))
        assertTrue(DeviceNames.isRawCode("OPD2415"))
        assertNull(DeviceNames.friendly("iPhone"))
    }
}
