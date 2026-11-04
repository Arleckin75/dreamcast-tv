package com.dreamcast.nativeapp

import com.dreamcast.nativeapp.data.Release
import com.dreamcast.nativeapp.data.ReleasePage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FeedPageMergeTest {
    @Test
    fun overlappingPagesKeepOrderAndExistingCardMetadata() = runBlocking {
        val first = Release(1, "First")
        val second = Release(2, "Second")
        val existing = listOf(first, second)
        val response =
            ReleasePage(
                listOf(Release(2, "Changed"), Release(3, "Third"), Release(3, "Duplicate")),
                3,
                2,
            )
        val result = mergeFeedPage(existing, response)
        assertEquals(listOf(1, 2, 3), result.items.map { it.id })
        assertSame(first, result.items[0])
        assertSame(second, result.items[1])
        assertEquals("Third", result.items[2].title)
        assertFalse(result.exhausted)
        assertEquals(listOf(first, second), existing)
    }

    @Test
    fun firstPageReplacesOldResultsAndRemovesDuplicateIds() = runBlocking {
        val result =
            mergeFeedPage(
                listOf(Release(99, "Old")),
                ReleasePage(listOf(Release(1, "New"), Release(1, "Duplicate")), 1, 1),
            )
        assertEquals(listOf(1), result.items.map { it.id })
        assertFalse(result.exhausted)
    }

    @Test
    fun emptyAndRepeatedSubsequentPagesStopPagination() = runBlocking {
        val existing = listOf(Release(1, "First"))
        assertTrue(mergeFeedPage(existing, ReleasePage(emptyList(), 10, 2)).exhausted)
        val repeated = mergeFeedPage(existing, ReleasePage(existing, 10, 2))
        assertTrue(repeated.exhausted)
        assertEquals(existing, repeated.items)
    }

    @Test
    fun largeListIsTraversedOffCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        val existing =
            object : AbstractList<Release>() {
                override val size = 10000

                override fun get(index: Int): Release {
                    assertNotSame(caller, Thread.currentThread())
                    return Release(index, "Title $index")
                }
            }
        val result = mergeFeedPage(existing, ReleasePage(listOf(Release(10000, "New")), 10001, 2))
        assertEquals(10001, result.items.size)
        assertEquals(10000, result.items.last().id)
    }

    @Test
    fun canceledRequestCannotPublishMergedResults() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val unblock = java.util.concurrent.CountDownLatch(1)
        val existing =
            object : AbstractList<Release>() {
                override val size = 2

                override fun get(index: Int): Release {
                    if (index == 0) {
                        entered.countDown()
                        check(unblock.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return Release(index, "Title")
                }
            }
        var published = false
        val job =
            launch(Dispatchers.Default) {
                mergeFeedPage(existing, ReleasePage(listOf(Release(3, "New")), 3, 2))
                published = true
            }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel()
        } finally {
            unblock.countDown()
            job.join()
        }
        assertFalse(published)
    }
}
