package com.dreamcast.nativeapp

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dreamcast.app.CosmosAv1PlayerActivity
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real dav1d/LibVLC playback of generated AV1 720p fMP4 HLS; no external network. */
@RunWith(AndroidJUnit4::class)
class SoftwarePlaybackTest {
    @Test
    fun av1PlaybackSavesPositionAndExitsCleanly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val name = request.path?.substringBefore('?')?.substringAfterLast('/') ?: ""
                    if (name == "master.m3u8")
                        return MockResponse()
                            .setBody(
                                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=1280x720,CODECS=\"av01.0.05M.08\"\nmedia.m3u8"
                            )
                    return try {
                        val bytes =
                            instrumentation.context.assets.open("av1/$name").use { it.readBytes() }
                        MockResponse()
                            .setBody(Buffer().write(bytes))
                            .setHeader(
                                "Content-Type",
                                if (name.endsWith("m3u8")) "application/vnd.apple.mpegurl"
                                else "video/mp4",
                            )
                    } catch (_: Exception) {
                        MockResponse().setResponseCode(404)
                    }
                }
            }
        server.start()
        val prefs = app.getSharedPreferences("dream_cast_vlc_progress", Context.MODE_PRIVATE)
        val key = "990002:test-av1"
        prefs.edit().remove(key).remove("$key:updatedAt").commit()
        try {
            ActivityScenario.launch<CosmosAv1PlayerActivity>(
                    Intent(app, CosmosAv1PlayerActivity::class.java)
                        .putExtra("url", server.url("/master.m3u8").toString())
                        .putExtra("quality", 720)
                        .putExtra("episodeKey", key)
                        .putExtra("title", "AV1 playback regression test")
                )
                .use { scenario ->
                    val deadline = System.nanoTime() + 10_000_000_000L
                    while (prefs.getLong(key, 0) < 1000 && System.nanoTime() < deadline) Thread
                        .sleep(100)
                    assertTrue(
                        "LibVLC must decode and advance AV1 720p playback",
                        prefs.getLong(key, 0) >= 1000,
                    )
                    scenario.moveToState(Lifecycle.State.CREATED)
                    Thread.sleep(500) // Exercise Surface destruction while the Activity is stopped.
                    val pausedPosition = prefs.getLong(key, 0)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    val resumeDeadline = System.nanoTime() + 15_000_000_000L
                    while (
                        prefs.getLong(key, 0) <= pausedPosition + 500 &&
                            System.nanoTime() < resumeDeadline
                    ) Thread.sleep(100)
                    assertTrue(
                        "Playback must resume after recreating SurfaceView",
                        prefs.getLong(key, 0) > pausedPosition + 500,
                    )
                    scenario.onActivity { activity ->
                        val vout =
                            CosmosAv1PlayerActivity::class
                                .java
                                .getDeclaredField("voutCount")
                                .apply { isAccessible = true }
                                .getInt(activity)
                        assertTrue(
                            "Resuming must restore video output, not only the playback clock",
                            vout > 0,
                        )
                    }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    Thread.sleep(300)
                    if (scenario.state != Lifecycle.State.DESTROYED)
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    val exitDeadline = System.nanoTime() + 5000_000_000L
                    while (
                        scenario.state != Lifecycle.State.DESTROYED &&
                            System.nanoTime() < exitDeadline
                    ) Thread.sleep(100)
                    assertEquals(Lifecycle.State.DESTROYED, scenario.state)
                    assertTrue(prefs.getLong(key, 0) >= 1000)
                }
        } finally {
            prefs.edit().remove(key).remove("$key:updatedAt").commit()
            server.shutdown()
        }
    }
}
