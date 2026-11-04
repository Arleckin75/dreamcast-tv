package com.dreamcast.nativeapp.data

import android.app.Application
import com.dreamcast.nativeapp.UiSection
import com.dreamcast.nativeapp.readUiSnapshot
import com.dreamcast.nativeapp.uiSectionsForPreference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class UiSnapshotTest {
    private lateinit var storage: Storage

    @Before
    fun setup() {
        storage = Storage(RuntimeEnvironment.getApplication())
    }

    @After
    fun close() {
        storage.close()
    }

    @Test
    fun `snapshot prepares latest continue items and per episode progress without losing watched marks`() {
        val show = Release(11, "Show")
        val watchedShow = Release(12, "Watched")
        val first = Episode("one", 11, 1, "Episode 1", "")
        val second = Episode("two", 11, 2, "Episode 2", "")
        storage.saveProgress(show, first, 10000, 100000)
        storage.saveProgress(show, second, 20000, 100000)
        storage.saveProgress(
            watchedShow,
            Episode("done", 12, 1, "Episode", ""),
            100000,
            100000,
            true,
        )
        storage.bookmark(show, LibraryStatus.planned)
        storage.subscribe(show, 2)
        storage.saveProfile("Viewer")
        storage.saveSearch("Show")
        storage.put("settings.theme_mode", "light")
        val snapshot = storage.readUiSnapshot()
        assertEquals("two", snapshot.continueWatching.single().episodeId)
        assertEquals(2, snapshot.progressByRelease[11]!!.size)
        assertTrue(snapshot.progressByRelease[12]!!["done"]!!.watched)
        assertEquals(LibraryStatus.planned, snapshot.bookmarksById[11]!!.status)
        assertTrue(11 in snapshot.subscriptions)
        assertEquals("Viewer", snapshot.profile)
        assertEquals(listOf("Show"), snapshot.searchHistory)
        assertEquals("light", snapshot.theme)
    }

    @Test
    fun `ready snapshot remains unchanged until refreshed after progress and notification writes`() {
        val show = Release(20, "Show")
        val episode = Episode("one", 20, 1, "Episode", "")
        storage.saveProgress(show, episode, 10000, 100000)
        val key = "notifications.history.item.example"
        val record =
            JSONObject()
                .put("release", show.json())
                .put("message", "New episode")
                .put("createdAt", "2026-11-04T10:00:00Z")
        storage.put(key, record.toString())
        val first = storage.readUiSnapshot()
        storage.saveProgress(show, episode, 30000, 100000)
        storage.put(key, record.put("readAt", "2026-11-04T10:01:00Z").toString())
        assertEquals(10000L, first.continueWatching.single().positionMs)
        assertEquals(1, first.unreadNotifications)
        assertTrue(first.notifications.single().unread)
        assertEquals("New episode", first.notifications.single().message)
        val refreshed = storage.readUiSnapshot()
        assertEquals(30000L, refreshed.continueWatching.single().positionMs)
        assertEquals(0, refreshed.unreadNotifications)
        assertFalse(refreshed.notifications.single().unread)
    }

    @Test
    fun `settings refresh does not open database or replace unrelated collections`() {
        val show = Release(31, "Show")
        storage.saveProgress(show, Episode("one", 31, 1, "Episode", ""), 100, 1000)
        storage.bookmark(show, LibraryStatus.planned)
        storage.saveSearch("Show")
        val before = storage.readUiSnapshot()
        storage.put("settings.theme_mode", "light")
        storage.put("player.preferred_stream_technology", "dash")
        // Any accidental progress/profile read would fail against this closed database.
        storage.close()
        val after = storage.readUiSnapshot(before, setOf(UiSection.SETTINGS))
        assertEquals("light", after.theme)
        assertEquals("dash", after.streamTechnology)
        assertSame(before.bookmarks, after.bookmarks)
        assertSame(before.progressByRelease, after.progressByRelease)
        assertSame(before.continueWatching, after.continueWatching)
        assertSame(before.searchHistory, after.searchHistory)
        assertSame(before.notifications, after.notifications)
        // Restore the fixture for @After.
        storage = Storage(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `partial updates merge without losing earlier changes`() {
        val show = Release(32, "Show")
        val before = storage.readUiSnapshot()
        storage.bookmark(show, LibraryStatus.watching)
        storage.saveSearch("Updated query")
        storage.put("settings.accent", "rose")
        val library = storage.readUiSnapshot(before, setOf(UiSection.BOOKMARKS))
        assertEquals(LibraryStatus.watching, library.bookmarksById[32]!!.status)
        assertSame(before.searchHistory, library.searchHistory)
        val merged = storage.readUiSnapshot(library, setOf(UiSection.SETTINGS, UiSection.SEARCH))
        assertSame(library.bookmarks, merged.bookmarks)
        assertEquals("rose", merged.accent)
        assertEquals(listOf("Updated query"), merged.searchHistory)
    }

    @Test
    fun `preference invalidation targets only affected sections including legacy keys`() {
        assertEquals(
            setOf(UiSection.SETTINGS),
            uiSectionsForPreference("flutter.settings.theme_mode"),
        )
        assertEquals(
            setOf(UiSection.SETTINGS),
            uiSectionsForPreference("player.preferred_stream_technology"),
        )
        assertEquals(setOf(UiSection.SEARCH), uiSectionsForPreference("search.history"))
        assertEquals(setOf(UiSection.BOOKMARKS), uiSectionsForPreference("library.release.item.1"))
        assertEquals(
            setOf(UiSection.SUBSCRIPTIONS),
            uiSectionsForPreference("notifications.release.item.1"),
        )
        assertEquals(
            setOf(UiSection.NOTIFICATIONS),
            uiSectionsForPreference("notifications.history.item.1"),
        )
        assertEquals(setOf(UiSection.CACHE), uiSectionsForPreference("cache.retention.native"))
        assertTrue(uiSectionsForPreference("progress.updatedAt.1:one").isEmpty())
        assertEquals(UiSection.entries.toSet(), uiSectionsForPreference(null))
    }
}
