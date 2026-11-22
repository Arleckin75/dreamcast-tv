package com.dreamcast.nativeapp.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ReleaseAddressTest {
    private val page =
        """
        <html><head>
          <link rel="canonical" href="https://dreamerscast.com/home/release/91-pererozhdenie-diadi" />
          <script src="/js/playerjs-2026.js"></script>
        </head><body>
          <div class="postItem"><h3>Перерождение Дяди / Isekai Ojisan</h3></div>
          <div class="postItem"><span>Год:</span> <a href="#">2022</a></div>
          <div class="postItem"><span>Сезон:</span> <a href="#">Лето</a></div>
          <div class="postItem"><span>Студия:</span> <a href="#">AtelierPontdarc</a></div>
          <div class="postItem"><span>Жанр:</span> <a href="#">Комедия</a>,<a href="#">Фэнтези</a></div>
          <div class="postDesc">Описание</div>
          <script>new Playerjs("#0payload")</script>
        </body></html>
        """
            .trimIndent()

    private fun catalog(id: Int, url: String) =
        JSONObject()
            .put("releases", JSONArray().put(JSONObject().put("id", id).put("url", url)))
            .put("total", 1)
            .toString()

    @Test
    fun `page fills original title, year, season, studio, genres and the full address`() {
        val release = SiteParser.detail(page, Release(91, "Перерождение Дяди")).release
        assertEquals("Перерождение Дяди", release.title)
        assertEquals("Isekai Ojisan", release.originalTitle)
        assertEquals(2022, release.year)
        assertEquals("Лето", release.season)
        assertEquals("AtelierPontdarc", release.studio)
        assertEquals("Комедия, Фэнтези", release.genres)
        assertEquals("https://dreamerscast.com/home/release/91-pererozhdenie-diadi", release.url)
    }

    @Test
    fun `a canonical address of another release or host is ignored`() {
        val other = page.replace("dreamerscast.com/home/release/91-", "example.com/home/release/91-")
        assertEquals(
            "https://dreamerscast.com/home/release/91",
            SiteParser.detail(other, Release(91, "")).release.url,
        )
    }

    @Test
    fun `a bare id that answers 404 is found through the catalog`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody(catalog(91, "/home/release/91-pererozhdenie-diadi")))
            server.enqueue(MockResponse().setBody(page))
            val source = DreamcastSiteSource(SiteClient(server.url("/").toString().trimEnd('/')))
            val bare = Release(91, "Перерождение Дяди", url = server.url("/home/release/91").toString())
            val request = source.detail(bare)
            val detail = request.parse(request.fetch())
            assertEquals("Isekai Ojisan", detail.release.originalTitle)
            assertEquals("/home/release/91", server.takeRequest().path)
            assertEquals("POST", server.takeRequest().method)
            assertEquals("/home/release/91-pererozhdenie-diadi", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a 404 for a release missing from the catalog stays an error`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody(catalog(5, "/home/release/5-other")))
            server.enqueue(MockResponse().setBody(catalog(5, "/home/release/5-other")))
            val source = DreamcastSiteSource(SiteClient(server.url("/").toString().trimEnd('/')))
            val bare =
                Release(91, "Перерождение Дяди", "Isekai Ojisan", url = server.url("/home/release/91").toString())
            try {
                source.detail(bare).fetch()
                fail("Expected the 404 to stay")
            } catch (e: java.io.IOException) {
                assertEquals("Ошибка сервера: 404", e.message)
            }
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
