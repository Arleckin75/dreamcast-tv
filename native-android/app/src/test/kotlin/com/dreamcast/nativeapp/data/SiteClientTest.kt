package com.dreamcast.nativeapp.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SiteClientTest {
    @Test
    fun `permanent HTTP failures are not retried`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            try {
                SiteClient(server.url("/").toString()).html("/")
                fail("Expected HTTP failure")
            } catch (_: java.io.IOException) {}
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `addresses on another host are refused without a request`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = SiteClient(server.url("/").toString().trimEnd('/'))
            val foreign =
                listOf("https://example.com/home/release/1", "http://example.com:${server.port}/")
            for (url in foreign) {
                try {
                    client.html(url)
                    fail("Expected $url to be refused")
                } catch (_: java.io.IOException) {}
            }
            server.enqueue(MockResponse().setBody("page"))
            assertEquals("page", client.html(server.url("/home/release/1").toString()))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `an attempt that times out is retried within the total budget`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(2) {
                server.enqueue(
                    MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                )
            }
            server.enqueue(MockResponse().setBody("page"))
            val quick =
                okhttp3.OkHttpClient.Builder()
                    .readTimeout(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
            val client = SiteClient(server.url("/").toString().trimEnd('/'), quick)
            assertEquals("page", client.html("/"))
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `the total budget ends a load that keeps timing out`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(3) {
                server.enqueue(
                    MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                )
            }
            val slow =
                okhttp3.OkHttpClient.Builder()
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            val client =
                SiteClient(server.url("/").toString().trimEnd('/'), slow, totalTimeoutMs = 400)
            val started = System.nanoTime()
            try {
                client.html("/")
                fail("Expected a timeout")
            } catch (_: java.io.IOException) {}
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3000)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `server filters use site identifiers and pagination`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(2) { server.enqueue(MockResponse().setBody("{\"releases\":[],\"count\":0}")) }
            val client = SiteClient(server.url("/").toString().trimEnd('/'))
            client.releases("тест", CatalogFilter(2024, setOf("romance", "magic")), 2)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("dateissue%5B%5D=2024"))
            assertTrue(body.contains("genres%5B%5D=magic"))
            assertTrue(body.contains("genres%5B%5D=romance"))
            assertTrue(body.contains("pageNumber=2"))
            client.releases()
            val reset = server.takeRequest().body.readUtf8()
            assertFalse(reset.contains("genres"))
            assertFalse(reset.contains("dateissue"))
        } finally {
            server.shutdown()
        }
    }
}
