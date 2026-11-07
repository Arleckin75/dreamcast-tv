package com.dreamcast.nativeapp.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Native preferences and the application SQLite database. */
class Storage(
    context: Context,
    private val maxCacheEntries: Int = 512,
    private val maxCacheBytes: Long = 16 * 1024 * 1024,
) {
    init {
        require(maxCacheEntries > 0 && maxCacheBytes > 0)
        deleteLegacyFlutterPreferences(context)
    }

    private val nativePreferences =
        context.getSharedPreferences("dream_cast_native", Context.MODE_PRIVATE)
    private val databaseDelegate = lazy { openDatabase(context.applicationContext) }
    private val database: SQLiteDatabase by databaseDelegate

    fun close() {
        if (databaseDelegate.isInitialized()) database.close()
    }

    private fun openDatabase(context: Context): SQLiteDatabase {
        // The path is inherited from the Flutter app. Moving it would lose the
        // watch history and caches of existing 0.9.x installs, so it stays.
        val directory = File(context.applicationInfo.dataDir, "app_flutter").apply { mkdirs() }
        val database =
            SQLiteDatabase.openOrCreateDatabase(File(directory, "dream_cast.sqlite"), null)
        database.rawQuery("PRAGMA busy_timeout=15000", null).use { it.moveToFirst() }
        database.enableWriteAheadLogging()
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS cache_entries (`key` TEXT NOT NULL PRIMARY KEY, value_json TEXT NOT NULL, updated_at INTEGER NOT NULL, expires_at INTEGER, etag TEXT)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS profiles (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, is_active INTEGER NOT NULL DEFAULT 0)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS playback_positions (release_id TEXT NOT NULL, episode_id TEXT NOT NULL, position_ms INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER, updated_at INTEGER NOT NULL, PRIMARY KEY(release_id,episode_id))"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS watch_entries (release_id TEXT NOT NULL, episode_id TEXT NOT NULL, release_title TEXT NOT NULL, episode_title TEXT NOT NULL, poster_url TEXT, episode_ordinal INTEGER NOT NULL, position_ms INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER, is_watched INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, PRIMARY KEY(release_id,episode_id))"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS native_watch_recent ON watch_entries(updated_at DESC)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS native_watch_release_recent ON watch_entries(release_id,updated_at DESC)"
        )
        return database
    }

    fun string(key: String, default: String = ""): String {
        return try {
            nativePreferences.getString(key, default) ?: default
        } catch (_: ClassCastException) {
            default
        }
    }

    fun number(key: String, default: Long = 0): Long {
        if (!nativePreferences.contains(key)) return default
        try {
            return nativePreferences.getLong(key, default)
        } catch (_: ClassCastException) {}
        try {
            return nativePreferences.getInt(key, 0).toLong()
        } catch (_: ClassCastException) {}
        try {
            return nativePreferences.getFloat(key, 0f).toLong()
        } catch (_: ClassCastException) {}
        return try {
            nativePreferences.getString(key, null)?.toLongOrNull() ?: default
        } catch (_: ClassCastException) {
            default
        }
    }

    fun boolean(key: String, default: Boolean = false) =
        nativePreferences.getBoolean(key, default)

    fun retentionHours(): Long =
        string("cache.retention.native").toLongOrNull()
            ?: when (string("cache.retention", "hours8")) {
                "hours12" -> 12L
                "day1" -> 24L
                "days3" -> 72L
                "days7" -> 168L
                "days30" -> 720L
                "forever" -> 0L
                else -> 8L
            }

    fun put(key: String, value: String) {
        nativePreferences.edit().putString(key, value).apply()
    }

    fun put(key: String, value: Boolean) {
        nativePreferences.edit().putBoolean(key, value).apply()
    }

    fun remove(key: String) {
        nativePreferences.edit().remove(key).apply()
    }

    fun recordSnapshot(): Map<String, Any?> = nativePreferences.all.toMap()

    fun records(
        prefix: String,
        snapshot: Map<String, Any?> = recordSnapshot(),
    ): List<Pair<String, JSONObject>> {
        return snapshot
            .asSequence()
            .filter { it.key.startsWith(prefix) }
            .mapNotNull { (key, value) ->
                runCatching { key to JSONObject(value as String) }.getOrNull()
            }
            .toList()
    }

    fun bookmarks(snapshot: Map<String, Any?> = recordSnapshot()): List<Bookmark> =
        records("library.release.item.", snapshot)
            .mapNotNull { (_, json) ->
                runCatching {
                        Bookmark(
                            Release.fromJson(json.getJSONObject("release")),
                            LibraryStatus.valueOf(json.getString("status")),
                        )
                    }
                    .getOrNull()
            }
            .sortedBy { it.release.title }

    fun bookmark(release: Release, status: LibraryStatus?) {
        if (status == null) {
            remove("library.release.item.${release.id}")
            remove("library.release.status.${release.id}")
            return
        }
        nativePreferences
            .edit()
            .putString("library.release.status.${release.id}", status.name)
            .putString(
                "library.release.item.${release.id}",
                JSONObject()
                    .put("status", status.name)
                    .put("release", release.json())
                    .put("updatedAt", Instant.now().toString())
                    .toString(),
            )
            .apply()
    }

    fun subscribed(id: Int) = string("notifications.release.item.$id").isNotEmpty()

    fun subscribe(release: Release, count: Int) {
        put(
            "notifications.release.item.${release.id}",
            JSONObject()
                .put("release", release.json())
                .put("lastKnownEpisodes", count)
                .put("updatedAt", Instant.now().toString())
                .toString(),
        )
    }

    fun searchHistory(): List<String> =
        runCatching {
                val array = JSONArray(nativePreferences.getString("search.history", null) ?: "[]")
                (0 until array.length()).map { array.getString(it) }
            }
            .getOrDefault(emptyList())

    fun saveSearch(query: String) {
        put(
            "search.history",
            JSONArray(
                    (listOf(query) + searchHistory().filterNot { it.equals(query, true) }).take(12)
                )
                .toString(),
        )
    }

    fun removeSearch(query: String) {
        put("search.history", JSONArray(searchHistory().filterNot { it == query }).toString())
    }

    fun profile(): String =
        database.rawQuery("SELECT name FROM profiles WHERE is_active=1 LIMIT 1", null).use {
            if (it.moveToFirst()) it.getString(0) else ""
        }

    fun saveProfile(name: String) {
        require(name.trim().length in 1..32)
        val now = System.currentTimeMillis() / 1000
        database.beginTransaction()
        try {
            val id =
                database.rawQuery("SELECT id FROM profiles WHERE is_active=1 LIMIT 1", null).use {
                    if (it.moveToFirst()) it.getString(0) else "profile_$now"
                }
            database.execSQL("UPDATE profiles SET is_active=0")
            val created =
                database.rawQuery("SELECT created_at FROM profiles WHERE id=?", arrayOf(id)).use {
                    if (it.moveToFirst()) it.getLong(0) else now
                }
            database.insertWithOnConflict(
                "profiles",
                null,
                ContentValues().apply {
                    put("id", id)
                    put("name", name.trim())
                    put("created_at", created)
                    put("updated_at", now)
                    put("is_active", 1)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    data class CacheRow(val value: String, val updatedAt: Long, val expiresAt: Long?)

    fun cache(key: String): CacheRow? =
        database
            .rawQuery(
                "SELECT value_json,updated_at,expires_at FROM cache_entries WHERE `key`=?",
                arrayOf(key),
            )
            .use {
                if (!it.moveToFirst()) null
                else
                    CacheRow(
                        it.getString(0),
                        it.getLong(1) * 1000,
                        if (it.isNull(2)) null else it.getLong(2) * 1000,
                    )
            }

    @Synchronized
    fun putCache(key: String, value: String) {
        // An oversized response may still be used by the caller, but must not evict the whole
        // cache.
        val byteCount = value.toByteArray(Charsets.UTF_8).size
        if (byteCount > maxCacheBytes) return
        val now = System.currentTimeMillis() / 1000
        val hours = retentionHours()
        val values =
            ContentValues().apply {
                put("key", key)
                put("value_json", value)
                put("updated_at", now)
                if (hours == 0L) putNull("expires_at") else put("expires_at", now + hours * 3600)
            }
        database.insertWithOnConflict(
            "cache_entries",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        pendingCacheBytes += byteCount
        maintainCache()
    }

    private var cacheWrites = 0
    private var lastCacheMaintenance = 0L
    private var pendingCacheBytes = 0L

    /** Keep a seven-day stale fallback window; storage budgets still apply in forever mode. */
    @Synchronized
    fun maintainCache(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (
            !force &&
                ++cacheWrites < 32 &&
                pendingCacheBytes < maxCacheBytes / 16 &&
                now - lastCacheMaintenance < 3600000L
        )
            return
        database.beginTransaction()
        try {
            database.delete(
                "cache_entries",
                "expires_at IS NOT NULL AND expires_at < ?",
                arrayOf((now / 1000 - 7 * 86400).toString()),
            )
            val (count, bytes) = cacheStats()
            var remainingCount = count
            var remainingBytes = bytes
            if (remainingCount > maxCacheEntries || remainingBytes > maxCacheBytes) {
                val remove = mutableListOf<String>()
                database
                    .rawQuery(
                        "SELECT `key`,LENGTH(CAST(value_json AS BLOB)) FROM cache_entries ORDER BY updated_at ASC,rowid ASC",
                        null,
                    )
                    .use {
                        while (
                            (remainingCount > maxCacheEntries || remainingBytes > maxCacheBytes) &&
                                it.moveToNext()
                        ) {
                            remove += it.getString(0)
                            remainingCount--
                            remainingBytes -= it.getLong(1)
                        }
                    }
                remove.forEach { database.delete("cache_entries", "`key`=?", arrayOf(it)) }
            }
            database.setTransactionSuccessful()
            cacheWrites = 0
            pendingCacheBytes = 0
            lastCacheMaintenance = now
        } finally {
            database.endTransaction()
        }
    }

    fun clearCache() {
        database.delete("cache_entries", null, null)
    }

    fun cacheStats(): Pair<Long, Long> =
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

    fun retention(hours: Long) {
        put("cache.retention.native", hours.toString())
        if (hours == 0L) database.execSQL("UPDATE cache_entries SET expires_at=NULL")
        else
            database.execSQL(
                "UPDATE cache_entries SET expires_at=updated_at+?",
                arrayOf(hours * 3600),
            )
    }

    fun progress(releaseId: Int, episodeId: String): WatchProgress? {
        database
            .rawQuery(
                "SELECT release_title,episode_title,poster_url,episode_ordinal,position_ms,duration_ms,is_watched,updated_at FROM watch_entries WHERE release_id=? AND episode_id=?",
                arrayOf(releaseId.toString(), episodeId),
            )
            .use {
                if (it.moveToFirst())
                    return WatchProgress(
                        Release(releaseId, it.getString(0), posterUrl = it.getString(2).orEmpty()),
                        episodeId,
                        it.getString(1),
                        it.getInt(3),
                        it.getLong(4),
                        it.getLong(5),
                        it.getInt(6) != 0,
                        it.getLong(7) * 1000,
                    )
            }
        return database
            .rawQuery(
                "SELECT position_ms,duration_ms,updated_at FROM playback_positions WHERE release_id=? AND episode_id=?",
                arrayOf(releaseId.toString(), episodeId),
            )
            .use {
                if (!it.moveToFirst()) null
                else
                    WatchProgress(
                        Release(releaseId, ""),
                        episodeId,
                        "",
                        0,
                        it.getLong(0),
                        it.getLong(1),
                        false,
                        it.getLong(2) * 1000,
                    )
            }
    }

    fun syncSoftwareProgress(context: Context) {
        val softwareProgress = context.getSharedPreferences("dream_cast_vlc_progress", Context.MODE_PRIVATE)
        val values = softwareProgress.all
        for ((timestampKey, value) in values) {
            if (!timestampKey.endsWith(":updatedAt") || value !is Long) continue
            val key = timestampKey.removeSuffix(":updatedAt")
            val position = values[key] as? Long ?: continue
            if (value <= number("progress.updatedAt.$key")) continue
            val releaseId = key.substringBefore(':').toIntOrNull() ?: continue
            val entry = progress(releaseId, key.substringAfter(':')) ?: continue
            if (value > number("progress.updatedAt.$key", entry.updatedAt)) {
                saveProgress(
                    entry.release,
                    Episode(
                        entry.episodeId,
                        entry.release.id,
                        entry.ordinal,
                        entry.episodeTitle,
                        "",
                    ),
                    position,
                    entry.durationMs,
                )
            }
        }
    }

    fun continueProgress(limit: Int = 10): List<WatchProgress> =
        queryProgress(
            "WHERE is_watched=0 AND NOT EXISTS (SELECT 1 FROM watch_entries newer WHERE newer.release_id=w.release_id AND (newer.updated_at>w.updated_at OR (newer.updated_at=w.updated_at AND newer.rowid>w.rowid)))",
            emptyArray(),
            limit,
        )

    fun releaseProgress(releaseId: Int): List<WatchProgress> =
        queryProgress("WHERE release_id=?", arrayOf(releaseId.toString()))

    fun recentProgress(): List<WatchProgress> = queryProgress()

    private fun queryProgress(
        where: String = "",
        args: Array<String> = emptyArray(),
        limit: Int? = null,
    ): List<WatchProgress> =
        database
            .rawQuery(
                "SELECT release_id,episode_id,release_title,episode_title,poster_url,episode_ordinal,position_ms,duration_ms,is_watched,updated_at FROM watch_entries w $where ORDER BY updated_at DESC,rowid DESC" +
                    (limit?.let { " LIMIT ${it.coerceAtLeast(0)}" } ?: ""),
                args,
            )
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0).toIntOrNull() ?: continue
                        add(
                            WatchProgress(
                                Release(
                                    id,
                                    cursor.getString(2),
                                    posterUrl = cursor.getString(4).orEmpty(),
                                ),
                                cursor.getString(1),
                                cursor.getString(3),
                                cursor.getInt(5),
                                cursor.getLong(6),
                                cursor.getLong(7),
                                cursor.getInt(8) != 0,
                                cursor.getLong(9) * 1000,
                            )
                        )
                    }
                }
            }

    fun saveProgress(
        release: Release,
        episode: Episode,
        position: Long,
        duration: Long,
        completed: Boolean = false,
    ) {
        val pos =
            position.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
        val watched = completed || (duration > 0 && pos.toDouble() / duration >= 0.9)
        val now = System.currentTimeMillis() / 1000
        database.beginTransaction()
        try {
            database.execSQL(
                "INSERT OR REPLACE INTO playback_positions VALUES(?,?,?,?,?)",
                arrayOf<Any?>(
                    release.id.toString(),
                    episode.id,
                    pos,
                    duration.takeIf { it > 0 },
                    now,
                ),
            )
            database.execSQL(
                "INSERT OR REPLACE INTO watch_entries VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    release.id.toString(),
                    episode.id,
                    release.title,
                    episode.title,
                    release.posterUrl,
                    episode.ordinal,
                    pos,
                    duration.takeIf { it > 0 },
                    if (watched) 1 else 0,
                    now,
                ),
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        nativePreferences
            .edit()
            .putLong("progress.updatedAt.${release.id}:${episode.id}", System.currentTimeMillis())
            .apply()
    }
}

internal const val LEGACY_FLUTTER_PREFERENCES = "FlutterSharedPreferences"

/**
 * Removes the preferences file left by the Flutter app (0.8.x and earlier).
 * It is no longer read; deleting it frees space and drops old personal data.
 */
internal fun deleteLegacyFlutterPreferences(context: Context) {
    val file =
        File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_FLUTTER_PREFERENCES.xml")
    if (!file.exists()) return
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.deleteSharedPreferences(LEGACY_FLUTTER_PREFERENCES)
        } else {
            context
                .getSharedPreferences(LEGACY_FLUTTER_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            file.delete()
        }
    }
}
