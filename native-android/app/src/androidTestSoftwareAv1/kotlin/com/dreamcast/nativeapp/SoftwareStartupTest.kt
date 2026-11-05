package com.dreamcast.nativeapp

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dreamcast.app.CosmosAv1PlayerActivity
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Cold first-player startup against local AV1; the server is never on the public network. */
@RunWith(AndroidJUnit4::class)
class SoftwareStartupTest {
    @Test
    fun localAv1FirstVideoOutput() {
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
        try {
            val started = SystemClock.elapsedRealtime()
            ActivityScenario.launch<CosmosAv1PlayerActivity>(
                    Intent(app, CosmosAv1PlayerActivity::class.java)
                        .putExtra("url", server.url("/master.m3u8").toString())
                        .putExtra("quality", 720)
                        .putExtra("diagnosticVlcLogging", true)
                        .putExtra("title", "Local AV1 startup test")
                )
                .use { scenario ->
                    var vout = 0
                    var position = 0L
                    while (
                        SystemClock.elapsedRealtime() - started < 90_000 &&
                            (vout <= 0 || position < 100)
                    ) {
                        scenario.onActivity { activity ->
                            vout =
                                CosmosAv1PlayerActivity::class
                                    .java
                                    .getDeclaredField("voutCount")
                                    .apply { isAccessible = true }
                                    .getInt(activity)
                            val player =
                                CosmosAv1PlayerActivity::class
                                    .java
                                    .getDeclaredField("player")
                                    .apply { isAccessible = true }
                                    .get(activity) as? org.videolan.libvlc.MediaPlayer
                            position = player?.time ?: 0
                        }
                        Thread.sleep(50)
                    }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    android.util.Log.i(
                        "DCTV_STARTUP",
                        "test.video.ready elapsedMs=$elapsed vout=$vout positionMs=$position",
                    )
                    // Stop through the player contract while its Surface is still present.
                    scenario.onActivity { it.onBackPressed() }
                    Thread.sleep(300)
                    if (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED)
                        scenario.onActivity { it.onBackPressed() }
                    Thread.sleep(1000)
                    assertTrue(
                        "AV1 video output must start; elapsedMs=$elapsed",
                        vout > 0 && position >= 100,
                    )
                    assertTrue(
                        "Local first playback must not wait for a font database; elapsedMs=$elapsed",
                        elapsed < 10_000,
                    )
                }
        } finally {
            server.shutdown()
        }
    }
}
