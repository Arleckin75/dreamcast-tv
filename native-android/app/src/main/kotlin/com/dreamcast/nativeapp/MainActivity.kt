package com.dreamcast.nativeapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.dreamcast.nativeapp.data.*
import com.dreamcast.nativeapp.ui.DreamApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Route(val name: String, val release: Release? = null, val argument: String = "") {
    val key
        get() = "$name:${release?.id ?: 0}:$argument"

    fun json() =
        JSONObject().put("name", name).put("release", release?.json()).put("argument", argument)
}

class FeedState {
    var page by mutableStateOf(1)
    var loadedPage by mutableStateOf(0)
    var items by mutableStateOf<List<Release>>(emptyList())
    var total by mutableStateOf(0)
    var stale by mutableStateOf(false)
    var exhausted by mutableStateOf(false)

    /** Guard repeated key/scroll events even before Compose has redrawn the loading state. */
    fun requestNextPage(): Boolean {
        if (page != loadedPage || exhausted || items.size >= total) return false
        page++
        return true
    }
}

class AppViewModel(app: android.app.Application) : AndroidViewModel(app) {
    val application = app as DreamApplication
    val storage = application.storage
    val repository = application.repository
    val updates =
        com.dreamcast.nativeapp.update.UpdateController(
            app,
            viewModelScope,
            com.dreamcast.nativeapp.update.UpdateChecker(storage, BuildConfig.VERSION_NAME),
        )
    var routes by mutableStateOf(listOf(Route("home")))
        private set

    var ui by mutableStateOf(UiSnapshot())
        private set

    var uiReady by mutableStateOf(false)
        private set

    private val localDataMutex = Mutex()
    private var localRefresh: Job? = null

    private val pendingSections = mutableSetOf<UiSection>()
    private val watchedPreferences =
        app.getSharedPreferences("dream_cast_native", android.content.Context.MODE_PRIVATE)
    private val preferenceListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, rawKey ->
            val sections = uiSectionsForPreference(rawKey)
            if (sections.isNotEmpty()) refreshLocalData(sections)
        }

    init {
        watchedPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        refreshLocalData()
    }

    override fun onCleared() {
        watchedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onCleared()
    }

    private fun refreshLocalData(sections: Set<UiSection> = UiSection.entries.toSet()) {
        pendingSections.addAll(sections)
        if (localRefresh?.isActive == true) return
        localRefresh =
            viewModelScope.launch {
                // Merge preference callbacks and explicit mutations, including multi-key changes.
                if (uiReady) kotlinx.coroutines.delay(75)
                while (pendingSections.isNotEmpty()) {
                    val requested = pendingSections.toSet()
                    pendingSections.clear()
                    val previous = ui
                    val progressIds = routes.mapNotNull { it.release?.id }.toSet()
                    try {
                        application.awaitStorageReady()
                        ui =
                            withContext(Dispatchers.IO) {
                                localDataMutex.withLock {
                                    storage.readUiSnapshot(previous, requested, progressIds)
                                }
                            }
                        uiReady = true
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        android.util.Log.e("DCTV_UI", "Could not refresh local data", error)
                    }
                }
            }
    }

    fun mutateLocal(section: UiSection, after: () -> Unit = {}, action: Storage.() -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { localDataMutex.withLock { storage.action() } }
            changed(section)
            after()
        }
    }

    fun refreshPlayerProgress(context: android.content.Context) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            application.progressWriter.awaitPending()
            withContext(Dispatchers.IO) {
                    localDataMutex.withLock { storage.syncSoftwareProgress(appContext) }
                }
            changed(UiSection.PROGRESS)
        }
    }

    var revision by mutableStateOf(0)
    var playbackError by mutableStateOf<String?>(null)
    var pendingStreams by mutableStateOf<Pair<Pair<Release, Episode>, List<VideoStream>>?>(null)
    var preparing by mutableStateOf(false)
    private val feeds = FeedCache()

    fun feed(key: String): FeedState = feeds.getOrCreate(key)

    fun trimFeeds(key: String) = feeds.trim(key)

    val route
        get() = routes.last()

    fun navigate(route: Route) {
        routes = routes + route
        if (route.release != null) changed(UiSection.PROGRESS)
    }

    fun selectSection(name: String) {
        routes = if (name == "home") listOf(Route("home")) else listOf(Route("home"), Route(name))
    }

    fun back() {
        if (routes.size > 1) routes = routes.dropLast(1)
        changed(UiSection.PROGRESS)
    }

    fun changed(section: UiSection) {
        if (section == UiSection.CACHE) revision++
        refreshLocalData(setOf(section))
    }

    fun saveNavigation() = JSONArray(routes.map { it.json() }).toString()

    fun restoreNavigation(text: String?) {
        if (text == null) return
        runCatching {
            routes =
                JSONArray(text)
                    .objects()
                    .map {
                        Route(
                            it.getString("name"),
                            it.optJSONObject("release")?.let(Release::fromJson),
                            it.text("argument"),
                        )
                    }
                    .ifEmpty { listOf(Route("home")) }
        }
    }

    fun prepare(
        activity: MainActivity,
        release: Release,
        episodeId: String,
        preferredTechnology: String? = null,
    ) {
        if (preparing) return
        preparing = true
        viewModelScope.launch {
            try {
                val (playbackRelease, episodes) = repository.playback(release)
                val episode =
                    episodes.firstOrNull { it.id == episodeId } ?: error("Серия не найдена")
                val streams = repository.streams(episode)
                require(streams.isNotEmpty()) { "Для серии не найдены потоки" }
                val technology =
                    preferredTechnology
                        ?: storage.string("player.preferred_stream_technology", "none")
                if (technology == "none") pendingStreams = (playbackRelease to episode) to streams
                else
                    activity.launchPlayback(
                        playbackRelease,
                        episode,
                        streams.firstOrNull { it.type == technology } ?: streams.first(),
                    )
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                playbackError = error.message ?: "Не удалось открыть серию"
            } finally {
                preparing = false
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private var playbackLaunch: Job? = null
    val model: AppViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (savedInstanceState != null) {
            model.restoreNavigation(savedInstanceState.getString("navigation"))
        } else consumeIntent(intent)
        if (
            Build.VERSION.SDK_INT >= 33 &&
                !model.storage.boolean("native.notification_permission_requested")
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 51)
            model.storage.put("native.notification_permission_requested", true)
        }
        setContent { DreamApp(model, this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent) {
        intent.getStringExtra("release")?.let { text ->
            runCatching {
                val release = Release.fromJson(JSONObject(text))
                intent.getStringExtra("notificationId")?.let { id ->
                    val record = model.storage.string("notifications.history.item.$id")
                    if (record.isNotBlank())
                        model.storage.put(
                            "notifications.history.item.$id",
                            JSONObject(record)
                                .put("readAt", java.time.Instant.now().toString())
                                .toString(),
                        )
                }
                model.navigate(Route("detail", release))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Continue an update install after the user allowed it in system settings.
        model.updates.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("navigation", model.saveNavigation())
        super.onSaveInstanceState(outState)
    }

    fun launchPlayback(release: Release, episode: Episode, stream: VideoStream) {
        if (playbackLaunch?.isActive == true) return
        model.pendingStreams = null
        playbackLaunch =
            lifecycleScope.launch {
                try {
                    model.application.awaitStorageReady()
                    model.application.progressWriter.awaitPending()
                    PlayerLauncher.launch(this@MainActivity, release, episode, stream)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    model.playbackError = error.message ?: "Не удалось открыть плеер"
                }
            }
    }

}
