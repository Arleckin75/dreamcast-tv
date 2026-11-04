package com.dreamcast.nativeapp

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.dreamcast.app.SoftwarePlayerControls
import com.dreamcast.app.showPlayerMenu
import com.dreamcast.nativeapp.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HardwarePlayerActivity : ComponentActivity() {
    private val app
        get() = application as DreamApplication

    private lateinit var release: Release
    private var episode: Episode? = null
    private var episodes = emptyList<Episode>()
    private var streams = emptyList<VideoStream>()
    private var activeStream: VideoStream? = null
    private var streamTypePreference: String? = null
    private var selectedQuality = 0
    private var selectedSpeed = 1f
    private var player: ExoPlayer? = null
    private lateinit var video: PlayerView
    private lateinit var controls: SoftwarePlayerControls
    private var loadJob: Job? = null
    private var resumePosition = 0L
    private var ready = false
    private var completed = false
    private var retryCount = 0
    private val failedStreams = mutableSetOf<String>()
    private var endDialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private val saveTick =
        object : Runnable {
            override fun run() {
                save()
                handler.postDelayed(this, 5000)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        release = Release.fromJson(JSONObject(intent.getStringExtra("release")!!))
        val root =
            object : FrameLayout(this) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (
                        event.action == KeyEvent.ACTION_DOWN &&
                            event.keyCode != KeyEvent.KEYCODE_BACK
                    )
                        reveal()
                    return super.dispatchKeyEvent(event)
                }
            }
        video =
            PlayerView(this).apply {
                keepScreenOn = true
                setShowSubtitleButton(true)
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        video.useController = false
        controls =
            SoftwarePlayerControls(
                this,
                root,
                android.graphics.Color.WHITE,
                android.graphics.Color.BLACK,
                onToggle = {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    reveal()
                },
                onSeek = ::seekBy,
                onRetry = {
                    retryCount = 0
                    episode?.let { load(it.id, null, refresh = true) }
                },
                onNext = ::nextEpisode,
                nextTitle = null,
                onQuality = {},
                onInteraction = ::reveal,
                settingsItems = {
                    buildList {
                        add(
                            "Качество · ${if (selectedQuality == 0) "Авто" else "${selectedQuality}p"}" to
                                ::chooseQuality
                        )
                        add("Скорость · ${selectedSpeed}×" to ::chooseSpeed)
                        add(
                            "Поток · ${activeStream?.type?.uppercase().orEmpty()}" to ::chooseStream
                        )
                        add("Звук и субтитры" to ::tracks)
                        if (
                            Build.VERSION.SDK_INT >= 26 &&
                                packageManager.hasSystemFeature(
                                    android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE
                                )
                        )
                            add("Картинка в картинке" to ::pip)
                    }
                },
                onCaptions = ::tracks,
            )
        video.setOnClickListener {
            reveal()
            controls.show(true)
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (controls.visible) controls.hide() else finish()
                }
            },
        )
        setContentView(root)
        root.setOnKeyListener { _, key, event ->
            if (
                key in
                    listOf(
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                    ) && !controls.hasActionFocus
            ) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)
                        seekBy(if (key == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000)
                    else {
                        reveal()
                        controls.show(true)
                    }
                }
                true
            } else false
        }
        root.requestFocus()
        handler.post(controlTick)
        resumePosition = savedInstanceState?.getLong("position") ?: 0
        streamTypePreference = savedInstanceState?.getString("streamType")
        selectedQuality = savedInstanceState?.getInt("qualityHeight") ?: 0
        selectedSpeed = savedInstanceState?.getFloat("playbackSpeed", 1f) ?: 1f
        load(
            savedInstanceState?.getString("episodeId") ?: intent.getStringExtra("episodeId")!!,
            if (savedInstanceState == null) intent.getStringExtra("stream") else null,
        )
        handler.postDelayed(saveTick, 5000)
    }

    private fun load(
        id: String,
        forcedUrl: String? = intent.getStringExtra("stream"),
        refresh: Boolean = false,
    ) {
        loadJob?.cancel()
        loadJob =
            lifecycleScope.launch {
                try {
                    val playback = app.repository.playback(release, refresh)
                    release = playback.first
                    episodes = playback.second
                    val found = episodes.firstOrNull { it.id == id } ?: error("Серия не найдена")
                    episode = found
                    streams = PlayerJs.streams(found)
                    require(streams.isNotEmpty()) { "Потоки видео не найдены" }
                    val progress = app.storage.progress(release.id, found.id)
                    if (resumePosition == 0L && progress?.watched != true)
                        resumePosition = progress?.positionMs ?: 0
                    controls.title.text = release.title
                    controls.subtitle.text = found.title
                    controls.setNextAvailable(episodes.getOrNull(found.ordinal) != null)
                    val preference =
                        streamTypePreference
                            ?: app.storage.string("player.preferred_stream_technology")
                    play(
                        streams.firstOrNull { it.url == forcedUrl }
                            ?: streams.firstOrNull { it.type == preference }
                            ?: streams.first()
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    failure(error.message ?: "Ошибка загрузки")
                }
            }
    }

    private fun play(stream: VideoStream, playWhenReady: Boolean = true) {
        save()
        ready = false
        completed = false
        player?.release()
        activeStream = stream
        streamTypePreference = stream.type
        val http =
            DefaultHttpDataSource.Factory().setDefaultRequestProperties(app.repository.site.headers)
        val source = DefaultDataSource.Factory(this, http)
        val exo =
            ExoPlayer.Builder(this)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .setMediaSourceFactory(DefaultMediaSourceFactory(source))
                .build()
        player = exo
        video.player = exo
        exo.addListener(
            object : Player.Listener {
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    if (player !== exo || !BuildConfig.DEBUG) return
                    for (group in tracks.groups) {
                        if (group.type != androidx.media3.common.C.TRACK_TYPE_VIDEO) continue
                        for (index in 0 until group.length) if (group.isTrackSelected(index)) {
                            val format = group.getTrackFormat(index)
                            android.util.Log.i(
                                "DCTV_NATIVE_PLAYER",
                                "video=${format.sampleMimeType} size=${format.width}x${format.height}",
                            )
                        }
                    }
                }

                override fun onPlaybackParametersChanged(
                    parameters: androidx.media3.common.PlaybackParameters
                ) {
                    if (player === exo && BuildConfig.DEBUG)
                        android.util.Log.i("DCTV_NATIVE_PLAYER", "speed=${parameters.speed}")
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (player !== exo) return
                    controls.showLoading(if (state == Player.STATE_BUFFERING) "Загрузка…" else null)
                    if (state == Player.STATE_READY) {
                        ready = true
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    if (state == Player.STATE_ENDED) {
                        completed = true
                        save()
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        if (episode?.ordinal ?: Int.MAX_VALUE < episodes.size) {
                            endDialog =
                                showPlayerMenu(
                                    this@HardwarePlayerActivity,
                                    "Серия завершена",
                                    listOf("Следующая серия"),
                                    onCancel = { finish() },
                                ) {
                                    nextEpisode()
                                }
                        } else finish()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (player !== exo) return
                    if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else {
                        reveal()
                        save()
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (player !== exo) return
                    save()
                    failedStreams += stream.url
                    if (retryCount++ == 0) {
                        val position = exo.currentPosition
                        resumePosition = position.coerceAtLeast(resumePosition)
                        episode?.let { load(it.id, null, refresh = true) }
                    } else {
                        val alternative = streams.firstOrNull { it.url !in failedStreams }
                        if (alternative != null) play(alternative)
                        else
                            failure(
                                "Не удалось воспроизвести ${stream.type.uppercase()}: ${error.errorCodeName}"
                            )
                    }
                }
            }
        )
        applyQualityLimit(exo)
        exo.setPlaybackSpeed(selectedSpeed)
        if (BuildConfig.DEBUG)
            android.util.Log.i(
                "DCTV_NATIVE_PLAYER",
                "stream=${stream.type} qualityLimit=$selectedQuality speed=$selectedSpeed",
            )
        exo.setMediaItem(MediaItem.fromUri(stream.url))
        exo.seekTo(resumePosition)
        exo.prepare()
        exo.playWhenReady = playWhenReady
    }

    private fun save() {
        val ep = episode ?: return
        val exo = player ?: return
        if (!ready || exo.playerError != null) return
        val duration = exo.duration.coerceAtLeast(0)
        resumePosition = exo.currentPosition.coerceAtLeast(0)
        app.storage.saveProgress(release, ep, resumePosition, duration, completed)
    }

    private fun nextEpisode() {
        val next = episodes.getOrNull(episode?.ordinal ?: return) ?: return
        save()
        ready = false
        player?.stop()
        resumePosition = 0
        retryCount = 0
        episode = null
        load(next.id, null)
    }

    private fun chooseStream() {
        showPlayerMenu(
            this,
            "Поток видео",
            streams.map { it.type.uppercase() + " • ${it.quality}p" },
            streams.indexOf(activeStream),
            onDismiss = ::reveal,
        ) { index ->
            save()
            retryCount = 0
            play(streams[index], player?.playWhenReady ?: true)
        }
    }

    private fun chooseQuality() {
        val heights = listOf(0, 480, 720, 1080, 2160)
        showPlayerMenu(
            this,
            "Качество",
            heights.map { if (it == 0) "Авто" else "${it}p" },
            heights.indexOf(selectedQuality),
            onDismiss = ::reveal,
        ) { index ->
            selectedQuality = heights[index]
            player?.let(::applyQualityLimit)
        }
    }

    private fun chooseSpeed() {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        showPlayerMenu(
            this,
            "Скорость",
            speeds.map { "${it}×" },
            speeds.indexOf(selectedSpeed),
            onDismiss = ::reveal,
        ) { index ->
            selectedSpeed = speeds[index]
            player?.setPlaybackSpeed(selectedSpeed)
        }
    }

    private fun applyQualityLimit(exo: ExoPlayer) {
        exo.trackSelectionParameters =
            exo.trackSelectionParameters
                .buildUpon()
                .apply {
                    if (selectedQuality == 0) clearVideoSizeConstraints()
                    else setMaxVideoSize(Int.MAX_VALUE, selectedQuality)
                }
                .build()
    }

    private fun tracks() {
        if (player == null) return
        showPlayerMenu(this, "Звук и субтитры", listOf("Звук", "Субтитры"), onDismiss = ::reveal) {
            index ->
            chooseTrack(
                if (index == 0) androidx.media3.common.C.TRACK_TYPE_AUDIO
                else androidx.media3.common.C.TRACK_TYPE_TEXT
            )
        }
    }

    private fun chooseTrack(type: Int) {
        val exo = player ?: return
        val choices =
            exo.currentTracks.groups
                .filter { it.type == type }
                .flatMap { group ->
                    (0 until group.length)
                        .filter { group.isTrackSupported(it) }
                        .map { index -> group to index }
                }
        val labels =
            listOf("Авто", "Выключены") +
                choices.mapIndexed { number, (group, index) ->
                    val format = group.getTrackFormat(index)
                    val language =
                        format.language
                            ?.takeIf { it != "und" }
                            ?.let {
                                java.util.Locale.forLanguageTag(it)
                                    .getDisplayLanguage(java.util.Locale.forLanguageTag("ru"))
                            }
                            .orEmpty()
                    listOfNotNull(
                            format.label?.takeIf { it.isNotBlank() },
                            language.takeIf { it.isNotBlank() },
                        )
                        .distinct()
                        .joinToString(" · ")
                        .ifBlank { "Дорожка ${number + 1}" }
                }
        val parameters = exo.trackSelectionParameters
        val selected =
            if (type in parameters.disabledTrackTypes) 1
            else if (parameters.overrides.values.none { it.type == type }) 0
            else
                choices
                    .indexOfFirst { (group, index) -> group.isTrackSelected(index) }
                    .let { if (it < 0) 0 else it + 2 }
        showPlayerMenu(
            this,
            if (type == androidx.media3.common.C.TRACK_TYPE_AUDIO) "Звук" else "Субтитры",
            labels,
            selected,
            onDismiss = ::reveal,
        ) { index ->
            exo.trackSelectionParameters =
                exo.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(type)
                    .setTrackTypeDisabled(type, index == 1)
                    .apply {
                        if (index >= 2) {
                            val (group, track) = choices[index - 2]
                            addOverride(
                                androidx.media3.common.TrackSelectionOverride(
                                    group.mediaTrackGroup,
                                    listOf(track),
                                )
                            )
                        }
                    }
                    .build()
        }
    }

    private fun failure(message: String) {
        if (isFinishing) return
        showPlayerMenu(
            this,
            "Ошибка воспроизведения\n$message",
            listOf("Повторить", "Другой поток"),
            onCancel = { finish() },
        ) { index ->
            if (index == 0) {
                retryCount = 0
                episode?.let { load(it.id, null, refresh = true) }
            } else chooseStream()
        }
    }

    private fun pip() {
        if (Build.VERSION.SDK_INT >= 26 && player?.isPlaying == true)
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                )
            }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        if (inPip) controls.hide()
        else {
            controls.show()
            reveal()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        save()
        outState.putLong("position", resumePosition)
        outState.putString("episodeId", episode?.id ?: intent.getStringExtra("episodeId"))
        outState.putString("streamType", streamTypePreference)
        outState.putInt("qualityHeight", selectedQuality)
        outState.putFloat("playbackSpeed", selectedSpeed)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT < 24 || !isInPictureInPictureMode) player?.pause()
    }

    private val hideControls = Runnable {
        if (hasWindowFocus() && !controls.modalVisible && player?.isPlaying == true) controls.hide()
    }
    private val controlTick =
        object : Runnable {
            override fun run() {
                player?.let {
                    controls.update(
                        it.currentPosition,
                        it.duration,
                        it.isPlaying,
                        it.isCurrentMediaItemSeekable,
                    )
                }
                handler.postDelayed(this, 500)
            }
        }

    private fun reveal() {
        controls.show()
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 4500)
    }

    private fun seekBy(delta: Long) {
        player?.let { exo ->
            val end = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            exo.seekTo((exo.currentPosition + delta).coerceIn(0, end))
        }
        controls.showSeekFeedback(delta)
        reveal()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN)
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    player?.pause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    player?.play()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekBy(10000)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekBy(-10000)
                    return true
                }
            }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        save()
        handler.removeCallbacksAndMessages(null)
        controls.dispose()
        loadJob?.cancel()
        endDialog?.dismiss()
        video.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
