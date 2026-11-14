package com.dreamcast.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.dreamcast.nativeapp.UiNotification
import com.dreamcast.nativeapp.UiSnapshot
import com.dreamcast.nativeapp.data.WatchProgress

/**
 * What poster cards show over the cover: how far the episode in progress is watched, and a
 * "new episode" label for releases with unread episode notifications.
 */
internal class PosterMarks(
    private val progress: Map<Int, Float>,
    private val fresh: Set<Int>,
) {
    fun progress(releaseId: Int): Float? = progress[releaseId]

    fun isNew(releaseId: Int): Boolean = releaseId in fresh

    companion object {
        fun from(watching: List<WatchProgress>, notifications: List<UiNotification>) =
            PosterMarks(
                // The latest episode in progress per release; finished ones need no bar.
                progress =
                    watching
                        .filter { !it.watched && it.durationMs > 0 && it.positionMs > 0 }
                        .sortedByDescending { it.updatedAt }
                        .distinctBy { it.release.id }
                        .associate {
                            it.release.id to
                                (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f)
                        },
                fresh = notifications.filter { it.unread }.mapNotNull { it.release?.id }.toSet(),
            )
    }
}

@Composable
internal fun rememberPosterMarks(ui: UiSnapshot): PosterMarks =
    remember(ui.continueWatching, ui.notifications) {
        PosterMarks.from(ui.continueWatching, ui.notifications)
    }
