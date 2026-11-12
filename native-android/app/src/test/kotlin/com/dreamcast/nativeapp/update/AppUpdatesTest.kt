package com.dreamcast.nativeapp.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    @Test
    fun `parses the unified APK and its checksum from the latest release`() {
        val release = parseLatestRelease(releaseJson("v0.9.5"))!!
        assertEquals("0.9.5", release.version)
        assertEquals("DreamCast-TV-v0.9.5-unified-release.apk", release.apkName)
        assertEquals("https://example.test/apk", release.apkUrl)
        assertEquals("https://example.test/sha", release.checksumUrl)
        assertEquals(5_466_335L, release.apkSize)
        assertEquals("https://github.com/Arleckin75/dreamcast-tv/releases/tag/v0.9.5", release.pageUrl)
        assertEquals("Исправления", release.notes)
    }

    @Test
    fun `releases that cannot be verified or are not final are ignored`() {
        assertNull(parseLatestRelease(releaseJson("v0.9.5", checksum = false)))
        assertNull(parseLatestRelease(releaseJson("v0.9.5", apk = false)))
        assertNull(parseLatestRelease(releaseJson("v0.9.5", prerelease = true)))
        assertNull(parseLatestRelease(releaseJson("nightly")))
    }

    @Test
    fun `compares dotted versions numerically`() {
        assertTrue(isNewerVersion("0.9.5", "0.9.4"))
        assertTrue(isNewerVersion("0.10.0", "0.9.4"))
        assertTrue(isNewerVersion("1.0", "0.9.9"))
        assertFalse(isNewerVersion("0.9.4", "0.9.4"))
        assertFalse(isNewerVersion("0.9.4.0", "0.9.4"))
        assertFalse(isNewerVersion("0.9.3", "0.9.4"))
        assertFalse(isNewerVersion("0.9.5-beta", "0.9.4"))
    }

    @Test
    fun `reads sha256sum output`() {
        val hash = "A".repeat(64)
        assertEquals("a".repeat(64), parseSha256("$hash  DreamCast-TV.apk\n"))
        assertNull(parseSha256("not a hash"))
        assertNull(parseSha256(""))
    }

    @Test
    fun `notes are shortened for the dialog`() {
        val notes = (1..20).joinToString("\n\n") { "Строка $it" }
        val short = shortNotes(notes, maxLines = 3)
        assertEquals("Строка 1\nСтрока 2\nСтрока 3\n…", short)
    }

    companion object {
        fun releaseJson(
            tag: String,
            apk: Boolean = true,
            checksum: Boolean = true,
            prerelease: Boolean = false,
            apkUrl: String = "https://example.test/apk",
            checksumUrl: String = "https://example.test/sha",
        ): String {
            val name = "DreamCast-TV-$tag-unified-release.apk"
            val assets = JSONArray()
            if (apk)
                assets.put(
                    JSONObject()
                        .put("name", name)
                        .put("size", 5_466_335)
                        .put("browser_download_url", apkUrl)
                )
            if (checksum)
                assets.put(
                    JSONObject().put("name", "$name.sha256").put("browser_download_url", checksumUrl)
                )
            return JSONObject()
                .put("tag_name", tag)
                .put("draft", false)
                .put("prerelease", prerelease)
                .put("body", "Исправления")
                .put("html_url", "https://github.com/Arleckin75/dreamcast-tv/releases/tag/$tag")
                .put("assets", assets)
                .toString()
        }
    }
}
