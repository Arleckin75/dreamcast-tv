package com.dreamcast.nativeapp.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Uses the original on-device names so a signed update can retain user data. */
class Storage(context: Context) {
    private val preferences =
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
    private val nativePreferences =
        context.getSharedPreferences("dream_cast_native", Context.MODE_PRIVATE)
    private val database: SQLiteDatabase

    fun close() {
        database.close()
    }

    init {
        val directory = File(context.applicationInfo.dataDir, "app_flutter").apply { mkdirs() }
        database = SQLiteDatabase.openOrCreateDatabase(File(directory, "dream_cast.sqlite"), null)
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
    }

    fun string(key: String, default: String = "") =
        (nativePreferences.all[key] ?: preferences.all["flutter.$key"]) as? String ?: default

    fun number(key: String, default: Long = 0): Long {
        val value = nativePreferences.all[key] ?: preferences.all["flutter.$key"]
        return (value as? Number)?.toLong() ?: (value as? String)?.toLongOrNull() ?: default
    }

    fun boolean(key: String, default: Boolean = false) =
        if (nativePreferences.contains(key)) nativePreferences.getBoolean(key, default)
        else preferences.getBoolean("flutter.$key", default)

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
        preferences.edit().remove("flutter.$key").apply()
    }

    fun records(prefix: String): List<Pair<String, JSONObject>> {
        val combined =
            preferences.all.mapKeys { it.key.removePrefix("flutter.") } + nativePreferences.all
        return combined
            .filterKeys { it.startsWith(prefix) }
            .mapNotNull { (key, value) ->
                runCatching { key to JSONObject(value as String) }.getOrNull()
            }
    }

    fun bookmarks(): List<Bookmark> =
        records("library.release.item.")
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
        put("library.release.status.${release.id}", status.name)
        put(
            "library.release.item.${release.id}",
            JSONObject()
                .put("status", status.name)
                .put("release", release.json())
                .put("updatedAt", Instant.now().toString())
                .toString(),
        )
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

    fun searchHistory(): List<String> {
        val native = nativePreferences.getString("search.history", null)
        if (native != null)
            return runCatching {
                    val array = JSONArray(native)
                    (0 until array.length()).map { array.getString(it) }
                }
                .getOrDefault(emptyList())
        // Older installations use either serialized or JSON-prefixed lists.
        val old = preferences.all["flutter.search.history"]
        return when (old) {
            is Set<*> -> old.filterIsInstance<String>()
            is String -> LegacyPreferences.stringList(old)
            else -> emptyList()
        }
    }

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

    fun putCache(key: String, value: String) {
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
        recentProgress()
            .firstOrNull { it.release.id == releaseId && it.episodeId == episodeId }
            ?.let {
                return it
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

    fun syncVlcProgress(context: Context) {
        val vlc = context.getSharedPreferences("dream_cast_vlc_progress", Context.MODE_PRIVATE)
        for (entry in recentProgress()) {
            val key = "${entry.release.id}:${entry.episodeId}"
            if (
                vlc.getLong("$key:updatedAt", 0) >
                    number("progress.updatedAt.$key", entry.updatedAt) && vlc.contains(key)
            ) {
                saveProgress(
                    entry.release,
                    Episode(
                        entry.episodeId,
                        entry.release.id,
                        entry.ordinal,
                        entry.episodeTitle,
                        "",
                    ),
                    vlc.getLong(key, entry.positionMs),
                    entry.durationMs,
                )
            }
        }
    }

    fun recentProgress(): List<WatchProgress> =
        database
            .rawQuery(
                "SELECT release_id,episode_id,release_title,episode_title,poster_url,episode_ordinal,position_ms,duration_ms,is_watched,updated_at FROM watch_entries ORDER BY updated_at DESC,rowid DESC",
                null,
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
