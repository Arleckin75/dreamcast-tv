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
import androidx.lifecycle.viewModelScope
import com.dreamcast.nativeapp.data.*
import com.dreamcast.nativeapp.ui.DreamApp
import kotlinx.coroutines.launch
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
    var loadedPage = 0
    var items by mutableStateOf<List<Release>>(emptyList())
    var total by mutableStateOf(0)
    var stale by mutableStateOf(false)
    var exhausted by mutableStateOf(false)
}

class AppViewModel(app: android.app.Application) : AndroidViewModel(app) {
    val application = app as DreamApplication
    val storage = application.storage
    val repository = application.repository
    var routes by mutableStateOf(listOf(Route("home")))
        private set

    var revision by mutableStateOf(0)
    var playbackError by mutableStateOf<String?>(null)
    var pendingStreams by mutableStateOf<Pair<Pair<Release, Episode>, List<VideoStream>>?>(null)
    var preparing by mutableStateOf(false)
    val feeds = mutableMapOf<String, FeedState>()
    val route
        get() = routes.last()

    val software
        get() = BuildConfig.FLAVOR == "softwareAv1"

    fun navigate(route: Route) {
        routes = routes + route
    }

    fun selectSection(name: String) {
        routes = if (name == "home") listOf(Route("home")) else listOf(Route("home"), Route(name))
    }

    fun back() {
        if (routes.size > 1) routes = routes.dropLast(1)
    }

    fun changed() {
        revision++
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

    fun prepare(activity: MainActivity, release: Release, episodeId: String) {
        if (preparing) return
        preparing = true
        viewModelScope.launch {
            try {
                val (playbackRelease, episodes) = repository.playback(release)
                val episode =
                    episodes.firstOrNull { it.id == episodeId } ?: error("Серия не найдена")
                val streams = PlayerJs.streams(episode)
                require(streams.isNotEmpty()) { "Для серии не найдены потоки" }
                val technology = storage.string("player.preferred_stream_technology", "none")
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
    val model: AppViewModel by viewModels()
    private var softwareRelease: Release? = null
    private var softwareEpisode: Episode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (savedInstanceState != null) {
            model.restoreNavigation(savedInstanceState.getString("navigation"))
            softwareRelease =
                savedInstanceState.getString("softwareRelease")?.let {
                    Release.fromJson(JSONObject(it))
                }
            softwareEpisode =
                savedInstanceState.getString("softwareEpisode")?.let { json ->
                    val obj = JSONObject(json)
                    Episode(
                        obj.getString("id"),
                        obj.getInt("releaseId"),
                        obj.getInt("ordinal"),
                        obj.getString("title"),
                        obj.getString("file"),
                    )
                }
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("navigation", model.saveNavigation())
        softwareRelease?.let { outState.putString("softwareRelease", it.json().toString()) }
        softwareEpisode?.let {
            outState.putString(
                "softwareEpisode",
                JSONObject()
                    .put("id", it.id)
                    .put("releaseId", it.releaseId)
                    .put("ordinal", it.ordinal)
                    .put("title", it.title)
                    .put("file", it.file)
                    .toString(),
            )
        }
        super.onSaveInstanceState(outState)
    }

    fun launchPlayback(release: Release, episode: Episode, stream: VideoStream) {
        model.pendingStreams = null
        val progress = model.storage.progress(release.id, episode.id)
        if (model.software) {
            model.storage.saveProgress(
                release,
                episode,
                if (progress?.watched == true) 0 else progress?.positionMs ?: 0,
                progress?.durationMs ?: 0,
            )
            softwareRelease = release
            softwareEpisode = episode
            SoftwareLauncher.launch(
                this,
                release,
                episode,
                stream,
                model.storage.progress(release.id, episode.id),
                model.storage,
            )
        } else HardwareLauncher.launch(this, release, episode, stream)
    }

    @Deprecated("Legacy LibVLC Activity result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7192 && resultCode == Activity.RESULT_OK && data != null) {
            val release = softwareRelease ?: return
            val episode = softwareEpisode ?: return
            if (!data.getBooleanExtra("playbackError", false))
                model.storage.saveProgress(
                    release,
                    episode,
                    data.getLongExtra("positionMs", 0),
                    data.getLongExtra("durationMs", 0),
                    data.getBooleanExtra("completed", false),
                )
            if (data.hasExtra("quality"))
                model.storage.put(
                    "software_av1_quality",
                    data.getIntExtra("quality", 720).toString(),
                )
            model.changed()
            if (data.getBooleanExtra("nextEpisode", false))
                model.viewModelScope.launch {
                    try {
                        val episodes = model.repository.playback(release).second
                        episodes.getOrNull(episode.ordinal)?.let {
                            model.prepare(this@MainActivity, release, it.id)
                        }
                    } catch (error: Exception) {
                        model.playbackError = error.message
                    }
                }
        }
    }

    override fun onResume() {
        super.onResume()
        model.storage.syncVlcProgress(this)
        model.changed()
    }
}
