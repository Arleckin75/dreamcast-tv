package com.dreamcast.nativeapp

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dreamcast.nativeapp.data.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.hamcrest.Matchers.startsWith
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the real Media3 lifecycle and saved position using deterministic silent PCM media. */
@RunWith(AndroidJUnit4::class)
class HardwarePlaybackTest {

    @Test
    fun playbackSavesProgressWhenLeavingPlayer() {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as DreamApplication
        val server = MockWebServer()
        val bytes =
            ByteBuffer.allocate(44 + 8000 * 2 * 30)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray())
                    putInt(capacity() - 8)
                    put("WAVEfmt ".toByteArray())
                    putInt(16)
                    putShort(1)
                    putShort(1)
                    putInt(8000)
                    putInt(16000)
                    putShort(2)
                    putShort(16)
                    put("data".toByteArray())
                    putInt(capacity() - 44)
                }
                .array()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/sample.wav")
                        return MockResponse()
                            .setHeader("Content-Type", "audio/wav")
                            .setBody(Buffer().write(bytes))
                    val playlist =
                        JSONObject().put("file", server.url("/sample.wav").toString()).toString()
                    val payload = "#2" + Base64.getEncoder().encodeToString(playlist.toByteArray())
                    return MockResponse()
                        .setBody(
                            """<h3>Playback test</h3><script src="/js/playerjs.js"></script><script>new Playerjs('$payload')</script>"""
                        )
                }
            }
        server.start()
        val previous = app.repository
        app.repository =
            Repository(app.storage, SiteClient(server.url("/").toString().trimEnd('/')))
        val release = Release(990001, "Playback test", url = server.url("/release").toString())
        app.storage.clearCache()
        app.storage.saveProgress(release, Episode("990001-1", 990001, 1, "Серия 1", ""), 0, 0)
        try {
            ActivityScenario.launch<HardwarePlayerActivity>(
                    Intent(app, HardwarePlayerActivity::class.java)
                        .putExtra("release", release.json().toString())
                        .putExtra("episodeId", "990001-1")
                )
                .use { scenario ->
                    val deadline = System.nanoTime() + 25_000_000_000L
                    while (
                        (app.storage.progress(990001, "990001-1")?.positionMs ?: 0) <= 1000 &&
                            System.nanoTime() < deadline
                    ) Thread.sleep(100)
                    scenario.onActivity { player ->
                        player.onKeyDown(
                            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                            android.view.KeyEvent(
                                android.view.KeyEvent.ACTION_DOWN,
                                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                            ),
                        )
                    }
                    onView(withContentDescription("Настройки воспроизведения")).perform(click())
                    onView(withText(startsWith("Качество ·"))).perform(click())
                    onView(withContentDescription("720p")).perform(click())
                    onView(withContentDescription("Настройки воспроизведения")).perform(click())
                    onView(withText(startsWith("Скорость ·"))).perform(click())
                    onView(withContentDescription("1.5×")).perform(click())
                    onView(withContentDescription("Звук и субтитры")).perform(click())
                    onView(withContentDescription("Звук")).perform(click())
                    onView(withContentDescription("Выключены")).perform(click())
                    scenario.onActivity { activity ->
                        val field =
                            HardwarePlayerActivity::class.java.getDeclaredField("player").apply {
                                isAccessible = true
                            }
                        val engine = field.get(activity) as androidx.media3.exoplayer.ExoPlayer
                        assertTrue(
                            androidx.media3.common.C.TRACK_TYPE_AUDIO in
                                engine.trackSelectionParameters.disabledTrackTypes
                        )
                    }
                    onView(withContentDescription("Звук и субтитры")).perform(click())
                    onView(withContentDescription("Звук")).perform(click())
                    onView(withContentDescription("Авто")).perform(click())

                    InstrumentationRegistry.getInstrumentation()
                        .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP)
                    scenario.onActivity { activity ->
                        val field =
                            HardwarePlayerActivity::class.java.getDeclaredField("player").apply {
                                isAccessible = true
                            }
                        val engine = field.get(activity) as androidx.media3.exoplayer.ExoPlayer
                        assertFalse(
                            androidx.media3.common.C.TRACK_TYPE_AUDIO in
                                engine.trackSelectionParameters.disabledTrackTypes
                        )
                        assertEquals(720, engine.trackSelectionParameters.maxVideoHeight)
                        assertEquals(1.5f, engine.playbackParameters.speed)
                        val content =
                            activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                        fun timeline(view: android.view.View): android.view.View? {
                            if (view.contentDescription == "Позиция воспроизведения") return view
                            if (view is android.view.ViewGroup)
                                for (i in 0 until view.childCount) timeline(view.getChildAt(i))
                                    ?.let {
                                        return it
                                    }
                            return null
                        }
                        assertTrue(timeline(content)!!.requestFocus())
                    }
                    InstrumentationRegistry.getInstrumentation()
                        .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    androidx.test.espresso.Espresso.pressBack()
                    onView(withContentDescription("Настройки воспроизведения"))
                        .check(
                            androidx.test.espresso.assertion.ViewAssertions.matches(
                                org.hamcrest.Matchers.not(
                                    androidx.test.espresso.matcher.ViewMatchers.isDisplayed()
                                )
                            )
                        )
                    androidx.test.espresso.Espresso.pressBackUnconditionally()
                    val exitDeadline = System.nanoTime() + 5_000_000_000L
                    while (
                        scenario.state != Lifecycle.State.DESTROYED &&
                            System.nanoTime() < exitDeadline
                    ) Thread.sleep(100)
                    assertEquals(Lifecycle.State.DESTROYED, scenario.state)
                    val progress = app.storage.progress(990001, "990001-1")!!
                    assertTrue(progress.positionMs > 10000)
                    assertTrue(progress.durationMs >= 29000)
                    assertFalse(progress.watched)
                }
        } finally {
            app.repository = previous
            android.database.sqlite.SQLiteDatabase.openDatabase(
                    java.io.File(app.applicationInfo.dataDir, "app_flutter/dream_cast.sqlite").path,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
                )
                .use { db ->
                    db.delete("watch_entries", "release_id=?", arrayOf("990001"))
                    db.delete("playback_positions", "release_id=?", arrayOf("990001"))
                }
            app.storage.remove("progress.updatedAt.990001:990001-1")
            server.shutdown()
        }
    }
}
