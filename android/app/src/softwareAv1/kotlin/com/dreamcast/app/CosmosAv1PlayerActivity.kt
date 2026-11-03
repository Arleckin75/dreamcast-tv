package com.dreamcast.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/** AV1 software fallback, isolated from ExoPlayer on compatible devices. */
object CosmosAv1Launcher {
    @JvmStatic fun launch(activity: Activity, url: String, headers: Map<String, String>, positionMs: Long, episodeKey: String, quality: Int, positionUpdatedAtMs: Long, title: String = "", subtitle: String = "", accent: Int = 0xFFD0BCFF.toInt(), onAccent: Int = 0xFF381E72.toInt()) {
        val intent = Intent(activity, CosmosAv1PlayerActivity::class.java)
        intent.putExtra("url", url)
        intent.putExtra("positionMs", positionMs)
        intent.putExtra("positionUpdatedAtMs", positionUpdatedAtMs)
        intent.putExtra("episodeKey", episodeKey)
        intent.putExtra("title", title)
        intent.putExtra("subtitle", subtitle)
        intent.putExtra("accent", accent)
        intent.putExtra("onAccent", onAccent)
        intent.putExtra("quality", if (quality in listOf(480, 720, 1080)) quality else 720)
        intent.putExtra("userAgent", headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value)
        intent.putExtra("referer", headers.entries.firstOrNull { it.key.equals("referer", true) }?.value)
        activity.startActivityForResult(intent, 7192)
    }
}

class CosmosAv1PlayerActivity : Activity() {
    private var activityResumed = false
    private var playbackScreenOnRequested = false

    private fun setPlaybackScreenOn(enabled: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            uiHandler.post { setPlaybackScreenOn(enabled) }
            return
        }
        playbackScreenOnRequested = enabled
        applyPlaybackScreenOn()
    }

    private fun applyPlaybackScreenOn() {
        if (activityResumed && playbackScreenOnRequested && !shutdownStarted && !isFinishing && !isDestroyed) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    private var backExitRequested = false
    private var shutdownStarted = false
    // Capture playback progress before VLC.stop()/release() invalidate player.time.
    private var exitPositionMs: Long? = null
    private var exitDurationMs: Long? = null
    private var engine: LibVLC? = null
    private var player: MediaPlayer? = null
    private var controls: SoftwarePlayerControls? = null
    private var loading = false
    private var retrying = false
    private var qualityPreferenceChanged = false
    private var pauseAfterRestart = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var voutCount = -1
    private var surfaceReady = false
    private val activityStartedAt = android.os.SystemClock.elapsedRealtimeNanos()
    private var outputTimingReported = false
    private var videoSurface: SurfaceView? = null
    private var episodeKey: String = ""
    private var episodeCompleted = false
    private var playbackError = false
    private var resumeMs = 0L
    private var hasRestoredPosition = false
    private var lastPersistedMs = -1L
    private val progressPrefs by lazy { getSharedPreferences("dream_cast_vlc_progress", Context.MODE_PRIVATE) }
    private fun persistPosition(force: Boolean = false) {
        if (retrying) return
        val mp = player ?: return
        if (episodeKey.isBlank()) return
        if (episodeCompleted || playbackError || (!hasRestoredPosition && resumeMs > 5000L)) return
        val ms = runCatching { mp.time }.getOrDefault(-1L)
        if (ms < 0L) return
        if (!force && (ms < 1000L || (lastPersistedMs >= 0 && kotlin.math.abs(ms - lastPersistedMs) < 4000L))) return
        val length = runCatching { mp.length }.getOrDefault(0L)
        if (length > 0 && ms >= length - 5000L) return // Don't overwrite progress at the end.
        progressPrefs.edit().putLong(episodeKey, ms)
            .putLong("${episodeKey}:updatedAt", System.currentTimeMillis()).apply()
        lastPersistedMs = ms
        Log.i("DCTV_PROGRESS", "saved positionMs=$ms durationMs=$length")
    }
    private fun restorePosition(mp: MediaPlayer) {
        if (hasRestoredPosition || resumeMs <= 0L || !mp.isSeekable) return
        val length = runCatching { mp.length }.getOrDefault(0L)
        val target = if (length > 0L && resumeMs >= length - 1000L) 0L else resumeMs
        runCatching { mp.time = target }
            .onSuccess { hasRestoredPosition = true; Log.i("DCTV_PROGRESS", "restored positionMs=$target") }
            .onFailure { Log.w("DCTV_PROGRESS", "restore failed: ${it.javaClass.simpleName}") }
    }

    private var activeHeight = 720
    private var requestSerial = 0
    private var userAgent: String? = null
    private var referer: String? = null
    // Progress persistence is independent of diagnostic logging and continues throughout playback.
    private val progressSaver = object : Runnable {
        override fun run() {
            player?.let { mp ->
                if (mp.isPlaying) persistPosition()
                restorePosition(mp)
                uiHandler.postDelayed(this, 5000L)
            }
        }
    }
    private val hideUi = Runnable {
        if (!loading && !playbackError && controls?.modalVisible != true && player?.isPlaying == true) controls?.hide()
    }
    private val controlsTicker = object : Runnable {
        override fun run() {
            if (!activityResumed || backExitRequested || isFinishing || isDestroyed || controls?.visible != true) return
            updateControls()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private fun updateControls() {
        val mp = player
        controls?.update(mp?.time ?: resumeMs, mp?.length ?: 0L,
            mp?.isPlaying == true, !retrying && !playbackError && mp?.isSeekable == true)
    }

    private fun postPlayerUpdate(mp: MediaPlayer, action: () -> Unit) {
        uiHandler.post {
            if (player === mp && !retrying && !backExitRequested && !isFinishing && !isDestroyed) action()
        }
    }

    private fun showLoading(text: String?) {
        loading = text != null
        controls?.showLoading(text)
        if (loading) uiHandler.removeCallbacks(hideUi)
    }

    private fun showPlaybackError(text: String) {
        if (backExitRequested || isFinishing || isDestroyed) return
        persistPosition(true)
        playbackError = true
        setPlaybackScreenOn(false)
        showLoading(null)
        uiHandler.removeCallbacks(hideUi)
        controls?.showError(text)
    }

    private fun togglePlayback() {
        if (playbackError || retrying || backExitRequested) return
        val mp = player ?: return
        if (mp.isPlaying) { persistPosition(true); mp.pause() } else mp.play()
        reveal()
    }

    private fun seekBy(deltaMs: Long) {
        if (playbackError || retrying || backExitRequested) return
        val mp = player ?: return
        if (!mp.isSeekable) return
        val end = mp.length.takeIf { it > 0L } ?: Long.MAX_VALUE
        mp.time = (mp.time.coerceAtLeast(0L) + deltaMs).coerceIn(0L, end)
        if (kotlin.math.abs(deltaMs) == 10_000L) controls?.showSeekFeedback(deltaMs)
        uiHandler.postDelayed({ if (player === mp) persistPosition(true) }, 500L)
        reveal()
    }

    private fun retryPlayback() {
        if (!playbackError) return
        restartPlayback()
    }

    private fun selectQuality(height: Int) {
        if (height !in listOf(480, 720, 1080) || height == activeHeight) return
        restartPlayback(height)
    }

    private fun restartPlayback(height: Int? = null) {
        if (retrying || backExitRequested || isFinishing || isDestroyed) return
        val mp = player
        val wasPlaying = mp?.isPlaying == true
        persistPosition(true)
        val currentPosition = runCatching { mp?.time ?: -1L }.getOrDefault(-1L)
        val savedPosition = if (episodeKey.isNotBlank()) progressPrefs.getLong(episodeKey, resumeMs) else resumeMs
        resumeMs = when {
            !hasRestoredPosition && resumeMs > 5000L -> resumeMs
            !playbackError && currentPosition >= 0L -> currentPosition
            else -> savedPosition
        }
        pauseAfterRestart = height != null && !playbackError && mp != null && !wasPlaying
        if (height != null) {
            activeHeight = height
            qualityPreferenceChanged = true
            controls?.setQuality(height)
        }
        retrying = true
        requestSerial++
        uiHandler.removeCallbacks(progressSaver)
        uiHandler.removeCallbacks(hideUi)
        controls?.showError(null)
        showLoading(if (height == null) "Повторное подключение…" else "Переключение на ${height}p…")
        mp?.setEventListener(null)
        runCatching { mp?.stop() }
        // Preserve the existing delay before detaching VLC's SurfaceView on Cosmos.
        uiHandler.postDelayed({
            if (backExitRequested || isFinishing || isDestroyed) return@postDelayed
            releasePlayer(alreadyStopped = true)
            hasRestoredPosition = false
            lastPersistedMs = -1L
            retrying = false
            playbackError = false
            startSelectedQuality(intent.getStringExtra("url") ?: return@postDelayed)
        }, 700L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        val url = intent.getStringExtra("url")
        if (url.isNullOrBlank() || !url.startsWith("https://") && !url.startsWith("http://")) {
            finish(); return
        }
        // Flutter's WakelockPlus only affects MainActivity, not this VLC window.
        setPlaybackScreenOn(true)
        userAgent = intent.getStringExtra("userAgent")
        referer = intent.getStringExtra("referer")
        episodeKey = intent.getStringExtra("episodeKey").orEmpty()
        activeHeight = intent.getIntExtra("quality", 720).takeIf { it in listOf(480, 720, 1080) } ?: 720
        resumeMs = intent.getLongExtra("positionMs", 0L)
        if (episodeKey.isNotBlank()) {
            val storedMs = progressPrefs.getLong(episodeKey, 0L)
            val storedAt = progressPrefs.getLong("${episodeKey}:updatedAt", 0L)
            val requestedAt = intent.getLongExtra("positionUpdatedAtMs", 0L)
            if (storedAt > requestedAt || (requestedAt == 0L && resumeMs == 0L)) resumeMs = storedMs
            Log.i("DCTV_PROGRESS", "loaded positionMs=$resumeMs")
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surface = SurfaceView(this)
        videoSurface = surface
        surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncVlcWindowSize("layout")
        }
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                Log.i("DCTV_AV1", "surfaceCreated valid=${holder.surface?.isValid}")
                syncVlcWindowSize("surfaceCreated")
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i("DCTV_AV1", "surfaceChanged width=$width height=$height format=$format")
                syncVlcWindowSize("surfaceChanged", width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                Log.i("DCTV_AV1", "surfaceDestroyed")
            }
        })
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        controls = SoftwarePlayerControls(this, root,
            intent.getIntExtra("accent", 0xFFD0BCFF.toInt()), intent.getIntExtra("onAccent", 0xFF381E72.toInt()),
            ::togglePlayback, ::seekBy, ::retryPlayback, ::exitToEpisodes, ::selectQuality, ::reveal).apply {
            title.text = intent.getStringExtra("title").orEmpty()
            subtitle.text = intent.getStringExtra("subtitle").orEmpty()
            setQuality(activeHeight)
        }
        setContentView(root)
        uiHandler.post(controlsTicker)
        startSelectedQuality(url)
    }

    /** Software AV1 fallback uses the preselected 720p or 1080p fMP4 variant. */
    private fun startSelectedQuality(master: String) {
        val serial = ++requestSerial
        Log.i("DCTV_QUALITY", "automatic fallback: requested AV1 ${activeHeight}p fMP4")
        setPlaybackScreenOn(true)
        showLoading("Открытие видео ${activeHeight}p…")
        CosmosFmp4Resolver.resolve(master, userAgent, referer, activeHeight) { direct ->
            if (backExitRequested || isFinishing || isDestroyed || serial != requestSerial) return@resolve
            if (direct == null) {
                setPlaybackScreenOn(false)
                Log.e("DCTV_QUALITY", "AV1 ${activeHeight}p fMP4 unavailable; no other quality fallback")
                showPlaybackError("Не удалось загрузить видео ${activeHeight}p. Проверьте подключение и повторите попытку.")
            } else {
                startVlcFmp4(direct, videoSurface ?: return@resolve)
            }
        }
    }

    private fun releasePlayer(alreadyStopped: Boolean = false) {
        setPlaybackScreenOn(false)
        uiHandler.removeCallbacks(progressSaver)
        val mp = player
        player = null
        mp?.let {
            runCatching { it.setEventListener(null) }
            // Stop while the SurfaceView is still attached to the window.
            if (!shutdownStarted && !alreadyStopped) runCatching { it.stop() }
            runCatching { it.vlcVout.detachViews() }
            runCatching { it.release() }
        }
        engine?.let { runCatching { it.release() } }
        engine = null
        voutCount = -1
    }

    private fun startVlcFmp4(url: String, surface: SurfaceView) {
        Log.i("DCTV_QUALITY", "starting LibVLC with forced ${activeHeight}p fMP4 media playlist")
        try {
            setPlaybackScreenOn(true)
            val vlc = LibVLC(this, arrayListOf("--no-video-title-show", "--network-caching=1200", "--avcodec-hw=none", "--verbose=0"))
            engine = vlc
            val mp = MediaPlayer(vlc)
            player = mp
            mp.vlcVout.setVideoView(surface)
            mp.vlcVout.attachViews()
            mp.setAspectRatio(null)
            mp.setScale(0f)
            syncVlcWindowSize("attached")
            Log.i("DCTV_AV1", "vlcVout attached=${mp.vlcVout.areViewsAttached()} softwareDecode=true")
            val media = Media(vlc, Uri.parse(url))
            media.setHWDecoderEnabled(false, false)
            intent.getStringExtra("userAgent")?.let { media.addOption(":http-user-agent=$it") }
            intent.getStringExtra("referer")?.let { media.addOption(":http-referrer=$it") }
            media.addOption(":adaptive-logic=lowest")
            media.addOption(":network-caching=1200")
            mp.media = media
            media.release()
            mp.setEventListener { event ->
                if (player !== mp || backExitRequested || isFinishing || isDestroyed) return@setEventListener
                when (event.type) {
                    MediaPlayer.Event.Vout -> {
                        voutCount = event.voutCount
                        if (voutCount > 0 && !outputTimingReported &&
                            applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                            outputTimingReported = true
                            val elapsedUs = (android.os.SystemClock.elapsedRealtimeNanos() - activityStartedAt) / 1000
                            Log.i("DCTV_PERF", "{\"event\":\"player.software.output_created\",\"elapsedUs\":$elapsedUs,\"outcome\":\"ok\"}")
                        }
                        Log.i("DCTV_AV1", "Vout event count=$voutCount surfaceReady=$surfaceReady")
                        postPlayerUpdate(mp) { syncVlcWindowSize("vout") }
                    }
                    MediaPlayer.Event.Opening -> {
                        setPlaybackScreenOn(true)
                        postPlayerUpdate(mp) { if (!playbackError) showLoading("Загрузка видео…") }
                        Log.i("DCTV_AV1", "Opening")
                    }
                    MediaPlayer.Event.Buffering -> {
                        val percent = event.buffering
                        postPlayerUpdate(mp) {
                            if (playbackError) return@postPlayerUpdate
                            showLoading(if (percent < 100f) "Буферизация… ${percent.toInt()}%" else null)
                            if (percent >= 100f) reveal()
                        }
                    }
                    MediaPlayer.Event.SeekableChanged -> postPlayerUpdate(mp) { restorePosition(mp) }
                    MediaPlayer.Event.Playing -> {
                        setPlaybackScreenOn(true)
                        Log.i("DCTV_AV1", "LibVLC playing; software decode requested")
                        postPlayerUpdate(mp) {
                            restorePosition(mp)
                            if (pauseAfterRestart) { pauseAfterRestart = false; mp.pause() }
                            showLoading(null); reveal()
                        }
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        setPlaybackScreenOn(false)
                        Log.e("DCTV_AV1", "LibVLC playback error")
                        postPlayerUpdate(mp) { showPlaybackError("Не удалось воспроизвести видео. Попробуйте подключиться ещё раз.") }
                    }
                    MediaPlayer.Event.EndReached -> postPlayerUpdate(mp) {
                        setPlaybackScreenOn(false)
                        episodeCompleted = true
                        if (episodeKey.isNotBlank()) progressPrefs.edit().remove(episodeKey)
                            .remove("${episodeKey}:updatedAt").apply()
                        finish()
                    }
                    MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                        setPlaybackScreenOn(false)
                        postPlayerUpdate(mp) { showLoading(null); reveal() }
                    }
                }
            }
            mp.play()
            uiHandler.postDelayed(progressSaver, 3000L)
            if (resumeMs > 5000L) uiHandler.postDelayed({ if (player === mp) restorePosition(mp) }, 2200L)
            reveal()
            Log.i("DCTV_QUALITY", "height=$activeHeight launched; videoTracks=${mp.videoTracksCount} selectedVideo=${mp.videoTrack} (initial)")
        } catch (e: Throwable) {
            setPlaybackScreenOn(false)
            Log.e("DCTV_AV1", "Failed to start LibVLC", e)
            showPlaybackError("Не удалось запустить плеер. Повторите попытку или вернитесь назад.")
        }
    }

    private fun syncVlcWindowSize(reason: String, widthOverride: Int = 0, heightOverride: Int = 0) {
        val mp = player ?: return
        val view = videoSurface ?: return
        val w = if (widthOverride > 0) widthOverride else view.width
        val h = if (heightOverride > 0) heightOverride else view.height
        if (w <= 0 || h <= 0 || !mp.vlcVout.areViewsAttached()) return
        runCatching {
            mp.vlcVout.setWindowSize(w, h)
            mp.setAspectRatio(null)
            mp.setScale(0f)
            Log.i("DCTV_LAYOUT", "windowSize=${w}x${h} reason=$reason scale=auto aspect=source")
        }.onFailure { Log.w("DCTV_LAYOUT", "sizing failed reason=$reason type=${it.javaClass.simpleName}") }
    }

    private fun reveal() {
        controls?.show()
        updateControls()
        uiHandler.removeCallbacks(controlsTicker)
        uiHandler.postDelayed(controlsTicker, 1000L)
        uiHandler.removeCallbacks(hideUi)
        uiHandler.postDelayed(hideUi, 10_000L)
    }

    // Consume BOTH DOWN and UP in this Activity. Finishing on DOWN can send the
    // matching UP to the resumed Flutter MainActivity, triggering an extra Back.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && !backExitRequested) {
                if (controls?.visible == true && !playbackError && !loading) controls?.hide()
                else exitToEpisodes()
            }
            return true
        }
        if (backExitRequested || retrying) return true
        if (event.action == KeyEvent.ACTION_DOWN && controls?.visible == true) reveal()
        // Handle shortcuts before Android's default focus search can move from
        // the video to an arbitrary button in the newly revealed panel.
        val direction = event.keyCode
        if (((direction == KeyEvent.KEYCODE_DPAD_UP || direction == KeyEvent.KEYCODE_DPAD_DOWN) &&
                controls?.hasActionFocus != true) ||
            ((direction == KeyEvent.KEYCODE_DPAD_LEFT || direction == KeyEvent.KEYCODE_DPAD_RIGHT) &&
                controls?.hasActionFocus != true)) {
            if (event.action == KeyEvent.ACTION_DOWN && !event.isCanceled) onKeyDown(direction, event)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Support legacy Android TV Back dispatch")
    override fun onBackPressed() {
        if (controls?.visible == true && !playbackError && !loading) controls?.hide()
        else exitToEpisodes()
    }

    private fun exitToEpisodes() {
        if (backExitRequested || isFinishing || isDestroyed) return
        backExitRequested = true
        requestSerial++
        uiHandler.removeCallbacks(controlsTicker)
        Log.i("DCTV_AV1", "Back pressed; stopping VLC before surface teardown")
        // Returning to Flutter destroys SurfaceView. The Cosmos Mali driver can
        // crash AWindowHandler if that happens while VLC is still rendering.
        val mp = player
        if (mp == null) {
            exitPositionMs = resumeMs
            finish()
            return
        }
        uiHandler.removeCallbacks(progressSaver)
        uiHandler.removeCallbacks(hideUi)
        persistPosition(true)
        // The delayed finish occurs AFTER releasePlayer() sets player=null.
        // Keep the same position/duration for the Activity result sent to Flutter.
        val currentMs = runCatching { mp.time }.getOrDefault(-1L)
        val savedMs = if (episodeKey.isNotBlank()) progressPrefs.getLong(episodeKey, 0L) else 0L
        exitPositionMs = when {
            episodeCompleted -> 0L
            retrying -> resumeMs
            !hasRestoredPosition && resumeMs > 5000L -> resumeMs
            playbackError -> savedMs
            currentMs >= 0L -> currentMs
            else -> savedMs
        }
        exitDurationMs = runCatching { mp.length }.getOrDefault(0L).coerceAtLeast(0L)
        Log.i("DCTV_PROGRESS", "captured before VLC stop positionMs=$exitPositionMs durationMs=$exitDurationMs")
        runCatching { mp.setEventListener(null) }
        shutdownStarted = true
        runCatching { mp.stop() }
            .onFailure { Log.w("DCTV_AV1", "VLC stop failed", it) }
        Log.i("DCTV_AV1", "VLC stop requested; delaying surface detach")
        // Give VLC's asynchronous vout/AWindowHandler thread time to quiesce
        // before detaching the view and finishing the Android Activity.
        uiHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            Log.i("DCTV_AV1", "Releasing VLC views before Activity.finish")
            releasePlayer()
            finish()
        }, 700L)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (backExitRequested || retrying) return true
        val p = player
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (controls?.hasActionFocus == true) return super.onKeyDown(keyCode, event)
                reveal()
                controls?.show(true)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (!playbackError && event?.repeatCount == 0) p?.play()
                reveal(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (!playbackError && p != null && event?.repeatCount == 0) { persistPosition(true); p.pause() }
                reveal(); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (event?.repeatCount == 0) togglePlayback()
                reveal(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (controls?.visible == true && controls?.hasActionFocus == true) return super.onKeyDown(keyCode, event)
                seekBy(-10_000L); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (controls?.visible == true && controls?.hasActionFocus == true) return super.onKeyDown(keyCode, event)
                seekBy(10_000L); return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000L); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10_000L); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        applyPlaybackScreenOn()
        if (controls?.visible == true) reveal()
    }

    override fun onPause() {
        activityResumed = false
        uiHandler.removeCallbacks(controlsTicker)
        applyPlaybackScreenOn()
        persistPosition(true)
        super.onPause()
    }

    override fun finish() {
        if (isFinishing) return
        setPlaybackScreenOn(false)
        val mp = player
        val ms = if (episodeCompleted) 0L else exitPositionMs
            ?: runCatching { mp?.time ?: 0L }.getOrDefault(0L)
        val duration = exitDurationMs
            ?: runCatching { mp?.length ?: 0L }.getOrDefault(0L)
        // Only read the active player; after release there is nothing to persist.
        if (mp != null) persistPosition(true)
        setResult(RESULT_OK, Intent().apply {
            putExtra("positionMs", ms.coerceAtLeast(0L))
            putExtra("durationMs", duration.coerceAtLeast(0L))
            putExtra("completed", episodeCompleted)
            putExtra("playbackError", playbackError)
            if (qualityPreferenceChanged) putExtra("quality", activeHeight)
        })
        Log.i("DCTV_PROGRESS", "return to Flutter positionMs=$ms durationMs=$duration completed=$episodeCompleted")
        super.finish()
    }

    override fun onDestroy() {
        controls?.dispose()
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.removeCallbacks(hideUi)
        uiHandler.removeCallbacks(progressSaver)
        requestSerial++
        persistPosition(true)
        releasePlayer()
        videoSurface = null
        super.onDestroy()
    }
}
