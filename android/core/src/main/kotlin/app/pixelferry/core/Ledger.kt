package app.pixelferry.core

/**
 * Life of one transferred file. Only moves forward.
 *
 *  VERIFIED        copy on Pixel, size + SHA-256 read back from disk match
 *  DUPLICATE       identical bytes already received earlier (copy discarded)
 *  BACKED_UP       Pixel copy was removed by Google Photos "Free up space"
 *                  (Google only removes what it has backed up) -> source may be deleted
 *  MISSING_UNKNOWN Pixel copy vanished outside a free-up window -> NEVER delete source
 *  SOURCE_DELETED  removed from the source device after the user's double confirm
 */
enum class ItemState { VERIFIED, DUPLICATE, BACKED_UP, MISSING_UNKNOWN, SOURCE_DELETED }

data class Item(
    val id: Long = 0,
    /** Stable id of the source device (serial / machine id). */
    val deviceKey: String,
    /** Stable id of the file on that device (path + size + date). */
    val itemKey: String,
    val originalName: String,
    val size: Long,
    val dateTakenMs: Long,
    /** Absolute path of the copy on the Pixel (null for DUPLICATE). */
    val pixelPath: String?,
    val sha256: String,
    val state: ItemState,
    val duplicateOf: Long? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class DeviceProfile(val deviceKey: String, val owner: String, val label: String)

interface Ledger {
    fun device(deviceKey: String): DeviceProfile?
    fun saveDevice(p: DeviceProfile)

    fun find(deviceKey: String, itemKey: String): Item?
    fun findByHash(sha256: String): Item?
    fun get(id: Long): Item?
    fun insert(item: Item): Item
    fun setState(id: Long, state: ItemState)
    /** Only used to replace a MISSING_UNKNOWN row when the file is sent again. */
    fun remove(id: Long)
    fun byState(state: ItemState): List<Item>
    fun forDevice(deviceKey: String): List<Item>

    /** Can this item's source copy be deleted? Only once Google has it. */
    fun isSafeToDeleteFromSource(item: Item): Boolean = when (item.state) {
        ItemState.BACKED_UP -> true
        ItemState.DUPLICATE -> item.duplicateOf?.let { get(it) }?.let {
            it.state == ItemState.BACKED_UP || it.state == ItemState.SOURCE_DELETED
        } ?: false
        else -> false
    }

    /**
     * Is this file really finished? A copy that vanished without Google freeing it is NOT,
     * and neither is a duplicate whose original vanished: those get sent again.
     */
    fun isDone(item: Item): Boolean = when (item.state) {
        ItemState.MISSING_UNKNOWN -> false
        ItemState.DUPLICATE -> item.duplicateOf?.let { get(it) }?.let { isDone(it) } ?: false
        else -> true
    }

    fun deletableFor(deviceKey: String): List<Item> = forDevice(deviceKey).filter { isSafeToDeleteFromSource(it) }
}

/** Thread-safe in-memory ledger (tests + desktop tooling). Android uses SqliteLedger. */
class MemoryLedger : Ledger {
    private val items = LinkedHashMap<Long, Item>()
    private val devices = HashMap<String, DeviceProfile>()
    private var next = 1L

    @Synchronized override fun device(deviceKey: String) = devices[deviceKey]
    @Synchronized override fun saveDevice(p: DeviceProfile) { devices[p.deviceKey] = p }
    @Synchronized override fun find(deviceKey: String, itemKey: String) =
        items.values.firstOrNull { it.deviceKey == deviceKey && it.itemKey == itemKey }
    @Synchronized override fun findByHash(sha256: String) =
        items.values.firstOrNull { it.sha256 == sha256 && it.state != ItemState.DUPLICATE && it.state != ItemState.MISSING_UNKNOWN }
    @Synchronized override fun get(id: Long) = items[id]
    @Synchronized override fun insert(item: Item): Item {
        val i = item.copy(id = next++); items[i.id] = i; return i
    }
    @Synchronized override fun setState(id: Long, state: ItemState) {
        items[id]?.let { items[id] = it.copy(state = state, updatedAtMs = System.currentTimeMillis()) }
    }
    @Synchronized override fun remove(id: Long) { items.remove(id) }
    @Synchronized override fun byState(state: ItemState) = items.values.filter { it.state == state }
    @Synchronized override fun forDevice(deviceKey: String) = items.values.filter { it.deviceKey == deviceKey }
}
