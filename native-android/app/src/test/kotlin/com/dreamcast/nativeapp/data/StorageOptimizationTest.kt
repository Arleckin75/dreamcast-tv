package com.dreamcast.nativeapp.data

import android.app.Application
import com.dreamcast.nativeapp.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class StorageOptimizationTest {
    @Test
    fun maintenanceBoundsCacheButPreservesRecentStaleDataAndUserProgress() {
        val storage =
            Storage(RuntimeEnvironment.getApplication(), maxCacheEntries = 3, maxCacheBytes = 512)
        try {
            val show = Release(1, "Show")
            storage.saveProgress(show, Episode("one", 1, 1, "One", ""), 100, 1000)
            repeat(10) { storage.putCache("key:$it", "x".repeat(100)) }
            storage.retention(-1)
            storage.maintainCache(force = true)
            assertEquals(3L, storage.cacheStats().first)
            assertNotNull(storage.cache("key:9"))
            assertNull(storage.cache("key:0"))
            assertEquals(100L, storage.progress(1, "one")!!.positionMs)
            storage.putCache("oversized", "x".repeat(1024))
            assertNull(storage.cache("oversized"))
            storage.retention(0)
            storage.maintainCache(force = true)
            assertNotNull(storage.cache("key:9"))
        } finally {
            storage.close()
        }
    }

    @Test
    fun scopedSnapshotLoadsActiveEpisodesAndDoesNotResurrectOlderUnwatchedEpisodes() {
        val storage = Storage(RuntimeEnvironment.getApplication())
        try {
            val show = Release(1, "Show")
            storage.saveProgress(show, Episode("old", 1, 1, "Old", ""), 100, 1000)
            storage.saveProgress(show, Episode("done", 1, 2, "Done", ""), 1000, 1000, true)
            val other = Release(2, "Other")
            repeat(50) {
                storage.saveProgress(other, Episode("ep$it", 2, it + 1, "Episode", ""), 100, 1000)
            }
            val home = storage.readUiSnapshot(progressReleaseIds = emptySet())
            assertEquals(listOf(2), home.continueWatching.map { it.release.id })
            assertEquals(1, home.progressByRelease[2]!!.size)
            assertNull(home.progressByRelease[1])
            val detail = storage.readUiSnapshot(progressReleaseIds = setOf(1))
            assertEquals(2, detail.progressByRelease[1]!!.size)
            assertTrue(detail.progressByRelease[1]!!["done"]!!.watched)
        } finally {
            storage.close()
        }
    }
}
