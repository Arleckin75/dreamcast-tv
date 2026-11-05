package com.dreamcast.nativeapp

import com.dreamcast.nativeapp.data.Episode
import com.dreamcast.nativeapp.data.Release
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Immutable player values captured on the main thread, without retaining the activity/player. */
internal data class ProgressSnapshot(
    val release: Release,
    val episode: Episode,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
)

/** Application-owned FIFO: activity destruction must not cancel the final queued save. */
internal class ProgressWriter(
    private val write: (ProgressSnapshot) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private val queue = Channel<() -> Unit>(Channel.UNLIMITED)
    private val worker =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { for (action in queue) action() }

    fun save(snapshot: ProgressSnapshot) {
        check(
            queue
                .trySend {
                    try {
                        write(snapshot)
                    } catch (error: Exception) {
                        onError(error)
                    }
                }
                .isSuccess
        )
    }

    /** Suspend readers until preceding writes finish; never block the main thread. */
    suspend fun awaitPending() {
        val done = CompletableDeferred<Unit>()
        queue.send { done.complete(Unit) }
        done.await()
    }

    internal suspend fun close() {
        queue.close()
        worker.join()
    }
}
