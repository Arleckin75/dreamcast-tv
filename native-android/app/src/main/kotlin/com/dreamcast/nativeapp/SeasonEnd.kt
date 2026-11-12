package com.dreamcast.nativeapp

import com.dreamcast.nativeapp.data.LibraryStatus
import com.dreamcast.nativeapp.data.Release

/** Choices after the last available episode ends. */
internal enum class SeasonEndAction(val label: String) {
    REWATCH("Пересмотреть с первой серии"),
    MARK_WATCHED("Отметить как просмотренное"),
    CLOSE("Закрыть"),
}

internal data class SeasonEndMenu(val title: String, val actions: List<SeasonEndAction>)

/**
 * True when [release] has no more episodes to come: the site does not mark it as airing and
 * all [available] episodes of its known total are out.
 */
internal fun seasonComplete(release: Release, available: Int): Boolean {
    val status = release.status.lowercase()
    if (AIRING_MARKERS.any { it in status }) return false
    return release.totalEpisodes <= available
}

/**
 * Menu shown when the last available episode ends. "Mark as watched" is offered only for a
 * finished season whose library status is not already [LibraryStatus.completed].
 */
internal fun seasonEndMenu(
    release: Release,
    available: Int,
    libraryStatus: LibraryStatus?,
): SeasonEndMenu =
    if (seasonComplete(release, available))
        SeasonEndMenu(
            "Сезон просмотрен",
            listOfNotNull(
                SeasonEndAction.REWATCH,
                SeasonEndAction.MARK_WATCHED.takeIf { libraryStatus != LibraryStatus.completed },
                SeasonEndAction.CLOSE,
            ),
        )
    else
        SeasonEndMenu(
            "Новых серий пока нет",
            listOf(SeasonEndAction.REWATCH, SeasonEndAction.CLOSE),
        )

/** Words in the site's release status that mean new episodes are still coming out. */
private val AIRING_MARKERS = listOf("онгоинг", "выходит", "ongoing")
