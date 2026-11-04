package com.dreamcast.nativeapp.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
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
class StorageTest {
    private lateinit var context: Context
    private lateinit var storage: Storage

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        storage = Storage(context)
    }

    @After
    fun close() {
        storage.close()
    }

    @Test
    fun `legacy numeric quality preferences remain readable`() {
        context
            .getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            .edit()
            .putLong("flutter.software_av1_quality", 1080)
            .commit()
        assertEquals(1080L, storage.number("software_av1_quality", 720))
        storage.put("software_av1_quality", "480")
        assertEquals(480L, storage.number("software_av1_quality", 720))
    }

    @Test
    fun `manual restart overrides older VLC progress even in the same second`() {
        val release = Release(1, "Релиз")
        val episode = Episode("one", 1, 1, "Серия", "")
        val vlc = context.getSharedPreferences("dream_cast_vlc_progress", Context.MODE_PRIVATE)
        vlc.edit()
            .putLong("1:one", 50000)
            .putLong("1:one:updatedAt", System.currentTimeMillis() - 10)
            .commit()
        storage.saveProgress(release, episode, 0, 100000)
        storage.syncVlcProgress(context)
        assertEquals(0L, storage.progress(1, "one")!!.positionMs)
    }

    @Test
    fun `reads old Flutter settings bookmarks and subscriptions`() {
        val release = Release(540, "Релиз", posterUrl = "https://cdn.example.com/a.jpg")
        val old = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        old.edit()
            .putString("flutter.settings.theme_mode", "light")
            .putBoolean("flutter.settings.dynamic_color", false)
            .putString("flutter.cache.retention", "forever")
            .putString(
                "flutter.library.release.item.540",
                JSONObject().put("release", release.json()).put("status", "planned").toString(),
            )
            .putString(
                "flutter.notifications.release.item.540",
                JSONObject().put("release", release.json()).put("lastKnownEpisodes", 6).toString(),
            )
            .commit()
        assertEquals("light", storage.string("settings.theme_mode"))
        assertFalse(storage.boolean("settings.dynamic_color", true))
        assertEquals(0L, storage.retentionHours())
        assertEquals(Bookmark(release, LibraryStatus.planned), storage.bookmarks().single())
        assertTrue(storage.subscribed(540))
        storage.bookmark(release, null)
        assertTrue(storage.bookmarks().isEmpty())
        storage.remove("notifications.release.item.540")
        assertFalse(storage.subscribed(540))
    }

    @Test
    fun `legacy list encoding is retained and deletions persist`() {
        context
            .getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "flutter.search.history",
                "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu[\"Комедия\",\"Магия\"]",
            )
            .commit()
        assertEquals(listOf("Комедия", "Магия"), storage.searchHistory())
        storage.saveSearch("комедия")
        assertEquals(listOf("комедия", "Магия"), storage.searchHistory())
        storage.removeSearch("Магия")
        assertEquals(listOf("комедия"), storage.searchHistory())
    }

    @Test
    fun `progress survives reopen and watched threshold matches Flutter`() {
        val release = Release(9, "Релиз")
        val episode = Episode("9-1", 9, 1, "Серия 1", "")
        storage.saveProgress(release, episode, 45000, 100000)
        storage.close()
        storage = Storage(context)
        assertEquals(45000L, storage.progress(9, "9-1")!!.positionMs)
        assertFalse(storage.progress(9, "9-1")!!.watched)
        storage.saveProgress(release, episode, 90000, 100000)
        assertTrue(storage.progress(9, "9-1")!!.watched)
        storage.saveProgress(release, episode, 0, 100000)
        assertFalse(storage.progress(9, "9-1")!!.watched)
    }

    @Test
    fun `opening an existing Drift database retains rows and schema version`() {
        storage.close()
        val path = File(context.applicationInfo.dataDir, "app_flutter/dream_cast.sqlite")
        SQLiteDatabase.deleteDatabase(path)
        val schema =
            JSONObject(javaClass.classLoader!!.getResource("flutter_schema_v3.json")!!.readText())
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val tables = schema.getJSONArray("tables")
            for (i in 0 until tables.length()) db.execSQL(tables.getString(i))
            db.version = schema.getInt("version")
            db.execSQL("INSERT INTO profiles VALUES('legacy','До переноса',100,100,1)")
            db.execSQL(
                "INSERT INTO watch_entries VALUES('1','old','До переноса','Серия',NULL,1,12345,50000,0,100)"
            )
            db.execSQL("INSERT INTO playback_positions VALUES('1','old',12345,50000,100)")
        }
        storage = Storage(context)
        assertEquals("До переноса", storage.profile())
        assertEquals(12345L, storage.progress(1, "old")!!.positionMs)
        storage.saveProfile("После переноса")
        assertEquals("После переноса", storage.profile())
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(3, it.version)
        }
    }

    @Test
    fun `cache cleanup preserves bookmarks profile and progress`() {
        val release = Release(1, "Релиз")
        storage.saveProfile("TV")
        storage.bookmark(release, LibraryStatus.watching)
        storage.saveProgress(release, Episode("one", 1, 1, "Серия", ""), 10000, 50000)
        storage.putCache("key", "данные")
        assertEquals(1L, storage.cacheStats().first)
        storage.retention(0)
        assertNull(storage.cache("key")!!.expiresAt)
        storage.clearCache()
        assertEquals(0L, storage.cacheStats().first)
        assertEquals(1, storage.bookmarks().size)
        assertEquals("TV", storage.profile())
        assertNotNull(storage.progress(1, "one"))
    }
}
