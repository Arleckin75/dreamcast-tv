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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.dreamcast.nativeapp.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val softwareQuality by lazy { loadSoftwareQuality() }
    private var completed = false
    private var activityStarted = false
    private var lastSaved: ProgressSnapshot? = null
    private var retryCount = 0
    private val failedStreams = mutableSetOf<String>()
    private var completionCountdown: EpisodeCompletionCountdown? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var seekPreview: SeekPreview
    private lateinit var controlsTimer: ControlsTimer
    private val menus by lazy { PlayerMenus(this, ::reveal) }
    private val saveTimer =
        PlaybackTicker(
            handler,
            PROGRESS_SAVE_INTERVAL_MS,
            active = { activityStarted || inPip() },
            playing = { player?.isPlaying == true },
        ) {
            save(force = false)
            checkSoftwareFrames()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        release = Release.fromJson(JSONObject(intent.getStringExtra("release")!!))
        root =
            object : FrameLayout(this) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (seekPreview.handleKey(event)) return true
                    if (
                        event.action == KeyEvent.ACTION_DOWN &&
                            event.keyCode != KeyEvent.KEYCODE_BACK &&
                            (controls.visible || event.keyCode == KeyEvent.KEYCODE_DPAD_UP)
                    )
                        reveal()
                    return super.dispatchKeyEvent(event)
                }
            }
        // A placeholder until playback starts: configureVideoView() replaces it with the view
        // for the chosen decoder, and the window's keep-screen-on flag follows playback.
        video = PlayerView(this).apply { useController = false }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
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
                onScrub = ::scrub,
                onRetry = {
                    retryCount = 0
                    episode?.let { load(it.id, null, refresh = true) }
                },
                onNext = ::nextEpisode,
                nextTitle = null,
                onInteraction = ::reveal,
                settingsItems = {
                    buildList {
                        add(
                            "Качество · ${if (selectedQuality == 0) "Авто" else "${selectedQuality}p"}" to
                                ::chooseQuality
                        )
                        add("Скорость · ${selectedSpeed}×" to ::chooseSpeed)
                        add(
                            "Декодирование · ${decoderMenuLabel(decoderMode, output.takeIf { player != null })}" to
                                ::chooseDecoder
                        )
                        add(
                            "Поток · ${activeStream?.type?.uppercase().orEmpty()}" to ::chooseStream
                        )
                    }
                },
            )
        seekPreview =
            SeekPreview(
                lifecycleScope,
                handler,
                controls,
                streamHeaders = { app.repository.streamHeaders },
                player = { player },
                ready = { ready },
                seekTo = ::seekToward,
                seekBy = ::seekBy,
                reveal = ::reveal,
            )
        controlsTimer =
            ControlsTimer(
                handler,
                controls,
                player = { player },
                onScreen = { activityStarted && !inPip() },
                hasWindowFocus = ::hasWindowFocus,
                onReveal = { seekPreview.prefetch() },
            )
        video.setOnClickListener {
            reveal()
            controls.show(true)
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Back first leaves scrubbing without seeking.
                    if (seekPreview.cancel()) return
                    if (controls.visible) controls.hide() else finish()
                }
            },
        )
        setContentView(root)
        root.setOnKeyListener { _, key, event ->
            if (key in DPAD_KEYS && !controls.hasActionFocus) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)
                        scrub(if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1, event.repeatCount)
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
                    seekPreview.load(found)
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

    /**
     * Starts [stream]. [decoderChanged]: a different video decoder replaces the one just
     * released, so wait [DECODER_SWITCH_PAUSE_MS] before creating it.
     */
    private fun play(stream: VideoStream, playWhenReady: Boolean = true, decoderChanged: Boolean = false) {
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
        softwareQuality.newEpisode("${release.id}/${episode?.id}")
        controls.showLoading("Проверка видеопотока…")
        playbackJob = lifecycleScope.launch {
            try {
                val source = mediaSourceFactory(this@PlayerActivity, app.repository.streamHeaders)
                formats = videoFormats(this@PlayerActivity, source, MediaItem.fromUri(stream.url))
                if (serial != playbackSerial) return@launch
                if (decoderChanged) {
                    delay(DECODER_SWITCH_PAUSE_MS)
                    if (serial != playbackSerial) return@launch
                }
                val choice = chooseVideo(formats, selectedQuality, decoderMode,
                    hardware = { !hardwareFailed && capabilities.hardware(it) },
                    software = capabilities::software, softwareAutoHeight = softwareQuality.limit)
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
        video = layoutInflater.inflate(
            if (output == VideoOutput.DAV1D) R.layout.dav1d_player else R.layout.hardware_player,
            root, false,
        ) as PlayerView
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
                onFormat = {
                    // A new resolution starts a new measurement: 30 s at 480p say nothing about 720p.
                    if (it.height != currentVideoFormat?.height) softwareFrameMonitor.reset()
                    currentVideoFormat = it
                },
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
                    saveTimer.schedule()
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
                            play(stream, exo.playWhenReady, decoderChanged = true)
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
        menus.decoder(decoderMode, hw, sw) { mode ->
            app.storage.put("player.decoder_mode", mode.name)
            // The decoder this choice would run; when it is the one already running (Auto already
            // on the hardware decoder, say), keep playing: restarting a hardware decoder for
            // nothing hangs some TVs.
            val next = chooseVideo(formats, selectedQuality, mode,
                hardware = { !hardwareFailed && capabilities.hardware(it) },
                software = capabilities::software, softwareAutoHeight = softwareQuality.limit)
            if (player != null && formats.isNotEmpty() &&
                unchangedChoice(next.output, output, failed = player?.playerError != null)) {
                decoderMode = mode
                android.util.Log.i("DCTV_DECODER", "requested=$mode output=$output restart=false")
                return@decoder
            }
            decoderMode = mode
            // A different decoder: reopen the player screen at the same position, the way an
            // episode is opened from the list. Restarting the decoder inside the running screen
            // hung a Candy Google TV (dav1d -> hardware) even after a pause; opening an episode
            // with the hardware decoder works there. onSaveInstanceState keeps the episode,
            // position, stream, quality, speed and this decoder mode.
            android.util.Log.i("DCTV_DECODER", "requested=$mode output=$output restart=screen")
            recreate()
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
            menus.seasonEnd(menu, onClose = { finish() }) { action ->
                when (action) {
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
        menus.stream(streams, activeStream) { stream ->
            // The stream already playing: nothing to do. After an error it is a retry.
            if (player != null &&
                unchangedChoice(stream, activeStream, failed = player?.playerError != null))
                return@stream
            save()
            retryCount = 0
            play(stream, player?.playWhenReady ?: true)
        }
    }

    private fun chooseQuality() {
        // Only heights the stream really has and the current decoder path can play.
        val playable = formats.filter {
            when (output) {
                VideoOutput.DAV1D -> capabilities.software(it)
                VideoOutput.HARDWARE -> capabilities.hardware(it)
                VideoOutput.SYSTEM -> true
            }
        }
        val heights = qualityChoices((playable.ifEmpty { formats }).map { it.height })
        menus.quality(heights, selectedQuality) { height ->
            // Re-picking the current quality would reset the dav1d quality logic for nothing.
            if (unchangedChoice(height, selectedQuality, failed = false)) return@quality
            selectedQuality = height
            softwareFrameMonitor.reset()
            softwareQuality.restoreLearned()
            player?.let(::applyQualityLimit)
        }
    }

    private fun chooseSpeed() {
        menus.speed(selectedSpeed) { speed ->
            if (unchangedChoice(speed, selectedSpeed, failed = false)) return@speed
            selectedSpeed = speed
            player?.setPlaybackSpeed(selectedSpeed)
        }
    }

    private fun applyQualityLimit(exo: ExoPlayer) {
        exo.trackSelectionParameters =
            exo.trackSelectionParameters
                .buildUpon()
                .apply {
                    val maxHeight = videoHeightLimit(output, selectedQuality, softwareQuality.limit)
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
        val now = android.os.SystemClock.elapsedRealtime()
        val signal = softwareFrameMonitor.sample(now, counters.renderedOutputBufferCount,
            counters.droppedBufferCount, trial = softwareQuality.trying)
        if (signal == SoftwareFrameMonitor.Signal.NONE) return
        val height = exo.videoFormat?.height ?: return
        if (signal == SoftwareFrameMonitor.Signal.OVERLOADED) {
            val lower = softwareQuality.overloaded(
                lowerSoftwareHeight(formats, height, softwareQuality.limit, capabilities::software))
                ?: return
            applyQualityLimit(exo)
            android.util.Log.i("DCTV_DECODER", "softwareAutoMaxHeight=$lower reason=sustained_frame_drops")
            android.widget.Toast.makeText(this, "Авто: ${lower}p для плавного воспроизведения",
                android.widget.Toast.LENGTH_SHORT).show()
        } else {
            val higher = softwareQuality.smooth(height,
                higherSoftwareHeight(formats, softwareQuality.limit, capabilities::software))
            if (higher == null) {
                android.util.Log.i("DCTV_DECODER", "smooth height=$height limit=${softwareQuality.limit}")
                return
            }
            softwareFrameMonitor.reset()
            applyQualityLimit(exo)
            android.util.Log.i("DCTV_DECODER", "softwareAutoMaxHeight=$higher reason=probe_up")
        }
    }

    /** The learned dav1d limit; values restored from another device (backup) are ignored. */
    private fun loadSoftwareQuality(): SoftwareAutoQuality {
        val storage = app.storage
        val device = "${android.os.Build.MANUFACTURER}/${android.os.Build.DEVICE}"
        val known = storage.string(DAV1D_DEVICE_KEY) == device
        return SoftwareAutoQuality(
            if (known) SoftwareAutoQuality.State(
                height = storage.number(DAV1D_HEIGHT_KEY, SOFTWARE_AUTO_START_HEIGHT.toLong()).toInt(),
                failures = storage.number(DAV1D_FAILURES_KEY).toInt(),
                waitEpisodes = storage.number(DAV1D_WAIT_KEY).toInt(),
            ) else SoftwareAutoQuality.State(SOFTWARE_AUTO_START_HEIGHT),
        ) { state ->
            storage.put(DAV1D_DEVICE_KEY, device)
            storage.put(DAV1D_HEIGHT_KEY, state.height.toLong())
            storage.put(DAV1D_FAILURES_KEY, state.failures.toLong())
            storage.put(DAV1D_WAIT_KEY, state.waitEpisodes.toLong())
        }
    }

    private fun tracks() {
        if (player != null) menus.tracks { player }
    }

    private fun failure(message: String) {
        if (isFinishing) return
        menus.failure(
            message,
            onRetry = {
                retryCount = 0
                episode?.let { load(it.id, null, refresh = true) }
            },
            onOtherStream = ::chooseStream,
            onClose = { finish() },
        )
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
        else if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            // The PiP window was closed, not expanded. onStop ran while still "in PiP" and kept
            // playing, so the sound went on with no picture: stop and close the player.
            closedFromPip()
        } else {
            controls.show()
            reveal()
        }
    }

    /** Leaves playback after its PiP window was closed: save the position, pause, finish. */
    private fun closedFromPip() {
        save()
        player?.pause()
        resumeOnStart = false
        saveTimer.stop()
        android.util.Log.i("DCTV_NATIVE_PLAYER", "pip closed")
        finish()
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
        seekPreview.cancel()
        controlsTimer.stop()
        if (!inPip()) {
            resumeOnStart = player?.playWhenReady ?: resumeOnStart
            player?.pause()
            video.onPause()
            saveTimer.stop()
        }
    }

    private fun inPip() = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    override fun onStart() {
        super.onStart()
        activityStarted = true
        video.onResume()
        if (resumeOnStart) player?.play()
        saveTimer.schedule()
        if (controls.visible) reveal()
    }

    private fun reveal() = controlsTimer.reveal()

    private fun scrub(direction: Int, repeatCount: Int) = seekPreview.step(direction, repeatCount)

    /** dav1d seeks land on a keyframe: decoding up to an exact position is slow on weak TVs. */
    private fun seekToward(exo: ExoPlayer, target: Long) {
        val position =
            if (output == VideoOutput.DAV1D)
                keyframeSeekTarget(target, exo.currentPosition, hlsKeyframesMs(exo))
            else target
        exo.seekTo(position)
        seekPreview.prefetch(position)
    }

    private fun seekBy(delta: Long) {
        player?.let { exo ->
            val end = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            seekToward(exo, (exo.currentPosition + delta).coerceIn(0, end))
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
            RemoteAction.FORWARD -> scrub(1, event.repeatCount)
            RemoteAction.REWIND -> scrub(-1, event.repeatCount)
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
        seekPreview.dispose()
        video.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}

/** How often a playing episode saves its position and checks dav1d frame drops. */
private const val PROGRESS_SAVE_INTERVAL_MS = 5000L
private const val DAV1D_DEVICE_KEY = "player.dav1d_auto_device"
private const val DAV1D_HEIGHT_KEY = "player.dav1d_auto_height"
private const val DAV1D_FAILURES_KEY = "player.dav1d_failures"
private const val DAV1D_WAIT_KEY = "player.dav1d_wait_episodes"
