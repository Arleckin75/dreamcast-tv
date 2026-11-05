package com.dreamcast.nativeapp

import com.dreamcast.nativeapp.data.Release
import com.dreamcast.nativeapp.data.ReleasePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Immutable page chunks: appending a page shares existing cards rather than rebuilding them. */
private class ReleasePages(
    private val chunks: List<List<Release>>,
    private val ids: List<Set<Int>> = chunks.map { page -> page.mapTo(HashSet()) { it.id } },
) : AbstractList<Release>() {
    private val ends = IntArray(chunks.size)
    override val size: Int

    init {
        var count = 0
        chunks.forEachIndexed { index, page ->
            count += page.size
            ends[index] = count
        }
        size = count
    }

    override fun get(index: Int): Release {
        if (index !in 0 until size) throw IndexOutOfBoundsException()
        val found = ends.binarySearch(index + 1)
        val page = if (found >= 0) found else -found - 1
        return chunks[page][index - (if (page == 0) 0 else ends[page - 1])]
    }

    fun containsId(id: Int) = ids.any { id in it }

    fun append(page: List<Release>): ReleasePages =
        if (page.isEmpty()) this
        else ReleasePages(chunks + listOf(page), ids + listOf(page.mapTo(HashSet()) { it.id }))

    companion object {
        fun from(items: List<Release>) =
            if (items is ReleasePages) items else ReleasePages(listOf(items.toList()))
    }
}

internal data class MergedFeedPage(val items: List<Release>, val exhausted: Boolean)

/** Keep existing card order/metadata and append only previously unseen titles off the UI thread. */
internal suspend fun mergeFeedPage(existing: List<Release>, response: ReleasePage): MergedFeedPage =
    withContext(Dispatchers.Default) {
        ensureActive()
        val base = ReleasePages.from(if (response.page > 1) existing else emptyList())
        val unique = LinkedHashMap<Int, Release>()
        response.items.forEach {
            ensureActive()
            if (!base.containsId(it.id)) unique.putIfAbsent(it.id, it)
        }
        MergedFeedPage(
            base.append(unique.values.toList()),
            exhausted = response.page > 1 && unique.isEmpty(),
        )
    }
