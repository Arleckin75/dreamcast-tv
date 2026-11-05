package com.dreamcast.nativeapp.data

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SharedLoadsTest {
    @Test
    fun overlappingReadersShareWorkAndOneCancellationKeepsTheOtherAlive() = runBlocking {
        val loads = SharedLoads()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        suspend fun load(): String {
            calls.incrementAndGet()
            started.complete(Unit)
            finish.await()
            return "done"
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { loads.run("key") { load() } }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { loads.run("key") { load() } }
        first.cancelAndJoin()
        finish.complete(Unit)
        assertEquals("done", second.await())
        assertEquals(1, calls.get())
        assertEquals("new", loads.run("key") { "new" })
    }

    @Test
    fun cancellingEveryReaderCancelsTheOperationAndDoesNotPoisonNextLoad() = runBlocking {
        val loads = SharedLoads()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val request = async {
            loads.run("key") {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        started.await()
        request.cancelAndJoin()
        withTimeout(2000) { cancelled.await() }
        assertEquals(42, loads.run("key") { 42 })
    }
}
