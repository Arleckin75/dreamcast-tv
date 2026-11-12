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
            if (key in DPAD_KEYS && !controls.hasActionFocus) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)
                        seekBy(if (key == KeyEvent.KEYCODE_DPAD_LEFT) -SEEK_STEP_MS else SEEK_STEP_MS)
                    else if (key == KeyEvent.KEYCODE_DPAD_UP || controls.visible) {
                        reveal()
                        controls.show(true)
                    }
                }
                true
            } else false
        }
        root.requestFocus()
        val saved = PlayerSavedState.from(savedInstanceState)
        resumePosition = saved?.positionMs ?: 0
        streamTypePreference = saved?.streamType
        selectedQuality = saved?.qualityHeight ?: 0
        selectedSpeed = saved?.playbackSpeed ?: 1f
        decoderMode = DecoderMode.from(saved?.decoderMode
            ?: app.storage.string("player.decoder_mode", "AUTO"))
        load(
            saved?.episodeId ?: intent.getStringExtra("episodeId")!!,
            if (saved == null) intent.getStringExtra("stream") else null,
        )
    }

    private fun load(
        id: String,
        forcedUrl: String? = intent.getStringExtra("stream"),
        refresh: Boolean = false,
        fromStart: Boolean = false,
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
                    streams = app.repository.streams(found)
                    require(streams.isNotEmpty()) { "Потоки видео не найдены" }
                    app.progressWriter.awaitPending()
                    val progress =
                        withContext(Dispatchers.IO) { app.storage.progress(release.id, found.id) }
                    if (!fromStart && resumePosition == 0L && progress?.watched != true)
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
                val source = mediaSourceFactory(this@PlayerActivity, app.repository.streamHeaders)
                formats = videoFormats(this@PlayerActivity, source, MediaItem.fromUri(stream.url))
                if (serial != playbackSerial) return@launch
                val choice = chooseVideo(formats, selectedQuality, decoderMode,
                    hardware = { !hardwareFailed && capabilities.hardware(it) },
                    software = capabilities::software)
                decoderMode = choice.mode
                currentVideoFormat = choice.format
                output = choice.output
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
        val exo = createPlayer(this, output, source)
        player = exo
        video.player = exo
        exo.addAnalyticsListener(
            DecoderDiagnostics(
                exo,
                isCurrent = { player === exo },
                requested = { "requested=$decoderMode output=$output" },
                onFormat = { currentVideoFormat = it },
                onDecoder = { videoDecoderName = it },
            )
        )
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
                        } else showSeasonEnd()
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
                    val dav1d = canFallbackToDav1d(decoderMode, output, hardwareFailed,
                        isVideoRendererError(error), format != null && capabilities.software(format))
                    if (!dav1d) failedStreams += stream.url
                    val recovery = planRecovery(dav1d, firstFailure = !dav1d && retryCount++ == 0,
                        stream, streams, failedStreams, error.errorCodeName)
                    when (recovery) {
                        PlaybackRecovery.Dav1dFallback -> {
                            resumePosition = exo.currentPosition.coerceAtLeast(0)
                            hardwareFailed = true
                            android.widget.Toast.makeText(this@PlayerActivity,
                                "Ошибка аппаратного декодера. Переключение на dav1d",
                                android.widget.Toast.LENGTH_LONG).show()
                            play(stream, exo.playWhenReady)
                        }
                        PlaybackRecovery.ReloadPage -> {
                            resumePosition = exo.currentPosition.coerceAtLeast(0)
                            episode?.let { load(it.id, null, refresh = true) }
                        }
                        is PlaybackRecovery.OtherStream -> play(recovery.stream)
                        is PlaybackRecovery.Fail -> failure(recovery.message)
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
        startEpisode(next)
    }

    private fun startEpisode(target: Episode, fromStart: Boolean = false) {
        save()
        ready = false
        player?.stop()
        resumePosition = 0
        retryCount = 0
        episode = null
        load(target.id, null, fromStart = fromStart)
    }

    /** After the last available episode: rewatch, mark the release watched, or close. */
    private fun showSeasonEnd() {
        if (inPip() || isFinishing) {
            finish()
            return
        }
        val shown = release
        lifecycleScope.launch {
            val status =
                withContext(Dispatchers.IO) {
                    app.storage.bookmarks().firstOrNull { it.release.id == shown.id }?.status
                }
            if (isFinishing || release.id != shown.id) return@launch
            val menu = seasonEndMenu(shown, episodes.size, status)
            showPlayerMenu(
                this@PlayerActivity,
                menu.title,
                menu.actions.map { it.label },
                onCancel = { finish() },
            ) { index ->
                when (menu.actions[index]) {
                    SeasonEndAction.REWATCH -> {
                        playbackJob?.cancel()
                        playbackSerial++
                        episodes.firstOrNull()?.let { startEpisode(it, fromStart = true) }
                    }
                    SeasonEndAction.MARK_WATCHED -> {
                        app.storage.bookmark(shown, LibraryStatus.completed)
                        finish()
                    }
                    SeasonEndAction.CLOSE -> finish()
                }
            }
        }
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
        val heights = QUALITY_HEIGHTS
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
        val speeds = PLAYBACK_SPEEDS
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
                    val maxHeight = videoHeightLimit(output, selectedQuality, softwareAutoMaxHeight)
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
        val lower = lowerSoftwareHeight(formats, height, softwareAutoMaxHeight,
            capabilities::software) ?: return
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
            val exo = player ?: return@showPlayerMenu
            showTrackMenu(this, exo, if (index == 0) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT, ::reveal)
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
        PlayerSavedState(
                episodeId = episode?.id ?: intent.getStringExtra("episodeId"),
                positionMs = resumePosition,
                streamType = streamTypePreference,
                qualityHeight = selectedQuality,
                playbackSpeed = selectedSpeed,
                decoderMode = decoderMode.name,
            )
            .writeTo(outState)
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
        val action =
            if (event.action == KeyEvent.ACTION_DOWN) remoteAction(event.keyCode) else null
        when (action) {
            RemoteAction.TOGGLE -> player?.let { if (it.isPlaying) it.pause() else it.play() }
            RemoteAction.PAUSE -> player?.pause()
            RemoteAction.PLAY -> player?.play()
            RemoteAction.FORWARD -> seekBy(SEEK_STEP_MS)
            RemoteAction.REWIND -> seekBy(-SEEK_STEP_MS)
            null -> return super.onKeyDown(keyCode, event)
        }
        return true
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
