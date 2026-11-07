package com.dreamcast.nativeapp

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
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.dreamcast.nativeapp.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ComponentActivity() {
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
    private lateinit var root: FrameLayout
    private val capabilities by lazy { VideoDecoderCapabilities(applicationContext) }
    private var decoderMode = DecoderMode.AUTO
    private var output = VideoOutput.SYSTEM
    private var formats = emptyList<Format>()
    private var currentVideoFormat: Format? = null
    private var videoDecoderName = ""
    private var playbackJob: Job? = null
    private var playbackSerial = 0
    private var hardwareFailed = false
    private var resumeOnStart = false
    private lateinit var controls: PlayerControls
    private var loadJob: Job? = null
    private var resumePosition = 0L
    private var ready = false
    private val softwareFrameMonitor = SoftwareFrameMonitor()
    private var softwareAutoMaxHeight = 720
    private var completed = false
    private var activityStarted = false
    private var lastSaved: ProgressSnapshot? = null
    private var retryCount = 0
    private val failedStreams = mutableSetOf<String>()
    private var completionCountdown: EpisodeCompletionCountdown? = null
    private val handler = Handler(Looper.getMainLooper())
    private val saveTick =
        object : Runnable {
            override fun run() {
                if (!activityStarted && !inPip()) return
                save(force = false)
                checkSoftwareFrames()
                if (player?.isPlaying == true) handler.postDelayed(this, 5000)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        release = Release.fromJson(JSONObject(intent.getStringExtra("release")!!))
        root =
            object : FrameLayout(this) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (
                        event.action == KeyEvent.ACTION_DOWN &&
                            event.keyCode != KeyEvent.KEYCODE_BACK &&
                            (controls.visible || event.keyCode == KeyEvent.KEYCODE_DPAD_UP)
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
            PlayerControls(
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
                        add("Декодирование · ${decoderMode.label}" to ::chooseDecoder)
                        add(
                            "Поток · ${activeStream?.type?.uppercase().orEmpty()}" to ::chooseStream
                        )
                    }
                },
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
                    else if (key == KeyEvent.KEYCODE_DPAD_UP || controls.visible) {
                        reveal()
                        controls.show(true)
                    }
                }
                true
            } else false
        }
        root.requestFocus()
        resumePosition = savedInstanceState?.getLong("position") ?: 0
        streamTypePreference = savedInstanceState?.getString("streamType")
        selectedQuality = savedInstanceState?.getInt("qualityHeight") ?: 0
        selectedSpeed = savedInstanceState?.getFloat("playbackSpeed", 1f) ?: 1f
        decoderMode = DecoderMode.from(savedInstanceState?.getString("decoderMode")
            ?: app.storage.string("player.decoder_mode", "AUTO"))
        load(
            savedInstanceState?.getString("episodeId") ?: intent.getStringExtra("episodeId")!!,
            if (savedInstanceState == null) intent.getStringExtra("stream") else null,
        )
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
                    // Save the previous episode before replacing its identity during a reload.
                    save()
                    ready = false
                    episode = found
                    streams = PlayerJs.streams(found)
                    require(streams.isNotEmpty()) { "Потоки видео не найдены" }
                    app.progressWriter.awaitPending()
                    val progress =
                        withContext(Dispatchers.IO) { app.storage.progress(release.id, found.id) }
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
        playbackJob?.cancel()
        val serial = ++playbackSerial
        save()
        player?.let { if (ready) resumePosition = it.currentPosition.coerceAtLeast(0) }
        ready = false
        currentVideoFormat = null
        formats = emptyList()
        videoDecoderName = ""
        if (activeStream?.url != stream.url) hardwareFailed = false
        activeStream = stream
        streamTypePreference = stream.type
        video.player = null
        player?.release()
        player = null
        softwareFrameMonitor.reset()
        softwareAutoMaxHeight = 720
        controls.showLoading("Проверка видеопотока…")
        playbackJob = lifecycleScope.launch {
            try {
                val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(app.repository.site.headers)
                val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(this@PlayerActivity, http))
                formats = videoFormats(this@PlayerActivity, source, MediaItem.fromUri(stream.url))
                if (serial != playbackSerial) return@launch
                val limited = formats.filter { selectedQuality == 0 || it.height <= selectedQuality }
                    .ifEmpty { formats }
                val ordered = limited.sortedByDescending { it.height }
                val hardware = ordered.firstOrNull { !hardwareFailed && capabilities.hardware(it) }
                val software = ordered.filter { capabilities.software(it) }
                    .let { candidates ->
                        candidates.firstOrNull { it.height <= (selectedQuality.takeIf { h -> h > 0 } ?: 720) }
                            ?: candidates.lastOrNull()
                    }
                if ((decoderMode == DecoderMode.HARDWARE && hardware == null) ||
                    (decoderMode == DecoderMode.SOFTWARE && software == null)) decoderMode = DecoderMode.AUTO
                val chosen = if (decoderMode == DecoderMode.SOFTWARE) software
                    else hardware ?: software ?: ordered.firstOrNull()
                currentVideoFormat = chosen
                output = decoderOutput(decoderMode, chosen != null && !hardwareFailed && capabilities.hardware(chosen),
                    chosen != null && capabilities.software(chosen))
                playPrepared(stream, playWhenReady, source)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (serial == playbackSerial) {
                    controls.showLoading(null)
                    failure(error.message ?: "Не удалось проверить видеопоток")
                }
            }
        }
    }

    private fun configureVideoView() {
        video.player = null
        video.onPause()
        root.removeView(video)
        video = if (output == VideoOutput.DAV1D)
            layoutInflater.inflate(R.layout.dav1d_player, root, false) as PlayerView
        else PlayerView(this).apply { useController = false }
        root.addView(video, 0, FrameLayout.LayoutParams(-1, -1))
        video.setOnClickListener { reveal(); controls.show(true) }
        if (activityStarted) video.onResume()
    }

    private fun playPrepared(stream: VideoStream, playWhenReady: Boolean, source: DefaultMediaSourceFactory) {
        val playbackStarted = NativePerformance.now()
        completed = false
        videoDecoderName = ""
        configureVideoView()
        val exo =
            ExoPlayer.Builder(this, UnifiedRenderersFactory(this, output))
                .setTrackSelector(androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
                    setParameters(buildUponParameters().setExceedRendererCapabilitiesIfNecessary(false))
                })
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .setMediaSourceFactory(source)
                .build()
        player = exo
        video.player = exo
        exo.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                if (player !== exo) return
                currentVideoFormat = format
                android.util.Log.i("DCTV_DECODER", "format=${format.width}x${format.height} fps=${format.frameRate} bitrate=${format.bitrate}")
            }

            override fun onDroppedVideoFrames(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                droppedFrames: Int, elapsedMs: Long,
            ) {
                if (player === exo)
                    android.util.Log.i("DCTV_DECODER", "dropped=$droppedFrames elapsedMs=$elapsedMs bufferedMs=${exo.totalBufferedDuration}")
            }

            override fun onVideoDisabled(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                counters: androidx.media3.exoplayer.DecoderCounters,
            ) {
                counters.ensureUpdated()
                android.util.Log.i("DCTV_DECODER", "rendered=${counters.renderedOutputBufferCount} dropped=${counters.droppedBufferCount} skipped=${counters.skippedOutputBufferCount} maxConsecutiveDropped=${counters.maxConsecutiveDroppedBufferCount}")
            }

            override fun onVideoDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
            ) {
                if (player === exo) {
                    videoDecoderName = decoderName
                    android.util.Log.i("DCTV_DECODER", "requested=$decoderMode output=$output decoder=$decoderName")
                }
            }
        })
        exo.addListener(
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (player === exo)
                        NativePerformance.event(
                            "player.first_rendered_frame",
                            playbackStarted,
                        )
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (player === exo && reason == Player.DISCONTINUITY_REASON_SEEK) {
                        softwareFrameMonitor.reset()
                        save()
                    }
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    if (player !== exo) return
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
                        if (exo.playWhenReady) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    if (state == Player.STATE_ENDED) {
                        completed = true
                        save()
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        val next = episodes.getOrNull(episode?.ordinal ?: Int.MAX_VALUE)
                        if (next != null) {
                            completionCountdown?.dismiss()
                            completionCountdown =
                                EpisodeCompletionCountdown(
                                        this@PlayerActivity,
                                        next.title,
                                        ::nextEpisode,
                                    )
                                    .also { it.show() }
                        } else finish()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (player !== exo) return
                    if (!isPlaying) softwareFrameMonitor.reset()
                    if (isPlaying) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        reveal()
                    } else {
                        reveal()
                        save()
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    scheduleSave()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (player !== exo) return
                    save()
                    val format = currentVideoFormat
                    val videoError = (error is androidx.media3.exoplayer.ExoPlaybackException &&
                        error.type == androidx.media3.exoplayer.ExoPlaybackException.TYPE_RENDERER &&
                        (error.rendererFormat?.sampleMimeType?.startsWith("video/") == true ||
                            error.rendererName?.contains("Video") == true))
                    if (canFallbackToDav1d(decoderMode, output, hardwareFailed, videoError,
                            format != null && capabilities.software(format))) {
                        resumePosition = exo.currentPosition.coerceAtLeast(0)
                        hardwareFailed = true
                        android.widget.Toast.makeText(this@PlayerActivity,
                            "Ошибка аппаратного декодера. Переключение на dav1d", android.widget.Toast.LENGTH_LONG).show()
                        play(stream, exo.playWhenReady)
                        return
                    }
                    failedStreams += stream.url
                    if (retryCount++ == 0) {
                        val position = exo.currentPosition
                        resumePosition = position.coerceAtLeast(0)
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
        resumeOnStart = playWhenReady
        exo.playWhenReady = playWhenReady && (activityStarted || inPip())
    }

    private fun chooseDecoder() {
        val format = player?.videoFormat ?: currentVideoFormat
        val hw = format != null && !hardwareFailed && capabilities.hardware(format)
        val sw = format != null && capabilities.software(format)
        showPlayerMenu(this, "Декодирование",
            listOf("Авто", if (hw) "Аппаратное" else "Аппаратное — недоступно для этого видео",
                if (sw) "Программное AV1 — dav1d" else "Программное AV1 — недоступно для этого видео"),
            decoderMode.ordinal, onDismiss = ::reveal,
            enabledItem = { it == 0 || (it == 1 && hw) || (it == 2 && sw) },
        ) { index ->
            if ((index == 1 && !hw) || (index == 2 && !sw)) return@showPlayerMenu
            decoderMode = DecoderMode.entries[index]
            app.storage.put("player.decoder_mode", decoderMode.name)
            activeStream?.let { play(it, player?.playWhenReady ?: resumeOnStart) }
        }
    }

    private fun save(force: Boolean = true) {
        val ep = episode ?: return
        val exo = player ?: return
        if (!ready || exo.playerError != null) return
        val duration = exo.duration.coerceAtLeast(0)
        resumePosition = exo.currentPosition.coerceAtLeast(0)
        val snapshot = ProgressSnapshot(release, ep, resumePosition, duration, completed)
        if (force || snapshot != lastSaved) {
            app.progressWriter.save(snapshot)
            lastSaved = snapshot
        }
    }

    private fun nextEpisode() {
        playbackJob?.cancel()
        playbackSerial++
        completionCountdown?.dismiss()
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
            softwareFrameMonitor.reset()
            softwareAutoMaxHeight = 720
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
                    val maxHeight = if (output == VideoOutput.DAV1D && selectedQuality == 0) softwareAutoMaxHeight else selectedQuality
                    if (maxHeight == 0) clearVideoSizeConstraints()
                    else setMaxVideoSize(Int.MAX_VALUE, maxHeight)
                    currentVideoFormat?.sampleMimeType?.let { setPreferredVideoMimeTypes(it) }
                }
                .build()
    }

    private fun checkSoftwareFrames() {
        val exo = player ?: return
        if (output != VideoOutput.DAV1D || selectedQuality != 0 || !exo.isPlaying) {
            softwareFrameMonitor.reset()
            return
        }
        val counters = exo.videoDecoderCounters ?: return
        counters.ensureUpdated()
        if (!softwareFrameMonitor.sample(android.os.SystemClock.elapsedRealtime(),
                counters.renderedOutputBufferCount, counters.droppedBufferCount)) return
        val height = exo.videoFormat?.height ?: return
        val lower = formats.filter { capabilities.software(it) && it.height in 1 until height &&
            it.height < softwareAutoMaxHeight }.maxOfOrNull { it.height } ?: return
        softwareAutoMaxHeight = lower
        applyQualityLimit(exo)
        android.util.Log.i("DCTV_DECODER", "softwareAutoMaxHeight=$lower reason=sustained_frame_drops")
        android.widget.Toast.makeText(this, "Авто: ${lower}p для плавного воспроизведения",
            android.widget.Toast.LENGTH_SHORT).show()
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
        if (inPip) controls.hide(animate = false)
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
        outState.putString("decoderMode", decoderMode.name)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        activityStarted = false
        handler.removeCallbacks(controlTick)
        handler.removeCallbacks(hideControls)
        if (!inPip()) {
            resumeOnStart = player?.playWhenReady ?: resumeOnStart
            player?.pause()
            video.onPause()
            handler.removeCallbacks(saveTick)
        }
    }

    private fun inPip() = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    override fun onStart() {
        super.onStart()
        activityStarted = true
        video.onResume()
        if (resumeOnStart) player?.play()
        scheduleSave()
        if (controls.visible) reveal()
    }

    private fun scheduleSave() {
        handler.removeCallbacks(saveTick)
        if ((activityStarted || inPip()) && player?.isPlaying == true)
            handler.postDelayed(saveTick, 5000)
    }

    private val hideControls = Runnable {
        if (hasWindowFocus() && !controls.modalVisible && player?.isPlaying == true) controls.hide()
    }
    private val controlTick =
        object : Runnable {
            override fun run() {
                if (!activityStarted || !controls.visible || inPip()) return
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
        if (!activityStarted || inPip()) return
        controls.show()
        handler.removeCallbacks(controlTick)
        handler.post(controlTick)
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 5000)
    }

    private fun seekBy(delta: Long) {
        player?.let { exo ->
            val end = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            exo.seekTo((exo.currentPosition + delta).coerceIn(0, end))
        }
        controls.showSeekFeedback(delta)
        if (controls.visible) reveal()
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
        playbackJob?.cancel()
        playbackSerial++
        completionCountdown?.dismiss()
        video.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
