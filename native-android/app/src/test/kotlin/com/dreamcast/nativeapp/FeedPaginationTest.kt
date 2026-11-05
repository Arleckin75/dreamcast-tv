package com.dreamcast.nativeapp

import com.dreamcast.nativeapp.data.Release
import org.junit.Assert.*
import org.junit.Test

class FeedPaginationTest {
    private fun loadedFeed() =
        FeedState().apply {
            loadedPage = 1
            items = listOf(Release(1, "First"))
            total = 100
        }

    @Test
    fun repeatedEventsCannotSkipAnUnfinishedPage() {
        val feed = loadedFeed()
        assertTrue(feed.requestNextPage())
        repeat(100) { assertFalse(feed.requestNextPage()) }
        assertEquals(2, feed.page)
        assertEquals(1, feed.loadedPage)
        // Completion of the merge, rather than receipt of the response, unlocks page 3.
        feed.items = feed.items + Release(2, "Second")
        feed.loadedPage = 2
        assertTrue(feed.requestNextPage())
        assertEquals(3, feed.page)
    }

    @Test
    fun failedPageStaysSelectedForRetryAndDoesNotModifyExistingCards() {
        val feed = loadedFeed()
        val original = feed.items
        feed.requestNextPage()
        // A network failure does not advance loadedPage; retry uses the same requested page.
        assertFalse(feed.requestNextPage())
        assertEquals(2, feed.page)
        assertSame(original, feed.items)
    }

    @Test
    fun exhaustedAndCompleteFeedsDoNotRequestMorePages() {
        val feed = loadedFeed()
        feed.exhausted = true
        assertFalse(feed.requestNextPage())
        feed.exhausted = false
        feed.total = feed.items.size
        assertFalse(feed.requestNextPage())
        assertEquals(1, feed.page)
        assertFalse(FeedState().requestNextPage())
    }
}
