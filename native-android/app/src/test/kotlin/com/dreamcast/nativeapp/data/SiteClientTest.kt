package com.dreamcast.nativeapp.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SiteClientTest {
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
