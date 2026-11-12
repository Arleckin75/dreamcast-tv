package com.dreamcast.nativeapp.data

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Bookmarks, episode notification subscriptions and search history, kept in preferences. */
internal class LibraryStore(private val preferences: PreferenceStore) {
    fun bookmarks(snapshot: Map<String, Any?> = preferences.snapshot()): List<Bookmark> =
        preferences
            .records(BOOKMARK_PREFIX, snapshot)
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
            preferences.edit {
                remove("$BOOKMARK_PREFIX${release.id}")
                remove("library.release.status.${release.id}")
            }
            return
        }
        preferences.edit {
            putString("library.release.status.${release.id}", status.name)
            putString(
                "$BOOKMARK_PREFIX${release.id}",
                JSONObject()
                    .put("status", status.name)
                    .put("release", release.json())
                    .put("updatedAt", Instant.now().toString())
                    .toString(),
            )
        }
    }

    fun subscribed(id: Int) = preferences.string("notifications.release.item.$id").isNotEmpty()

    fun subscribe(release: Release, count: Int) {
        preferences.put(
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
                val array = JSONArray(preferences.string(SEARCH_HISTORY, "[]"))
                (0 until array.length()).map { array.getString(it) }
            }
            .getOrDefault(emptyList())

    fun saveSearch(query: String) {
        val history = listOf(query) + searchHistory().filterNot { it.equals(query, true) }
        preferences.put(SEARCH_HISTORY, JSONArray(history.take(MAX_SEARCH_HISTORY)).toString())
    }

    fun removeSearch(query: String) {
        preferences.put(SEARCH_HISTORY, JSONArray(searchHistory().filterNot { it == query }).toString())
    }

    private companion object {
        const val BOOKMARK_PREFIX = "library.release.item."
        const val SEARCH_HISTORY = "search.history"
        const val MAX_SEARCH_HISTORY = 12
    }
}
