package com.filmax.core.network

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.filmax.core.domain.cache.ItemCacheTtl
import com.filmax.core.domain.cache.ItemDetailsCache
import com.filmax.core.domain.cache.ItemDetailsCacheAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DB_NAME = "filmax_item_cache.db"
private const val DB_VERSION = 1
private const val TABLE_ENTRIES = "entries"
private const val TABLE_META = "meta"
private const val META_KEY_TTL = "ttl"
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

private const val MAX_ENTRIES = 2000

class ItemDetailsCacheDb(context: Context) : ItemDetailsCache {
    private val dbHelper = Helper(context.applicationContext)

    private val ttlState = MutableStateFlow(ItemCacheTtl.MONTH)
    private val countState = MutableStateFlow(0)

    override val ttl: StateFlow<ItemCacheTtl> = ttlState.asStateFlow()
    override val count: StateFlow<Int> = countState.asStateFlow()

    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + writeDispatcher)

    private val initDeferred: Deferred<Unit> = scope.async { performInit() }

    init {
        ItemDetailsCacheAccess.cache = this
    }

    private suspend fun performInit() {
        val db = dbHelper.writableDatabase

        val loadedTtl = db.query(
            TABLE_META,
            arrayOf("value"),
            "key = ?",
            arrayOf(META_KEY_TTL),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                runCatching { ItemCacheTtl.valueOf(cursor.getString(0)) }.getOrNull()
            } else {
                null
            }
        } ?: ItemCacheTtl.MONTH
        ttlState.value = loadedTtl

        val maxAgeDays = loadedTtl.days
        if (maxAgeDays != null) {
            val horizon = currentTimeMillis() - maxAgeDays * MILLIS_PER_DAY
            db.delete(TABLE_ENTRIES, "cached_at < ?", arrayOf(horizon.toString()))
        }

        val total = countRows(db)
        if (total > MAX_ENTRIES) {
            val overflow = total - MAX_ENTRIES
            db.execSQL(
                "DELETE FROM $TABLE_ENTRIES WHERE key IN " +
                    "(SELECT key FROM $TABLE_ENTRIES ORDER BY cached_at ASC LIMIT $overflow)",
            )
        }

        countState.value = countRows(db)
    }

    private fun countRows(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_ENTRIES", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    override suspend fun get(key: String): String? {
        initDeferred.await()
        val maxAgeDays = ttlState.value.days ?: return null
        val row = readRow(key)
        return when {
            row == null -> null
            currentTimeMillis() - row.second <= maxAgeDays * MILLIS_PER_DAY -> row.first
            else -> {
                deleteRow(key)
                null
            }
        }
    }

    private suspend fun readRow(key: String): Pair<String, Long>? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            TABLE_ENTRIES,
            arrayOf("json", "cached_at"),
            "key = ?",
            arrayOf(key),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null
        }
    }

    override fun remember(key: String, json: String) {
        scope.launch {
            initDeferred.await()
            if (ttlState.value == ItemCacheTtl.NEVER) return@launch

            val db = dbHelper.writableDatabase
            val exists = db.query(
                TABLE_ENTRIES,
                arrayOf("key"),
                "key = ?",
                arrayOf(key),
                null,
                null,
                null,
                "1",
            ).use { it.moveToFirst() }

            val values = ContentValues().apply {
                put("key", key)
                put("json", json)
                put("cached_at", currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE_ENTRIES, null, values, SQLiteDatabase.CONFLICT_REPLACE)

            if (!exists) {
                countState.value += 1
            }
        }
    }

    override fun rememberIfAbsent(key: String, json: String) {
        scope.launch {
            initDeferred.await()
            if (ttlState.value == ItemCacheTtl.NEVER) return@launch

            val values = ContentValues().apply {
                put("key", key)
                put("json", json)
                put("cached_at", currentTimeMillis())
            }
            val inserted = dbHelper.writableDatabase.insertWithOnConflict(
                TABLE_ENTRIES,
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (inserted != -1L) countState.value += 1
        }
    }

    override suspend fun remove(key: String) {
        initDeferred.await()
        deleteRow(key)
    }

    private suspend fun deleteRow(key: String) {
        withContext(writeDispatcher) {
            val deleted = dbHelper.writableDatabase.delete(TABLE_ENTRIES, "key = ?", arrayOf(key))
            if (deleted > 0) {
                countState.value = (countState.value - 1).coerceAtLeast(0)
            }
        }
    }

    override suspend fun setTtl(ttl: ItemCacheTtl) {
        initDeferred.await()
        withContext(writeDispatcher) {
            val values = ContentValues().apply {
                put("key", META_KEY_TTL)
                put("value", ttl.name)
            }
            dbHelper.writableDatabase.insertWithOnConflict(TABLE_META, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
        ttlState.value = ttl
    }

    override suspend fun clear() {
        initDeferred.await()
        withContext(writeDispatcher) {
            dbHelper.writableDatabase.delete(TABLE_ENTRIES, null, null)
        }
        countState.value = 0
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_ENTRIES (" +
                    "key TEXT PRIMARY KEY, json TEXT NOT NULL, cached_at INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX idx_entries_cached_at ON $TABLE_ENTRIES(cached_at)")
            db.execSQL("CREATE TABLE $TABLE_META (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_ENTRIES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_META")
            onCreate(db)
        }
    }
}
