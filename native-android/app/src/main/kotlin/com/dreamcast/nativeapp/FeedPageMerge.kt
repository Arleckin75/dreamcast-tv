package com.dreamcast.nativeapp

import com.dreamcast.nativeapp.data.Release
import com.dreamcast.nativeapp.data.ReleasePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class MergedFeedPage(val items: List<Release>, val exhausted: Boolean)

/** Keep existing card order/metadata and append only previously unseen titles off the UI thread. */
internal suspend fun mergeFeedPage(existing: List<Release>, response: ReleasePage): MergedFeedPage =
    withContext(Dispatchers.Default) {
        val unique = LinkedHashMap<Int, Release>()
        if (response.page > 1) {
            existing.forEach {
                ensureActive()
                unique.putIfAbsent(it.id, it)
            }
        }
        response.items.forEach {
            ensureActive()
            unique.putIfAbsent(it.id, it)
        }
        MergedFeedPage(
            unique.values.toList(),
            exhausted = response.page > 1 && unique.size == existing.size,
        )
    }
