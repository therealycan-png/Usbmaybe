package app.pixelferry

import org.robolectric.RuntimeEnvironment
import app.pixelferry.core.DeviceProfile
import app.pixelferry.core.Item
import app.pixelferry.core.ItemState
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class SqliteLedgerTest {
    private fun ledger() = SqliteLedger(RuntimeEnvironment.getApplication())

    @Test fun roundtrip_and_safety_rules() {
        val l = ledger()
        l.saveDevice(DeviceProfile("d1", "Mom", "iPhone13"))
        assertEquals("Mom", l.device("d1")!!.owner)
        val a = l.insert(Item(deviceKey = "d1", itemKey = "k1", originalName = "IMG_1.HEIC", size = 123, dateTakenMs = 99,
            pixelPath = "/sdcard/DCIM/PixelFerry/x", sha256 = "ab".repeat(32), state = ItemState.VERIFIED))
        val dup = l.insert(Item(deviceKey = "d2", itemKey = "k9", originalName = "c.HEIC", size = 123, dateTakenMs = 0,
            pixelPath = null, sha256 = "ab".repeat(32), state = ItemState.DUPLICATE, duplicateOf = a.id))
        val got = l.find("d1", "k1")!!
        assertEquals(a.copy(updatedAtMs = got.updatedAtMs), got)
        assertEquals(a.id, l.findByHash("ab".repeat(32))!!.id, "duplicates never become the original")
        assertFalse(l.isSafeToDeleteFromSource(got))
        assertFalse(l.isSafeToDeleteFromSource(l.get(dup.id)!!))
        l.setState(a.id, ItemState.BACKED_UP)
        assertTrue(l.isSafeToDeleteFromSource(l.get(a.id)!!))
        assertTrue(l.isSafeToDeleteFromSource(l.get(dup.id)!!))
        assertEquals(setOf("k1"), l.keysFor("d1"))
        assertEquals(1, l.countState(ItemState.BACKED_UP))
        assertFailsWith<Exception>("same file twice is impossible") {
            l.insert(a.copy(id = 0))
        }
    }

    @Test fun lost_copies_are_not_dedupe_targets_and_can_be_replaced() {
        val l = ledger()
        val a = l.insert(Item(deviceKey = "d", itemKey = "k", originalName = "a.jpg", size = 1, dateTakenMs = 0,
            pixelPath = "/x", sha256 = "cd".repeat(32), state = ItemState.VERIFIED))
        l.setState(a.id, ItemState.MISSING_UNKNOWN)
        assertNull(l.findByHash("cd".repeat(32)))
        assertFalse(l.isDone(l.get(a.id)!!))
        l.remove(a.id)
        assertNull(l.find("d", "k"))
        l.insert(a.copy(id = 0, state = ItemState.VERIFIED)) // re-sent copy fits the unique key again
        assertTrue(l.isDone(l.find("d", "k")!!))
    }
}
