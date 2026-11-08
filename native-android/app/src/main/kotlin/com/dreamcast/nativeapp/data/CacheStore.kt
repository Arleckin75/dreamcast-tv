package com.dreamcast.nativeapp.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Response cache in SQLite with retention, a stale fallback window and size budgets. */
internal class CacheStore(
    database: Lazy<SQLiteDatabase>,
    private val preferences: PreferenceStore,
    private val maxEntries: Int,
    private val maxBytes: Long,
) {
    private val database by database

    private var writes = 0
    private var lastMaintenance = 0L
    private var pendingBytes = 0L

    fun retentionHours(): Long =
        preferences.string(RETENTION_KEY).toLongOrNull()
            ?: when (preferences.string("cache.retention", "hours8")) {
                "hours12" -> 12L
                "day1" -> 24L
                "days3" -> 72L
                "days7" -> 168L
                "days30" -> 720L
                "forever" -> 0L
                else -> 8L
            }

    /** Stores the retention ([hours], 0 = forever) and reapplies it to existing entries. */
    fun retention(hours: Long) {
        preferences.put(RETENTION_KEY, hours.toString())
        if (hours == 0L) database.execSQL("UPDATE cache_entries SET expires_at=NULL")
        else
            database.execSQL(
                "UPDATE cache_entries SET expires_at=updated_at+?",
                arrayOf(hours * 3600),
            )
    }

    fun get(key: String): Storage.CacheRow? =
        database
            .rawQuery(
                "SELECT value_json,updated_at,expires_at FROM cache_entries WHERE `key`=?",
                arrayOf(key),
            )
            .use {
                if (!it.moveToFirst()) null
                else
                    Storage.CacheRow(
                        it.getString(0),
                        it.getLong(1) * 1000,
                        if (it.isNull(2)) null else it.getLong(2) * 1000,
                    )
            }

    @Synchronized
    fun put(key: String, value: String) {
        // An oversized response may still be used by the caller, but must not evict the whole
        // cache.
        val byteCount = value.toByteArray(Charsets.UTF_8).size
        if (byteCount > maxBytes) return
        val now = System.currentTimeMillis() / 1000
        val hours = retentionHours()
        val values =
            ContentValues().apply {
                put("key", key)
                put("value_json", value)
                put("updated_at", now)
                if (hours == 0L) putNull("expires_at") else put("expires_at", now + hours * 3600)
            }
        database.insertWithOnConflict("cache_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        pendingBytes += byteCount
        maintain()
    }

    /** Keep a seven-day stale fallback window; storage budgets still apply in forever mode. */
    @Synchronized
    fun maintain(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (
            !force &&
                ++writes < 32 &&
                pendingBytes < maxBytes / 16 &&
                now - lastMaintenance < 3600000L
        )
            return
        database.transaction {
            delete(
                "cache_entries",
                "expires_at IS NOT NULL AND expires_at < ?",
                arrayOf((now / 1000 - 7 * 86400).toString()),
            )
            evictOverBudget()
        }
        writes = 0
        pendingBytes = 0
        lastMaintenance = now
    }

    /** Removes the oldest entries until both the entry and byte budgets are met. */
    private fun evictOverBudget() {
        var (count, bytes) = stats()
        if (count <= maxEntries && bytes <= maxBytes) return
        val remove = mutableListOf<String>()
        database
            .rawQuery(
                "SELECT `key`,LENGTH(CAST(value_json AS BLOB)) FROM cache_entries ORDER BY updated_at ASC,rowid ASC",
                null,
            )
            .use {
                while ((count > maxEntries || bytes > maxBytes) && it.moveToNext()) {
                    remove += it.getString(0)
                    count--
                    bytes -= it.getLong(1)
                }
            }
        remove.forEach { database.delete("cache_entries", "`key`=?", arrayOf(it)) }
    }

    fun clear() {
        database.delete("cache_entries", null, null)
    }

    /** Entry count and total size in bytes. */
    fun stats(): Pair<Long, Long> =
        database
            .rawQuery(
                "SELECT COUNT(*),COALESCE(SUM(LENGTH(CAST(value_json AS BLOB))),0) FROM cache_entries",
                null,
            )
            .use {
                it.moveToFirst()
                it.getLong(0) to it.getLong(1)
            }

    fun clearExpired() {
        database.delete(
            "cache_entries",
            "expires_at IS NOT NULL AND expires_at < ?",
            arrayOf((System.currentTimeMillis() / 1000).toString()),
        )
    }

    private companion object {
        const val RETENTION_KEY = "cache.retention.native"
    }
}
