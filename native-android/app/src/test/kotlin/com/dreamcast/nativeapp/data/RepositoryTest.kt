package com.dreamcast.nativeapp.data

import android.app.Application
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class RepositoryTest {
    @Test
    fun `invalid refresh preserves cached data and next valid refresh replaces it`() = runBlocking {
        fun response(title: String) =
            MockResponse().setBody("""{"releases":[{"id":1,"russian":"$title"}],"count":1}""")
        server.enqueue(response("Original"))
        val original = repository.releases()
        server.enqueue(MockResponse().setBody("invalid JSON"))
        val fallback = repository.releases(refresh = true)
        assertTrue(fallback.stale)
        assertSame(original.items, fallback.items)
        val reused = repository.releases()
        assertFalse(reused.stale)
        assertSame(original.items, reused.items)
        server.enqueue(response("Recovered"))
        assertEquals("Recovered", repository.releases(refresh = true).items.single().title)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `playback renewal replaces parsed playlist and stream URLs`() = runBlocking {
        fun page(file: String): MockResponse {
            val payload =
                "#2" +
                    java.util.Base64.getEncoder()
                        .encodeToString(
                            """{"file":"https://example.com/$file.mp4"}""".toByteArray()
                        )
            return MockResponse()
                .setBody(
                    """<h3>Release</h3><script src="/js/playerjs.js"></script><script>new Playerjs('$payload')</script>"""
                )
        }
        val release = Release(1, "Release", url = server.url("/home/release/1").toString())
        server.enqueue(page("old"))
        val first = repository.playback(release).second
        assertTrue(PlayerJs.streams(first.single()).single().url.contains("old.mp4"))
        server.enqueue(page("renewed"))
        val renewed = repository.playback(release, refresh = true).second
        assertTrue(PlayerJs.streams(renewed.single()).single().url.contains("renewed.mp4"))
        val reused = repository.playback(release).second
        assertSame(renewed, reused)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `parsed cache reuses objects but refresh and disk clearing still fetch new data`() =
        runBlocking {
            fun response(title: String) =
                MockResponse().setBody("""{"releases":[{"id":1,"russian":"$title"}],"count":1}""")
            server.enqueue(response("Original"))
            val first = repository.releases()
            val reused = repository.releases()
            assertSame(first.items, reused.items)
            assertEquals(1, server.requestCount)
            server.enqueue(response("Updated"))
            assertEquals("Updated", repository.releases(refresh = true).items.single().title)
            storage.clearCache()
            server.enqueue(response("After clear"))
            assertEquals("After clear", repository.releases().items.single().title)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `parsed objects do not hide external disk changes or expiration`() = runBlocking {
        val key = "dreamcast:releases:ongoing:1:16"
        fun json(title: String) = """{"releases":[{"id":1,"russian":"$title"}],"count":1}"""
        storage.putCache(key, json("First"))
        assertEquals("First", repository.releases().items.single().title)
        storage.putCache(key, json("External update"))
        assertEquals("External update", repository.releases().items.single().title)
        storage.retention(-1)
        server.enqueue(MockResponse().setBody(json("Renewed")))
        assertEquals("Renewed", repository.releases().items.single().title)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `detail parser fallback follows changed release metadata`() = runBlocking {
        storage.putCache(
            "dreamcast:detail:1",
            """{"playerPayload":"cached","playerScriptUrl":"script"}""",
        )
        val first = repository.detail(Release(1, "First"))
        val second = repository.detail(Release(1, "Second"))
        assertEquals("First", first.value.release.title)
        assertEquals("Second", second.value.release.title)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `playback reuses fresh playlist and explicit renewal refetches the page`() = runBlocking {
        val payload =
            "#2" +
                java.util.Base64.getEncoder()
                    .encodeToString("""{"file":"https://example.com/one.mp4"}""".toByteArray())
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """<h3>Release</h3><script src="/js/playerjs.js"></script><script>new Playerjs('$payload')</script>"""
                    )
            )
        }
        val release = Release(1, "Release", url = server.url("/home/release/1").toString())
        assertEquals(1, repository.playback(release).second.size)
        assertEquals(1, repository.playback(release).second.size)
        assertEquals(1, server.requestCount)
        repository.playback(release, refresh = true)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `uses existing Flutter catalog and episode caches without network`() = runBlocking {
        storage.putCache(
            "dreamcast:releases:ongoing:1:16",
            """{"releases":[{"id":1,"russian":"До переноса","url":"/home/release/1"}],"count":1}""",
        )
        storage.putCache(
            "dreamcast:detail:1",
            JSONObject()
                .put("release", Release(1, "До переноса").json())
                .put("title", "До переноса")
                .put("playerPayload", "cached")
                .put("playerScriptUrl", "https://example.com/js/playerjs.js")
                .toString(),
        )
        storage.putCache(
            "dreamcast:episodes:1",
            """{"episodes":[{"id":"1-1","ordinal":1,"title":"Серия 1","file":"https://example.com/one.mp4"}]}""",
        )
        assertEquals("До переноса", repository.releases().items.single().title)
        val detail = repository.detail(Release(1, "")).value
        assertEquals("До переноса", detail.release.title)
        assertEquals("1-1", repository.episodes(detail).value.single().id)
        assertEquals(0, server.requestCount)
    }

    private lateinit var storage: Storage
    private lateinit var server: MockWebServer
    private lateinit var repository: Repository

    @Before
    fun setup() {
        storage = Storage(RuntimeEnvironment.getApplication())
        server = MockWebServer()
        server.start()
        repository = Repository(storage, SiteClient(server.url("/").toString().trimEnd('/')))
    }

    @After
    fun close() {
        server.shutdown()
        storage.close()
    }

    @Test
    fun `fresh catalog cache avoids network and filters have independent keys`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("{\"releases\":[],\"count\":0}")) }
        repository.releases(filter = CatalogFilter(2024))
        repository.releases(filter = CatalogFilter(2024))
        repository.releases(filter = CatalogFilter(2023))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `failed refresh falls back to cache and reports stale`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"releases\":[],\"count\":0}"))
        assertFalse(repository.releases().stale)
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        assertTrue(repository.releases(refresh = true).stale)
    }

    @Test
    fun `cancellation does not become a cache fallback`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"releases\":[],\"count\":0}"))
        repository.releases()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val request = async { repository.releases(refresh = true) }
        delay(100)
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
    }
}
