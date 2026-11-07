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

enum class UiSection {
    SETTINGS,
    PROFILE,
    CACHE,
    BOOKMARKS,
    SUBSCRIPTIONS,
    PROGRESS,
    SEARCH,
    NOTIFICATIONS,
}

internal fun uiSectionsForPreference(rawKey: String?): Set<UiSection> {
    val key = rawKey ?: return UiSection.entries.toSet()
    val section =
        when {
            key.startsWith("settings.") || key == "player.preferred_stream_technology" ->
                UiSection.SETTINGS
            key.startsWith("cache.retention") -> UiSection.CACHE
            key.startsWith("library.") -> UiSection.BOOKMARKS
            key.startsWith("notifications.release.") -> UiSection.SUBSCRIPTIONS
            key.startsWith("notifications.history.") -> UiSection.NOTIFICATIONS
            key == "search.history" -> UiSection.SEARCH
            else -> null
        }
    return section?.let { setOf(it) } ?: emptySet()
}

/** Only replace requested sections; unchanged collections retain their identity. */
internal fun Storage.readUiSnapshot(
    previous: UiSnapshot = UiSnapshot(),
    sections: Set<UiSection> = UiSection.entries.toSet(),
    progressReleaseIds: Set<Int>? = null,
): UiSnapshot {
    var result = previous
    val records by lazy { recordSnapshot() }
    if (UiSection.SETTINGS in sections)
        result =
            result.copy(
                theme = string("settings.theme_mode", "dark"),
                accent = string("settings.accent", "system"),
                dynamicColor = boolean("settings.dynamic_color", true),
                streamTechnology = string("player.preferred_stream_technology", "none"),
            )
    if (UiSection.PROFILE in sections) result = result.copy(profile = profile())
    if (UiSection.CACHE in sections) result = result.copy(retentionHours = retentionHours())
    if (UiSection.BOOKMARKS in sections) {
        val bookmarks = bookmarks(records)
        result =
            result.copy(
                bookmarks = bookmarks,
                bookmarksById = bookmarks.associateBy { it.release.id },
            )
    }
    if (UiSection.SUBSCRIPTIONS in sections)
        result =
            result.copy(
                subscriptions =
                    records("notifications.release.item.", records)
                        .mapNotNull { (key, _) ->
                            key.removePrefix("notifications.release.item.").toIntOrNull()
                        }
                        .toSet()
            )
    if (UiSection.PROGRESS in sections) {
        val recent = continueProgress()
        val progress =
            if (progressReleaseIds == null) recentProgress()
            else recent + progressReleaseIds.flatMap { releaseProgress(it) }
        result =
            result.copy(
                continueWatching = recent,
                progressByRelease =
                    progress
                        .groupBy { it.release.id }
                        .mapValues { (_, entries) -> entries.associateBy { it.episodeId } },
            )
    }
    if (UiSection.SEARCH in sections) result = result.copy(searchHistory = searchHistory())
    if (UiSection.NOTIFICATIONS in sections) {
        val notifications =
            records("notifications.history.item.", records)
                .map { (key, record) ->
                    UiNotification(
                        key,
                        runCatching { Release.fromJson(record.getJSONObject("release")) }
                            .getOrNull(),
                        record.text("message"),
                        record.text("createdAt"),
                        record.isNull("readAt"),
                        record.toString(),
                    )
                }
                .sortedByDescending { it.createdAt }
        result =
            result.copy(
                notifications = notifications,
                unreadNotifications = notifications.count { it.unread },
            )
    }
    return result
}
