package com.dreamcast.nativeapp

import android.app.Application
import com.dreamcast.nativeapp.data.Episode
import com.dreamcast.nativeapp.data.Release
import com.dreamcast.nativeapp.data.Storage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ProgressPersistenceTest {
    @Test
    fun finalPositionAndCompletionSurviveQueueDrainAndDatabaseReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val storage = Storage(context)
        val errors = mutableListOf<Exception>()
        val writer =
            ProgressWriter(
                write = {
                    storage.saveProgress(
                        it.release,
                        it.episode,
                        it.positionMs,
                        it.durationMs,
                        it.completed,
                    )
                },
                onError = { errors.add(it) },
            )
        val release = Release(123, "Title")
        val first = Episode("one", release.id, 1, "Episode 1", "")
        val second = Episode("two", release.id, 2, "Episode 2", "")
        try {
            writer.save(ProgressSnapshot(release, first, 4000, 10000, false))
            writer.awaitPending()
            assertEquals(4000L, storage.progress(release.id, first.id)!!.positionMs)
            assertFalse(storage.progress(release.id, first.id)!!.watched)
            writer.save(ProgressSnapshot(release, first, 10000, 10000, true))
            writer.save(ProgressSnapshot(release, second, 2500, 10000, false))
        } finally {
            // No reader barrier here: shutdown itself must drain final queued saves.
            writer.close()
            storage.close()
        }
        val reopened = Storage(context)
        try {
            assertTrue(errors.isEmpty())
            val completed = reopened.progress(release.id, first.id)!!
            assertEquals(10000L, completed.positionMs)
            assertTrue(completed.watched)
            val resumed = reopened.progress(release.id, second.id)!!
            assertEquals(2500L, resumed.positionMs)
            assertEquals(10000L, resumed.durationMs)
            assertFalse(resumed.watched)
            // Restarting after queued saves must retain the reset position.
            reopened.saveProgress(release, second, 0, resumed.durationMs)
            assertEquals(0L, reopened.progress(release.id, second.id)!!.positionMs)
        } finally {
            reopened.close()
        }
    }
}
