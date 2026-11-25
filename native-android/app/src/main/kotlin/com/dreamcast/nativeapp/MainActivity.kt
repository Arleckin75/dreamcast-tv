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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.runtime.mutableIntStateOf

data class Route(val name: String, val release: Release? = null) {
    val key
        get() = "$name:${release?.id ?: 0}"

    // Navigation saved by 1.0.6 and earlier also has an "argument" field; it is ignored.
    fun json() = JSONObject().put("name", name).put("release", release?.json())
}

class FeedState {
    var page by mutableIntStateOf(1)
    var loadedPage by mutableIntStateOf(0)
    var items by mutableStateOf<List<Release>>(emptyList())
    var total by mutableIntStateOf(0)
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

    fun refreshPlayerProgress() {
        viewModelScope.launch {
            application.progressWriter.awaitPending()
            changed(UiSection.PROGRESS)
        }
    }

    var revision by mutableIntStateOf(0)
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
                        Route(it.getString("name"), it.optJSONObject("release")?.let(Release::fromJson))
                    }
                    .ifEmpty { listOf(Route("home")) }
        }
    }

    /** A player start for the screen that is open when it arrives (it may have been recreated). */
    class PlaybackLaunch(
        val release: Release,
        val episode: Episode,
        val stream: VideoStream,
        val onFailure: () -> Unit,
    )

    private val launchRequests = Channel<PlaybackLaunch>(Channel.BUFFERED)
    /** Collected by the open [MainActivity], which starts the player. */
    val playbackLaunches = launchRequests.receiveAsFlow()
    private var launching = false

    /**
     * Starts [episode] once saved progress is written. The player is opened by whichever
     * [MainActivity] is open then, never by one that was destroyed in the meantime.
     */
    fun launchPlayback(
        release: Release,
        episode: Episode,
        stream: VideoStream,
        /** Called when the player could not be opened, on top of showing the error. */
        onFailure: () -> Unit = {},
    ) {
        pendingStreams = null
        if (launching) return
        launching = true
        viewModelScope.launch {
            try {
                application.awaitStorageReady()
                application.progressWriter.awaitPending()
                launchRequests.send(PlaybackLaunch(release, episode, stream, onFailure))
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                playbackError = error.message ?: "Не удалось открыть плеер"
                onFailure()
            } finally {
                launching = false
            }
        }
    }

    fun prepare(
        release: Release,
        episodeId: String,
        preferredTechnology: String? = null,
        /** Called after a failed start, on top of showing [playbackError]. */
        onFailure: () -> Unit = {},
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
                        ?: storage.string("player.preferred_stream_technology", DEFAULT_STREAM_TECHNOLOGY)
                if (technology == "none") pendingStreams = (playbackRelease to episode) to streams
                else
                    launchPlayback(
                        playbackRelease,
                        episode,
                        streams.firstOrNull { it.type == technology } ?: streams.first(),
                        onFailure,
                    )
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                playbackError = error.message ?: "Не удалось открыть серию"
                onFailure()
            } finally {
                preparing = false
            }
        }
    }
}

class MainActivity : ComponentActivity() {
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
        setContent { DreamApp(model) }
        // Player starts come from the view model, so a recreated activity still receives them.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.playbackLaunches.collect { launch ->
                    try {
                        PlayerLauncher.launch(this@MainActivity, launch.release, launch.episode, launch.stream)
                    } catch (error: Exception) {
                        model.playbackError = error.message ?: "Не удалось открыть плеер"
                        launch.onFailure()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent) {
        intent.getStringExtra("release")?.let { text ->
            runCatching {
                // Any app can start this activity: keep only what identifies the release. The
                // poster, original title and the rest come from the site page, so another app
                // cannot make us load its image or send its text to AniList.
                val sent = Release.fromJson(JSONObject(text))
                val release = Release(sent.id, sent.title, url = sent.url)
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
        // The player saves progress in the background; show it once the saves have finished,
        // so returning from the player updates the episode list and "Continue watching".
        model.refreshPlayerProgress()
        // Continue an update install after the user allowed it in system settings.
        model.updates.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("navigation", model.saveNavigation())
        super.onSaveInstanceState(outState)
    }

}
