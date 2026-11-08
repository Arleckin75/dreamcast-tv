package com.dreamcast.nativeapp.data

import android.content.Context
import android.os.Build
import java.io.File
import org.json.JSONObject

/**
 * Native preferences and the application SQLite database.
 *
 * A facade over the per-area stores: [PreferenceStore], [LibraryStore], [ProfileStore],
 * [CacheStore] and [WatchHistoryStore]. The database is opened on first use.
 */
class Storage(
    context: Context,
    maxCacheEntries: Int = 512,
    maxCacheBytes: Long = 16 * 1024 * 1024,
) {
    init {
        require(maxCacheEntries > 0 && maxCacheBytes > 0)
        deleteLegacyFlutterPreferences(context)
    }

    data class CacheRow(val value: String, val updatedAt: Long, val expiresAt: Long?)

    private val database = lazy { openStorageDatabase(context.applicationContext) }
    private val preferences =
        PreferenceStore(context.getSharedPreferences("dream_cast_native", Context.MODE_PRIVATE))
    private val library = LibraryStore(preferences)
    private val profiles = ProfileStore(database)
    private val cacheStore = CacheStore(database, preferences, maxCacheEntries, maxCacheBytes)
    private val history = WatchHistoryStore(database, preferences)

    fun close() {
        if (database.isInitialized()) database.value.close()
    }

    // Preferences

    fun string(key: String, default: String = "") = preferences.string(key, default)

    fun number(key: String, default: Long = 0) = preferences.number(key, default)

    fun boolean(key: String, default: Boolean = false) = preferences.boolean(key, default)

    fun put(key: String, value: String) = preferences.put(key, value)

    fun put(key: String, value: Boolean) = preferences.put(key, value)

    fun remove(key: String) = preferences.remove(key)

    fun recordSnapshot(): Map<String, Any?> = preferences.snapshot()

    fun records(
        prefix: String,
        snapshot: Map<String, Any?> = recordSnapshot(),
    ): List<Pair<String, JSONObject>> = preferences.records(prefix, snapshot)

    // Library, notifications and search

    fun bookmarks(snapshot: Map<String, Any?> = recordSnapshot()) = library.bookmarks(snapshot)

    fun bookmark(release: Release, status: LibraryStatus?) = library.bookmark(release, status)

    fun subscribed(id: Int) = library.subscribed(id)

    fun subscribe(release: Release, count: Int) = library.subscribe(release, count)

    fun searchHistory() = library.searchHistory()

    fun saveSearch(query: String) = library.saveSearch(query)

    fun removeSearch(query: String) = library.removeSearch(query)

    // Profile

    fun profile() = profiles.profile()

    fun saveProfile(name: String) = profiles.saveProfile(name)

    // Response cache

    fun retentionHours() = cacheStore.retentionHours()

    fun retention(hours: Long) = cacheStore.retention(hours)

    fun cache(key: String) = cacheStore.get(key)

    fun putCache(key: String, value: String) = cacheStore.put(key, value)

    fun maintainCache(force: Boolean = false) = cacheStore.maintain(force)

    fun clearCache() = cacheStore.clear()

    fun cacheStats() = cacheStore.stats()

    fun clearExpired() = cacheStore.clearExpired()

    // Watch history

    fun progress(releaseId: Int, episodeId: String) = history.progress(releaseId, episodeId)

    fun syncSoftwareProgress(context: Context) = history.syncSoftwareProgress(context)

    fun continueProgress(limit: Int = 10) = history.continueProgress(limit)

    fun releaseProgress(releaseId: Int) = history.releaseProgress(releaseId)

    fun recentProgress() = history.recentProgress()

    fun saveProgress(
        release: Release,
        episode: Episode,
        position: Long,
        duration: Long,
        completed: Boolean = false,
    ) = history.saveProgress(release, episode, position, duration, completed)
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
