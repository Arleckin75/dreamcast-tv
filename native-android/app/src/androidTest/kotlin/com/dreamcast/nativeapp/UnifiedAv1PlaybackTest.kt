package com.dreamcast.nativeapp

import android.content.Intent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dreamcast.nativeapp.data.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.hamcrest.Matchers.not
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class UnifiedAv1PlaybackTest {
    private fun engine(activity: PlayerActivity) = activity.javaClass.getDeclaredField("player")
        .apply { isAccessible = true }.get(activity) as? ExoPlayer
    private fun decoder(activity: PlayerActivity) = activity.javaClass.getDeclaredField("videoDecoderName")
        .apply { isAccessible = true }.get(activity) as String
    private fun menu(activity: PlayerActivity) = activity.javaClass.getDeclaredMethod("chooseDecoder")
        .apply { isAccessible = true }.invoke(activity)

    @Test fun autoDetectsHardwareAndPreservesPlaybackWhenChangingMode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as DreamApplication
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val name = request.path?.substringBefore('?')?.substringAfterLast('/') ?: ""
                if (name == "master.m3u8") return MockResponse().setBody(
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=1280x720,CODECS=\"av01.0.05M.08,mp4a.40.2\"\nmedia.m3u8")
                if (name.endsWith("m3u8") || name.endsWith("mp4") || name.endsWith("m4s")) {
                    return MockResponse().setBody(Buffer().write(instrumentation.context.assets.open("av1/$name").use { it.readBytes() }))
                        .setHeader("Content-Type", if (name.endsWith("m3u8")) "application/vnd.apple.mpegurl" else "video/mp4")
                }
                val playlist = JSONObject().put("file", server.url("/master.m3u8").toString()).toString()
                val payload = "#2" + Base64.getEncoder().encodeToString(playlist.toByteArray())
                return MockResponse().setBody("<h3>Unified AV1</h3><script src=\"/js/playerjs.js\"></script><script>new Playerjs('$payload')</script>")
            }
        }
        server.start()
        val oldRepository = app.repository
        val oldMode = app.storage.string("player.decoder_mode", "AUTO")
        app.storage.put("player.decoder_mode", "AUTO")
        app.repository = Repository(app.storage, SiteClient(server.url("/").toString().trimEnd('/')))
        val release = Release(991099, "Unified AV1", url = server.url("/release").toString())
        app.storage.clearCache()
        try {
            ActivityScenario.launch<PlayerActivity>(Intent(app, PlayerActivity::class.java)
                .putExtra("release", release.json().toString()).putExtra("episodeId", "991099-1")).use { scenario ->
                var hardware = false
                fun waitFor(condition: (PlayerActivity) -> Boolean) {
                    val until = System.nanoTime() + 25_000_000_000L
                    var satisfied = false
                    while (!satisfied && System.nanoTime() < until) {
                        scenario.onActivity { satisfied = condition(it) }
                        if (!satisfied) Thread.sleep(50)
                    }
                    assertTrue("Playback condition timed out", satisfied)
                }
                waitFor { decoder(it).isNotBlank() && (engine(it)?.currentPosition ?: 0) > 1200 }
                scenario.onActivity { activity ->
                    val player = engine(activity)!!
                    hardware = VideoDecoderCapabilities(app).hardware(player.videoFormat!!)
                    if (!hardware) assertEquals("libdav1d", decoder(activity))
                    else assertFalse("Auto must prefer real hardware", decoder(activity).contains("dav1d"))
                    player.pause()
                    menu(activity)
                }
                if (!hardware) onView(withText("Аппаратное — недоступно для этого видео")).inRoot(isDialog())
                    .check(matches(not(isEnabled())))
                else {
                    onView(withContentDescription("Аппаратное")).inRoot(isDialog()).check(matches(isEnabled())).perform(click())
                    waitFor { decoder(it).isNotBlank() && engine(it)?.playbackState == androidx.media3.common.Player.STATE_READY }
                    waitFor { it.hasWindowFocus() }
                    scenario.onActivity {
                        assertFalse(decoder(it).contains("dav1d"))
                        assertFalse(engine(it)!!.playWhenReady)
                        menu(it)
                    }
                }
                var pausedAt = 0L
                scenario.onActivity { pausedAt = engine(it)!!.currentPosition }
                onView(withContentDescription("Программное AV1 — dav1d")).inRoot(isDialog()).perform(click())
                waitFor { decoder(it) == "libdav1d" && engine(it)?.playbackState == androidx.media3.common.Player.STATE_READY }
                scenario.onActivity { activity ->
                    val player = engine(activity)!!
                    assertFalse(player.playWhenReady)
                    assertTrue("Mode switch must preserve position", kotlin.math.abs(player.currentPosition - pausedAt) < 500)
                    player.seekTo(5000)
                    player.play()
                }
                waitFor { (engine(it)?.currentPosition ?: 0) > 5500 }
                val supportsPip = app.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
                if (supportsPip) scenario.onActivity { engine(it)!!.pause() }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                if (supportsPip) scenario.onActivity {
                    assertFalse("Paused playback must remain paused after returning", engine(it)!!.playWhenReady)
                    engine(it)!!.play()
                }
                waitFor { engine(it)?.isPlaying == true && (engine(it)?.currentPosition ?: 0) > 6000 }
                waitFor { it.hasWindowFocus() }
                scenario.onActivity { menu(it) }
                onView(withContentDescription("Авто")).inRoot(isDialog()).perform(click())
                waitFor { decoder(it).isNotBlank() && engine(it)?.isPlaying == true }
                scenario.onActivity {
                    if (!hardware) assertEquals("libdav1d", decoder(it))
                    assertTrue(engine(it)!!.currentPosition > 5000)
                }
            }
        } finally {
            app.repository = oldRepository
            app.storage.put("player.decoder_mode", oldMode)
            app.storage.clearCache()
            server.shutdown()
        }
    }
}
