package com.dreamcast.nativeapp.data

import java.net.URLEncoder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ParsersTest {
    @Test
    fun `decodes peppered hash one key configuration`() {
        val value = "{\"bk0\":\"alpha\"}"
        val alphabet = "ABCDEFGHIJKLMabcdefghijklmNOPQRSTUVWXYZnopqrstuvwxyz"
        val rotated = alphabet.drop(28) + alphabet.take(28)
        val encoded =
            salt(value)
                .map { if (it in alphabet) rotated[alphabet.indexOf(it)] else it }
                .joinToString("")
        assertEquals(value, PlayerJs.crypto("#1" + encoded))
    }

    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private fun encode(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun urlEncode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun playlist() =
        JSONObject()
            .put(
                "file",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "episode_1")
                            .put("title", "Серия 1")
                            .put(
                                "file",
                                "https://play.example.com/dash/show/manifest.mpd or https://play.example.com/hls/show/master.m3u8",
                            )
                    ),
            )

    private fun salt(text: String): String {
        val standard = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        val salt = "ABCDEFGHIJKLMabcdefghijklmNOPQRSTUVWXYZnopqrstuvwxyz0123456789+/="
        return encode(text).map { salt[standard.indexOf(it)] }.joinToString("")
    }

    @Test
    fun `parses original release HTML fixture`() {
        val result =
            SiteParser.detail(
                fixture("release_page.html").replace("{{PLAYER_PAYLOAD}}", "#0encoded-payload"),
                Release(1, ""),
            )
        assertEquals("Тестовый релиз Dream Cast", result.release.title)
        assertEquals("Описание с лишними пробелами.", result.release.description)
        assertEquals("https://cdn.example.test/poster.jpg", result.release.posterUrl)
        assertEquals("https://dreamerscast.com/js/playerjs-2026.js", result.scriptUrl)
    }

    @Test
    fun `unpacks original packed fixture`() {
        assertEquals("hello world hello", PlayerJs.unpack(fixture("playerjs_packed.js")))
    }

    @Test(timeout = 5000)
    fun `large packed scripts preserve ASCII tokens and punctuation`() {
        val payload = "0(1);_0;10;é0;0é;".repeat(30000)
        val source = "return p}('" + payload + "',36,2,'hello|world'.split('|'))"
        assertEquals("hello(world);_0;10;éhello;helloé;".repeat(30000), PlayerJs.unpack(source))
    }

    @Test
    fun `direct and URI encoded playlists retain episode IDs and stream choices`() {
        for (text in listOf(playlist().toString(), urlEncode(playlist().toString()))) {
            val episodes = PlayerJs.episodes(PlayerJs.decode("unused", "#2" + encode(text)), 540)
            assertEquals("episode_1", episodes.first().id)
            assertEquals(listOf("hls", "dash"), PlayerJs.streams(episodes.first()).map { it.type })
        }
    }

    @Test
    fun `legacy salt keys are removed from direct playlist`() {
        val keys =
            JSONObject().put("bk0", "alpha").put("bk1", "ключ !").put("bk2", "undefined").toString()
        val script = fixture("playerjs_direct.js").replace("{{CRYPT_CODE}}", "#0" + salt(keys))
        val text = encode(urlEncode(playlist().toString()))
        val payload =
            "#2" +
                text.take(36) +
                "//" +
                encode("alpha") +
                text.substring(36, 92) +
                "//" +
                encode(urlEncode("ключ !").replace("%21", "!")) +
                text.drop(92)
        assertEquals(
            "Серия 1",
            PlayerJs.episodes(PlayerJs.decode(script, payload), 540).first().title,
        )
    }

    @Test
    fun `supports trailing garbage after padded payload`() {
        val source = playlist().put("id", "player_padding").toString()
        var text = source
        while (!encode(text).endsWith("=")) text += " "
        assertEquals(
            1,
            PlayerJs.episodes(PlayerJs.decode("unused", "#2" + encode(text) + "garbage"), 1).size,
        )
    }

    @Test
    fun `single file receives stable episode identity`() {
        val episodes =
            PlayerJs.episodes(JSONObject().put("file", "https://example.com/video.mp4"), 9)
        assertEquals("9-1", episodes.single().id)
        assertEquals("mp4", PlayerJs.streams(episodes.single()).single().type)
    }

    @Test
    fun `API release mapping preserves current versus total episodes`() {
        val release =
            Release.fromJson(
                JSONObject()
                    .put("id", 540)
                    .put("russian", "Релиз")
                    .put("url", "/home/release/540")
                    .put("series", 6)
                    .put("currentSeries", 12)
                    .put("dateissue", 2024)
                    .put("image", "//cdn.example.com/a.jpg")
            )
        assertEquals(6, release.currentEpisodes)
        assertEquals(12, release.totalEpisodes)
        assertEquals("https://cdn.example.com/a.jpg", release.posterUrl)
        assertEquals(release, Release.fromJson(release.json()))
    }

    @Test
    fun `filters decode entities preserve IDs and sort years`() {
        val options =
            SiteParser.options(
                """<select id="dateissue"><option value="2020">2020</option><option value="2024">2024</option><option value="2020">2020</option></select>
            <select id="genres"><option value="romance">Романтика &amp; драма</option><option value="magic">Магия</option></select>"""
            )
        assertEquals(listOf(2024, 2020), options.years)
        assertEquals("Романтика & драма", options.genres.last().name)
        assertEquals("magic", options.genres.first().id)
    }

    @Test
    fun `parses schedule fixture and skips malformed cards`() {
        val days = SiteParser.schedule(fixture("schedule_page.html"))
        assertEquals(listOf("Понедельник", "Вторник"), days.map { it.title })
        assertEquals(emptyList<Release>(), days[1].releases)
        val (full, originalOnly) = days[0].releases
        assertEquals(2, days[0].releases.size)
        assertEquals(540, full.id)
        assertEquals("Тестовый релиз", full.title)
        assertEquals("Test Release", full.originalTitle)
        assertEquals("https://dreamerscast.com/home/release/540", full.url)
        assertEquals("https://cache.dreamerscast.com/uploads/540.jpg", full.posterUrl)
        assertEquals(2026, full.year)
        assertEquals(6, full.currentEpisodes)
        assertEquals(12, full.totalEpisodes)
        assertEquals("Драма", full.genres)
        assertEquals("Studio", full.studio)
        assertEquals("Original Only", originalOnly.title)
        assertEquals("https://cdn.example.test/541.jpg", originalOnly.posterUrl)
        assertEquals(3, originalOnly.currentEpisodes)
        assertEquals(0, originalOnly.totalEpisodes)
    }

    @Test
    fun `changed schedule markup is an error, not an empty schedule`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                SiteParser.schedule("<html><body><h4>Понедельник</h4><p>…</p></body></html>")
            }
        assertEquals("Расписание не найдено", error.message)
    }

    @Test
    fun `release page without player is reported`() {
        val page = fixture("release_page.html").replace("{{PLAYER_PAYLOAD}}", "#0payload")
        val noPayload = page.replace(Regex("""(?:new\s+)?Playerjs\s*\([^)]*\)"""), "")
        assertEquals(
            "Не найден PlayerJS payload",
            assertThrows(IllegalStateException::class.java) {
                    SiteParser.detail(noPayload, Release(1, ""))
                }
                .message,
        )
        val noScript = page.replace("/js/playerjs", "/js/other")
        assertEquals(
            "Не найден скрипт PlayerJS",
            assertThrows(IllegalStateException::class.java) {
                    SiteParser.detail(noScript, Release(1, ""))
                }
                .message,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `site failure is not an empty catalog`() {
        SiteParser.options("<html>Unavailable</html>")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty playlist`() {
        PlayerJs.episodes(JSONObject().put("file", JSONArray()), 1)
    }
}
