package app.pixelferry

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.pixelferry.core.DeviceProfile
import app.pixelferry.core.Item
import app.pixelferry.core.ItemState
import app.pixelferry.core.Ledger

/** Durable record of every verified file. Survives crashes, reboots, unplugging. */
class SqliteLedger(ctx: Context) : SQLiteOpenHelper(ctx, "ledger.db", null, 1), Ledger {
    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE items(id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL, key TEXT NOT NULL,
            name TEXT NOT NULL, size INTEGER NOT NULL, taken INTEGER NOT NULL, path TEXT, sha TEXT NOT NULL,
            state TEXT NOT NULL, dup INTEGER, updated INTEGER NOT NULL, UNIQUE(device, key))""")
        db.execSQL("CREATE INDEX items_sha ON items(sha)")
        db.execSQL("CREATE INDEX items_state ON items(state)")
        db.execSQL("CREATE TABLE devices(device TEXT PRIMARY KEY, owner TEXT NOT NULL, label TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun Cursor.item() = Item(
        id = getLong(0), deviceKey = getString(1), itemKey = getString(2), originalName = getString(3),
        size = getLong(4), dateTakenMs = getLong(5), pixelPath = if (isNull(6)) null else getString(6),
        sha256 = getString(7), state = ItemState.valueOf(getString(8)),
        duplicateOf = if (isNull(9)) null else getLong(9), updatedAtMs = getLong(10),
    )

    private val cols = "id,device,key,name,size,taken,path,sha,state,dup,updated"

    private fun query(where: String, vararg args: String): List<Item> =
        readableDatabase.rawQuery("SELECT $cols FROM items WHERE $where ORDER BY id", args).use { c ->
            val out = ArrayList<Item>(c.count); while (c.moveToNext()) out += c.item(); out
        }

    override fun device(deviceKey: String): DeviceProfile? =
        readableDatabase.rawQuery("SELECT owner,label FROM devices WHERE device=?", arrayOf(deviceKey)).use {
            if (it.moveToFirst()) DeviceProfile(deviceKey, it.getString(0), it.getString(1)) else null
        }

    override fun saveDevice(p: DeviceProfile) {
        writableDatabase.insertWithOnConflict("devices", null, ContentValues().apply {
            put("device", p.deviceKey); put("owner", p.owner); put("label", p.label)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun devices(): List<DeviceProfile> =
        readableDatabase.rawQuery("SELECT device,owner,label FROM devices", null).use { c ->
            val out = ArrayList<DeviceProfile>(); while (c.moveToNext()) out += DeviceProfile(c.getString(0), c.getString(1), c.getString(2)); out
        }

    override fun find(deviceKey: String, itemKey: String) = query("device=? AND key=?", deviceKey, itemKey).firstOrNull()
    override fun findByHash(sha256: String) = query("sha=? AND state NOT IN ('DUPLICATE','MISSING_UNKNOWN')", sha256).firstOrNull()
    override fun remove(id: Long) { writableDatabase.delete("items", "id=?", arrayOf(id.toString())) }
    override fun get(id: Long) = query("id=?", id.toString()).firstOrNull()
    override fun byState(state: ItemState) = query("state=?", state.name)
    override fun forDevice(deviceKey: String) = query("device=?", deviceKey)

    /** Fast lookup of which keys of a device are already done (for resume). */
    fun keysFor(deviceKey: String): Set<String> =
        readableDatabase.rawQuery("SELECT key FROM items WHERE device=?", arrayOf(deviceKey)).use { c ->
            val s = HashSet<String>(c.count); while (c.moveToNext()) s += c.getString(0); s
        }

    fun countState(state: ItemState): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM items WHERE state=?", arrayOf(state.name)).use { it.moveToFirst(); it.getInt(0) }

    override fun insert(item: Item): Item {
        val id = writableDatabase.insertOrThrow("items", null, ContentValues().apply {
            put("device", item.deviceKey); put("key", item.itemKey); put("name", item.originalName)
            put("size", item.size); put("taken", item.dateTakenMs); put("path", item.pixelPath); put("sha", item.sha256)
            put("state", item.state.name); put("dup", item.duplicateOf); put("updated", System.currentTimeMillis())
        })
        return item.copy(id = id)
    }

    override fun setState(id: Long, state: ItemState) {
        writableDatabase.update("items", ContentValues().apply {
            put("state", state.name); put("updated", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }
}
