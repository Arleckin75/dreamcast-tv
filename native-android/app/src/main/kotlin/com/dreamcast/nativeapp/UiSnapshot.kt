package com.dreamcast.nativeapp

import com.dreamcast.nativeapp.data.*

/** Prepared off the UI thread; composables only consume these in-memory values. */
data class UiNotification(
    val key: String,
    val release: Release?,
    val message: String,
    val createdAt: String,
    val unread: Boolean,
    val raw: String,
)

data class UiSnapshot(
    val theme: String = "dark",
    val accent: String = "system",
    val dynamicColor: Boolean = true,
    val streamTechnology: String = "none",
    val profile: String = "",
    val retentionHours: Long = 8,
    val bookmarks: List<Bookmark> = emptyList(),
    val bookmarksById: Map<Int, Bookmark> = emptyMap(),
    val subscriptions: Set<Int> = emptySet(),
    val continueWatching: List<WatchProgress> = emptyList(),
    val progressByRelease: Map<Int, Map<String, WatchProgress>> = emptyMap(),
    val searchHistory: List<String> = emptyList(),
    val notifications: List<UiNotification> = emptyList(),
    val unreadNotifications: Int = 0,
)

internal fun Storage.readUiSnapshot(): UiSnapshot {
    val bookmarks = bookmarks()
    val progress = recentProgress()
    val notifications =
        records("notifications.history.item.")
            .map { (key, record) ->
                UiNotification(
                    key,
                    runCatching { Release.fromJson(record.getJSONObject("release")) }.getOrNull(),
                    record.text("message"),
                    record.text("createdAt"),
                    record.isNull("readAt"),
                    record.toString(),
                )
            }
            .sortedByDescending { it.createdAt }
    return UiSnapshot(
        theme = string("settings.theme_mode", "dark"),
        accent = string("settings.accent", "system"),
        dynamicColor = boolean("settings.dynamic_color", true),
        streamTechnology = string("player.preferred_stream_technology", "none"),
        profile = profile(),
        retentionHours = retentionHours(),
        bookmarks = bookmarks,
        bookmarksById = bookmarks.associateBy { it.release.id },
        subscriptions =
            records("notifications.release.item.")
                .mapNotNull { (key, _) ->
                    key.removePrefix("notifications.release.item.").toIntOrNull()
                }
                .toSet(),
        continueWatching = progress.distinctBy { it.release.id }.filterNot { it.watched }.take(10),
        progressByRelease =
            progress
                .groupBy { it.release.id }
                .mapValues { (_, entries) -> entries.associateBy { it.episodeId } },
        searchHistory = searchHistory(),
        notifications = notifications,
        unreadNotifications = notifications.count { it.unread },
    )
}
