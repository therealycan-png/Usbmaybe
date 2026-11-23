package app.pixelferry

import app.pixelferry.core.Item
import app.pixelferry.core.ItemState
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = App::class)
class OverrideTest {
    @Test fun override_frees_the_pixel_and_makes_originals_deletable() {
        val f = kotlin.io.path.createTempFile("Sh_OPD2415_IMG", ".jpg").toFile().apply { writeBytes(ByteArray(1000) { 1 }) }
        val item = Hub.ledger.insert(Item(deviceKey = "usb:pad", itemKey = "k1", originalName = "IMG.jpg", size = 1000,
            dateTakenMs = 0, pixelPath = f.absolutePath, sha256 = "ef".repeat(32), state = ItemState.VERIFIED))
        assertEquals(listOf(item.id), Hub.stillOnPixel().map { it.id })
        assertEquals(1, Hub.overrideBackedUp(Hub.stillOnPixel()))
        assertFalse(f.exists(), "copy removed from the Pixel")
        assertEquals(ItemState.BACKED_UP, Hub.ledger.get(item.id)!!.state)
        assertEquals(listOf("k1"), Hub.ledger.deletableFor("usb:pad").map { it.itemKey }, "original can now be deleted (after the usual 2 confirms)")
        assertTrue(Hub.stillOnPixel().isEmpty())
    }
}
