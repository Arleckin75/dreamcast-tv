package com.dreamcast.nativeapp

import com.dreamcast.nativeapp.data.Episode
import com.dreamcast.nativeapp.data.Release
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ProgressWriterTest {
    private fun snapshot(id: String, position: Long, completed: Boolean = false) =
        ProgressSnapshot(Release(1, "Title"), Episode(id, 1, 1, id, ""), position, 10000, completed)

    @Test
    fun queuedSavesDoNotBlockCallerAndRetainEpisodeAndOrder() = runBlocking {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val saved = mutableListOf<ProgressSnapshot>()
        val caller = Thread.currentThread()
        val writer =
            ProgressWriter(
                write = {
                    assertNotSame(caller, Thread.currentThread())
                    if (saved.isEmpty()) {
                        entered.countDown()
                        check(unblock.await(5, TimeUnit.SECONDS))
                    }
                    saved.add(it)
                },
                onError = { throw AssertionError(it) },
            )
        try {
            val first = snapshot("first", 5000)
            val final = snapshot("first", 10000, true)
            val next = snapshot("next", 0)
            writer.save(first)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // These submissions finish while storage is still blocked on the first write.
            writer.save(final)
            writer.save(next)
            unblock.countDown()
            withTimeout(5000) { writer.awaitPending() }
            assertEquals(listOf(first, final, next), saved)
        } finally {
            unblock.countDown()
            writer.close()
        }
    }

    @Test
    fun failedWriteIsReportedAndDoesNotDiscardLaterSaves() = runBlocking {
        val saved = mutableListOf<ProgressSnapshot>()
        val errors = mutableListOf<Exception>()
        val writer =
            ProgressWriter(
                write = {
                    if (it.positionMs == 1L) error("Storage unavailable")
                    saved.add(it)
                },
                onError = { errors.add(it) },
            )
        try {
            writer.save(snapshot("first", 1))
            val final = snapshot("first", 10000, true)
            writer.save(final)
            withTimeout(5000) { writer.awaitPending() }
            assertEquals(1, errors.size)
            assertEquals(listOf(final), saved)
        } finally {
            writer.close()
        }
    }
}
