package com.dreamcast.nativeapp

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.dreamcast.nativeapp.data.*
import java.time.Duration
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiRefreshRegressionTest {
    private lateinit var app: DreamApplication
    private lateinit var model: AppViewModel
    private val models = ViewModelStore()

    @Before
    fun setup() {
        // Attach the application without starting its notification scheduler.
        app = DreamApplication()
        ReflectionHelpers.callInstanceMethod<Unit>(
            app,
            "attach",
            ClassParameter.from(
                Context::class.java,
                RuntimeEnvironment.getApplication().baseContext,
            ),
        )
        app.repository = Repository(app.storage)
        model = AppViewModel(app)
        models.put("model", model)
        await { model.uiReady }
    }

    @After
    fun cleanup() {
        models.clear()
        app.storage.close()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for UI refresh", condition())
    }

    @Test
    fun rapidMutationsAcrossSectionsAreAllPublishedWithoutReplacingProgress() {
        val previous = model.ui
        val show = Release(77, "Show")
        model.mutateLocal(UiSection.SETTINGS) { put("settings.theme_mode", "light") }
        model.mutateLocal(UiSection.BOOKMARKS) { bookmark(show, LibraryStatus.watching) }
        model.mutateLocal(UiSection.SEARCH) { saveSearch("Show") }
        model.mutateLocal(UiSection.PROFILE) { saveProfile("Viewer") }
        model.mutateLocal(UiSection.CACHE) { retention(24) }
        await {
            model.ui.theme == "light" &&
                model.ui.bookmarksById[77] != null &&
                model.ui.searchHistory == listOf("Show") &&
                model.ui.profile == "Viewer" &&
                model.ui.retentionHours == 24L
        }
        assertSame(previous.progressByRelease, model.ui.progressByRelease)
        assertSame(previous.notifications, model.ui.notifications)
    }

    @Test
    fun externalPreferenceChangesUpdateSubscriptionsAndUnreadCounts() {
        val show = Release(78, "Show")
        val key = "notifications.history.item.test"
        val notification = JSONObject().put("release", show.json()).put("message", "New episode")
        app.storage.subscribe(show, 2)
        app.storage.put(key, notification.toString())
        await { 78 in model.ui.subscriptions && model.ui.unreadNotifications == 1 }
        app.storage.remove("notifications.release.item.78")
        app.storage.put(key, notification.put("readAt", "2026-11-04T12:00:00Z").toString())
        await { 78 !in model.ui.subscriptions && model.ui.unreadNotifications == 0 }
        app.storage.remove(key)
        await { model.ui.notifications.isEmpty() }
    }

    @Test
    fun returningFromPlayerWaitsForQueuedProgressAndPreservesSettings() {
        model.mutateLocal(UiSection.SETTINGS) { put("settings.accent", "rose") }
        await { model.ui.accent == "rose" }
        val show = Release(79, "Show")
        val episode = Episode("one", 79, 1, "Episode", "")
        app.progressWriter.save(ProgressSnapshot(show, episode, 12345, 100000, false))
        model.refreshPlayerProgress(app)
        await { model.ui.progressByRelease[79]?.get("one")?.positionMs == 12345L }
        assertEquals("rose", model.ui.accent)
        assertEquals("one", model.ui.continueWatching.single().episodeId)
    }
}
