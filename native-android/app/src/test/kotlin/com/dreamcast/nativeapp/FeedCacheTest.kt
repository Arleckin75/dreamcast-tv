package com.dreamcast.nativeapp

import com.dreamcast.nativeapp.data.Release
import org.junit.Assert.*
import org.junit.Test

class FeedCacheTest {
    @Test
    fun defaultLimitEvictsOnlyTheOldestOfNineDifferentFeeds() {
        val cache = FeedCache()
        val original = (0 until 8).map { cache.getOrCreate("query:$it") }
        repeat(20) { assertSame(original[0], cache.getOrCreate("query:0")) }
        val ninth = cache.getOrCreate("query:8")
        assertSame(original[0], cache.getOrCreate("query:0"))
        for (index in 2 until 8) {
            assertSame(original[index], cache.getOrCreate("query:$index"))
        }
        assertSame(ninth, cache.getOrCreate("query:8"))
        assertNotSame(original[1], cache.getOrCreate("query:1"))
    }

    @Test
    fun longSearchSessionKeepsRecentCatalogAndReloadsOldSearch() {
        val cache = FeedCache()
        val catalog = cache.getOrCreate("catalog:2026")
        catalog.page = 5
        catalog.loadedPage = 5
        catalog.items = listOf(Release(1, "Catalog title"))
        val firstSearch = cache.getOrCreate("search:0")
        repeat(1000) { index ->
            cache.getOrCreate("search:$index")
            assertSame(catalog, cache.getOrCreate("catalog:2026"))
        }
        assertEquals(5, catalog.page)
        assertEquals(5, catalog.loadedPage)
        assertEquals(1, catalog.items.size)
        assertNotSame(firstSearch, cache.getOrCreate("search:0"))
    }

    @Test
    fun returningToRecentFeedPreservesItemsAndPagination() {
        val cache = FeedCache(2)
        val catalog = cache.getOrCreate("2026:action")
        catalog.items = listOf(Release(1, "Title"))
        catalog.page = 3
        catalog.loadedPage = 3
        catalog.total = 100
        cache.getOrCreate("search:title")

        val restored = cache.getOrCreate("2026:action")
        assertSame(catalog, restored)
        assertEquals(3, restored.page)
        assertEquals(3, restored.loadedPage)
        assertEquals(100, restored.total)
        assertEquals(listOf(1), restored.items.map { it.id })
    }

    @Test
    fun readingAnOldFeedProtectsItFromNextEviction() {
        val cache = FeedCache(2)
        val first = cache.getOrCreate("first")
        val second = cache.getOrCreate("second")
        assertSame(first, cache.getOrCreate("first"))
        cache.getOrCreate("third")
        assertSame(first, cache.getOrCreate("first"))
        assertNotSame(second, cache.getOrCreate("second"))
    }

    @Test
    fun evictedFeedRestartsWithConsistentPagination() {
        val cache = FeedCache(1)
        val old = cache.getOrCreate("old")
        old.page = 20
        old.loadedPage = 20
        old.exhausted = true
        old.items = listOf(Release(1, "Title"))
        cache.getOrCreate("new")
        val reloaded = cache.getOrCreate("old")
        assertNotSame(old, reloaded)
        assertEquals(1, reloaded.page)
        assertEquals(0, reloaded.loadedPage)
        assertTrue(reloaded.items.isEmpty())
        assertFalse(reloaded.exhausted)
        // Eviction must not mutate a list still held by a composed screen.
        assertEquals(1, old.items.size)
    }
}
