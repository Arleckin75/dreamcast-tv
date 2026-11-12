package com.dreamcast.nativeapp

import android.app.Application
import android.graphics.Bitmap
import com.dreamcast.nativeapp.data.PlayerJs
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class SeekPreviewTest {
    private val base = "https://cache.dreamerscast.com/media/abc/thumbnails/thumbnails.vtt"

    /** Same layout as the site's tracks: no blank lines between cues. */
    private val sample =
        """
        WEBVTT
        00:00:00.000 --> 00:00:10.000
        img1.webp#xywh=0,0,160,90
        00:00:10.000 --> 00:00:20.000
        img1.webp#xywh=160,0,160,90
        00:04:10.000 --> 00:04:20.000
        img2.webp#xywh=0,0,160,90

        01:00:00.000 --> 01:00:10.000
        https://cdn.example.test/full.webp
        00:00:30.000 --> 00:00:20.000
        bad.webp#xywh=0,0,1,1
        """
            .trimIndent()

    @Test
    fun `parses sprite cues and resolves image paths`() {
        val track = ThumbnailTrack.parse(sample, base)
        assertEquals(4, track.cues.size)
        val second = track.cues[1]
        assertEquals(10_000L, second.startMs)
        assertEquals(20_000L, second.endMs)
        assertEquals("https://cache.dreamerscast.com/media/abc/thumbnails/img1.webp", second.imageUrl)
        assertEquals(listOf(160, 0, 160, 90), listOf(second.x, second.y, second.width, second.height))
        val whole = track.cues.last()
        assertEquals(3_600_000L, whole.startMs)
        assertEquals("https://cdn.example.test/full.webp", whole.imageUrl)
        assertEquals(0, whole.width)
    }

    @Test
    fun `frame lookup picks the cue containing the position`() {
        val track = ThumbnailTrack.parse(sample, base)
        assertEquals(0L, track.frameAt(0)?.startMs)
        assertEquals(0L, track.frameAt(9_999)?.startMs)
        assertEquals(10_000L, track.frameAt(10_000)?.startMs)
        // Between cues the closest earlier frame is shown.
        assertEquals(10_000L, track.frameAt(100_000)?.startMs)
        assertEquals(250_000L, track.frameAt(255_000)?.startMs)
        assertEquals(0L, track.frameAt(-5)?.startMs)
        assertNull(ThumbnailTrack(emptyList()).frameAt(0))
    }

    @Test
    fun `parses WebVTT timestamps`() {
        assertEquals(3_723_456L, ThumbnailTrack.parseTime("01:02:03.456"))
        assertEquals(62_500L, ThumbnailTrack.parseTime("01:02.500"))
        assertEquals(1_000L, ThumbnailTrack.parseTime("00:00:01,000"))
        assertNull(ThumbnailTrack.parseTime("abc"))
        assertNull(ThumbnailTrack.parseTime("10"))
    }

    @Test
    fun `scrub steps from the playback position, speeds up when held and stays in range`() {
        val scrubber = SeekScrubber()
        assertFalse(scrubber.active)
        assertEquals(70_000L, scrubber.step(1, 60_000, 1_500_000))
        assertEquals(80_000L, scrubber.step(1, 99_999, 1_500_000))
        assertEquals(110_000L, scrubber.step(1, 0, 1_500_000, repeatCount = 5))
        assertEquals(170_000L, scrubber.step(1, 0, 1_500_000, repeatCount = 20))
        assertEquals(160_000L, scrubber.step(-1, 0, 1_500_000))
        assertEquals(160_000L, scrubber.commit())
        assertFalse(scrubber.active)
        assertNull(scrubber.commit())

        assertEquals(0L, scrubber.step(-1, 5_000, 1_500_000))
        assertEquals(1_500_000L, SeekScrubber().step(1, 1_495_000, 1_500_000))
        scrubber.cancel()
        assertNull(scrubber.target)
    }

    @Test
    fun `playlist keeps the thumbnails track of each episode`() {
        val playlist =
            JSONObject()
                .put(
                    "file",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("file", "https://example.test/a.m3u8")
                                .put("thumbnails", base)
                        )
                        .put(
                            JSONObject()
                                .put("file", "https://example.test/b.m3u8")
                                .put("thumbnails", "http://insecure.test/t.vtt")
                        )
                        .put(JSONObject().put("file", "https://example.test/c.m3u8")),
                )
        val episodes = PlayerJs.episodes(playlist, 620)
        assertEquals(listOf(base, "", ""), episodes.map { it.thumbnails })
    }

    @Test
    fun `loader downloads the track with the source headers`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/media/abc/thumbnails/thumbnails.vtt").toString()
            server.enqueue(MockResponse().setBody(sample))
            val loader = ThumbnailLoader(mapOf("Referer" to "https://dreamerscast.com/"))
            // Images resolve against the http test server, so only the https cue remains.
            val track = loader.track(url)!!
            assertEquals(listOf("https://cdn.example.test/full.webp"), track.cues.map { it.imageUrl })
            assertEquals("https://dreamerscast.com/", server.takeRequest().getHeader("Referer"))
            assertSame(track, loader.track(url))
            assertEquals(1, server.requestCount)

            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(loader.track(server.url("/missing.vtt").toString()))
            assertNull(loader.track(""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `crop returns the cue region or the whole sprite`() {
        val sprite = Bitmap.createBitmap(800, 450, Bitmap.Config.RGB_565)
        val cue = ThumbnailCue(0, 10_000, "https://x.test/a.webp", 160, 90, 160, 90)
        val frame = ThumbnailLoader.crop(sprite, cue)!!
        assertEquals(160, frame.width)
        assertEquals(90, frame.height)
        assertSame(sprite, ThumbnailLoader.crop(sprite, cue.copy(width = 0, height = 0)))
        assertEquals(40, ThumbnailLoader.crop(sprite, cue.copy(x = 760))!!.width)
        assertNull(ThumbnailLoader.crop(sprite, cue.copy(x = 800)))
    }
}
