package com.dreamcast.nativeapp.data

import android.app.Application
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The repository works with any [DreamcastSource], not only the website. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class DreamcastSourceTest {
    private class FakeSource : DreamcastSource {
        var offline = false
        var releaseFetches = 0
        var scriptFetches = 0

        override val streamHeaders = mapOf("X-Test" to "1")

        override fun releases(query: String, filter: CatalogFilter, page: Int) =
            SourceRequest(
                fetch = {
                    releaseFetches++
                    if (offline) throw IOException("offline")
                    "$query|$page"
                },
                parse = { ReleasePage(listOf(Release(page, it)), 1, page) },
            )

        override fun catalogOptions() =
            SourceRequest({ "2026" }, { CatalogOptions(listOf(it.toInt()), emptyList()) })

        override fun detail(release: Release) =
            SourceRequest({ "payload" }, { Detail(release, it, "https://example.test/s.js") })

        override fun episodes(detail: Detail, cache: TextCache) =
            SourceRequest(
                fetch = {
                    cache.text("fake:script") {
                        scriptFetches++
                        "script"
                    }
                },
                parse = {
                    listOf(Episode("e1", detail.release.id, 1, it, "https://example.test/v.m3u8"))
                },
            )

        override fun schedule() =
            SourceRequest({ "Понедельник" }, { listOf(ScheduleDay(it, emptyList())) })

        override fun streams(episode: Episode) = listOf(VideoStream(episode.file, "hls", 0))
    }

    private lateinit var storage: Storage
    private lateinit var source: FakeSource
    private lateinit var repository: Repository

    @Before
    fun setup() {
        storage = Storage(RuntimeEnvironment.getApplication())
        source = FakeSource()
        repository = Repository(storage, source)
    }

    @After
    fun close() = storage.close()

    @Test
    fun `repository reads every resource through the source`() = runBlocking {
        assertEquals("query|2", repository.releases(" query ", page = 2).items.single().title)
        assertEquals(listOf(2026), repository.options().value.years)
        assertEquals("Понедельник", repository.schedule().value.single().title)
        val (release, episodes) = repository.playback(Release(7, "Seven"))
        assertEquals(7, release.id)
        assertEquals("script", episodes.single().title)
        assertEquals(listOf("hls"), repository.streams(episodes.single()).map { it.type })
        assertEquals(mapOf("X-Test" to "1"), repository.streamHeaders)
    }

    @Test
    fun `source responses are cached and a failed refresh falls back to them`() = runBlocking {
        assertFalse(repository.releases().stale)
        repository.releases()
        assertEquals(1, source.releaseFetches)
        source.offline = true
        val fallback = repository.releases(refresh = true)
        assertEquals(2, source.releaseFetches)
        assertTrue(fallback.stale)
        assertEquals("|1", fallback.items.single().title)
    }

    @Test
    fun `auxiliary downloads go through the repository cache`() = runBlocking {
        repository.episodes(repository.detail(Release(1, "")).value)
        repository.episodes(repository.detail(Release(2, "")).value)
        assertEquals(1, source.scriptFetches)
    }
}
