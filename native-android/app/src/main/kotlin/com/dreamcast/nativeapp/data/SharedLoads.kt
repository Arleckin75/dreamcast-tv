package com.dreamcast.nativeapp.data

import kotlinx.coroutines.*

/** Share overlapping work; cancelling one reader must not cancel another reader's request. */
internal class SharedLoads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Entry(val task: Deferred<Any>, var readers: Int = 0)

    private val entries = mutableMapOf<Any, Entry>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> run(key: Any, loader: suspend () -> T): T {
        val entry =
            synchronized(entries) {
                entries
                    .getOrPut(key) { Entry(scope.async(start = CoroutineStart.LAZY) { loader() }) }
                    .also { it.readers++ }
            }
        try {
            return entry.task.await() as T
        } finally {
            synchronized(entries) {
                if (--entry.readers == 0) {
                    entries.remove(key)
                    entry.task.cancel()
                }
            }
        }
    }
}
