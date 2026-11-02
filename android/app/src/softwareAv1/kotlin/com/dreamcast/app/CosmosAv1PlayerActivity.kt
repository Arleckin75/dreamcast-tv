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
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/** AV1 software fallback, isolated from ExoPlayer on compatible devices. */
object CosmosAv1Launcher {
    @JvmStatic fun launch(activity: Activity, url: String, headers: Map<String, String>, positionMs: Long, episodeKey: String, quality: Int) {
        val intent = Intent(activity, CosmosAv1PlayerActivity::class.java)
        intent.putExtra("url", url)
        intent.putExtra("positionMs", positionMs)
        intent.putExtra("episodeKey", episodeKey)
        intent.putExtra("quality", if (quality in listOf(480, 720, 1080)) quality else 720)
        intent.putExtra("userAgent", headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value)
        intent.putExtra("referer", headers.entries.firstOrNull { it.key.equals("referer", true) }?.value)
        activity.startActivityForResult(intent, 7192)
    }
}

class CosmosAv1PlayerActivity : Activity() {
    private var backExitRequested = false
    private var shutdownStarted = false
    // Capture playback progress before VLC.stop()/release() invalidate player.time.
    private var exitPositionMs: Long? = null
    private var exitDurationMs: Long? = null
    private var engine: LibVLC? = null
    private var player: MediaPlayer? = null
    private var message: TextView? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var uiVisible = true
    private var voutCount = -1
    private var surfaceReady = false
    private var videoSurface: SurfaceView? = null
    private var episodeKey: String = ""
    private var episodeCompleted = false
    private var resumeMs = 0L
    private var hasRestoredPosition = false
    private var lastPersistedMs = -1L
    private val progressPrefs by lazy { getSharedPreferences("dream_cast_vlc_progress", Context.MODE_PRIVATE) }
    private fun persistPosition(force: Boolean = false) {
        val mp = player ?: return
        if (episodeKey.isBlank()) return
        val ms = runCatching { mp.time }.getOrDefault(-1L)
        if (ms < 0L) return
        if (!force && (ms < 1000L || (lastPersistedMs >= 0 && kotlin.math.abs(ms - lastPersistedMs) < 4000L))) return
        val length = runCatching { mp.length }.getOrDefault(0L)
        if (length > 0 && ms >= length - 5000L) return // Don't overwrite progress at the end.
        progressPrefs.edit().putLong(episodeKey, ms).apply()
        lastPersistedMs = ms
        Log.i("DCTV_PROGRESS", "saved positionMs=$ms durationMs=$length")
    }
    private fun restorePosition(mp: MediaPlayer) {
        if (hasRestoredPosition || resumeMs <= 5000L || !mp.isSeekable) return
        hasRestoredPosition = true
        val target = resumeMs
        runCatching { mp.time = target }
            .onSuccess { Log.i("DCTV_PROGRESS", "restored positionMs=$target") }
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
    private val hideUi = Runnable { message?.visibility = View.GONE; uiVisible = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        val url = intent.getStringExtra("url")
        if (url.isNullOrBlank() || !url.startsWith("https://") && !url.startsWith("http://")) {
            finish(); return
        }
                userAgent = intent.getStringExtra("userAgent")
        referer = intent.getStringExtra("referer")
        episodeKey = intent.getStringExtra("episodeKey").orEmpty()
        activeHeight = intent.getIntExtra("quality", 720).takeIf { it in listOf(480, 720, 1080) } ?: 720
        resumeMs = intent.getLongExtra("positionMs", 0L)
        if (episodeKey.isNotBlank()) {
            val storedMs = progressPrefs.getLong(episodeKey, 0L)
            if (storedMs > resumeMs) resumeMs = storedMs
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
        val status = TextView(this).apply {
            text = "AV1 ${activeHeight}p · программный режим | OK: пауза | ◀ ▶: ±10 сек | Back: выход"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 16f
            setPadding(20, 14, 20, 14)
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        message = status
        setContentView(root)
        startSelectedQuality(url)
    }

    /** Software AV1 fallback uses the preselected 720p or 1080p fMP4 variant. */
    private fun startSelectedQuality(master: String) {
        val serial = ++requestSerial
        Log.i("DCTV_QUALITY", "automatic fallback: requested AV1 ${activeHeight}p fMP4")
        message?.apply { visibility = View.VISIBLE; text = "Проверка AV1 ${activeHeight}p fMP4…" }
        CosmosFmp4Resolver.resolve(master, userAgent, referer, activeHeight) { direct ->
            if (isFinishing || isDestroyed || serial != requestSerial) return@resolve
            if (direct == null) {
                Log.e("DCTV_QUALITY", "AV1 ${activeHeight}p fMP4 unavailable; no other quality fallback")
                message?.text = "AV1 ${activeHeight}p fMP4 недоступен. Нажмите Back."
                reveal()
            } else {
                startVlcFmp4(direct, message ?: return@resolve, videoSurface ?: return@resolve)
            }
        }
    }

    private fun releasePlayer() {
        uiHandler.removeCallbacks(progressSaver)
        val mp = player
        player = null
        mp?.let {
            runCatching { it.setEventListener(null) }
            // Stop while the SurfaceView is still attached to the window.
            if (!shutdownStarted) runCatching { it.stop() }
            runCatching { it.vlcVout.detachViews() }
            runCatching { it.release() }
        }
        engine?.let { runCatching { it.release() } }
        engine = null
        voutCount = -1
    }

    private fun startVlcFmp4(url: String, status: TextView, surface: SurfaceView) {
        Log.i("DCTV_QUALITY", "starting LibVLC with forced ${activeHeight}p fMP4 media playlist")
        try {
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
                when (event.type) {
                    MediaPlayer.Event.Vout -> {
                        voutCount = event.voutCount
                        Log.i("DCTV_AV1", "Vout event count=$voutCount surfaceReady=$surfaceReady")
                        uiHandler.post { syncVlcWindowSize("vout") }
                    }
                    MediaPlayer.Event.Opening -> Log.i("DCTV_AV1", "Opening")
                    MediaPlayer.Event.SeekableChanged -> uiHandler.post { restorePosition(mp) }
                    MediaPlayer.Event.Playing -> {
                        Log.i("DCTV_AV1", "LibVLC playing; software decode requested")
                        uiHandler.post { restorePosition(mp) }
                        uiHandler.post { status.text = "AV1 ${activeHeight}p · программный режим | OK: пауза | ◀ ▶: ±10 сек | Back: выход" }
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        Log.e("DCTV_AV1", "LibVLC playback error")
                        uiHandler.post { status.text = "Ошибка воспроизведения AV1. Нажмите Back."; reveal() }
                    }
                    MediaPlayer.Event.EndReached -> uiHandler.post {
                        episodeCompleted = true
                        if (episodeKey.isNotBlank()) progressPrefs.edit().remove(episodeKey).apply()
                        finish()
                    }
                }
            }
            mp.play()
            uiHandler.postDelayed(progressSaver, 3000L)
            if (resumeMs > 5000L) uiHandler.postDelayed({ restorePosition(mp) }, 2200L)
            reveal()
            Log.i("DCTV_QUALITY", "height=$activeHeight launched; videoTracks=${mp.videoTracksCount} selectedVideo=${mp.videoTrack} (initial)")
        } catch (e: Throwable) {
            Log.e("DCTV_AV1", "Failed to start LibVLC", e)
            status.text = "Не удалось запустить AV1: ${e.javaClass.simpleName}. Нажмите Back."
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
        message?.visibility = View.VISIBLE
        uiVisible = true
        uiHandler.removeCallbacks(hideUi)
        uiHandler.postDelayed(hideUi, 6000)
    }

    // Consume BOTH DOWN and UP in this Activity. Finishing on DOWN can send the
    // matching UP to the resumed Flutter MainActivity, triggering an extra Back.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && !backExitRequested) {
                exitToEpisodes()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Support legacy Android TV Back dispatch")
    override fun onBackPressed() {
        exitToEpisodes()
    }

    private fun exitToEpisodes() {
        if (backExitRequested || isFinishing || isDestroyed) return
        backExitRequested = true
        Log.i("DCTV_AV1", "Back pressed; stopping VLC before surface teardown")
        // Returning to Flutter destroys SurfaceView. The Cosmos Mali driver can
        // crash AWindowHandler if that happens while VLC is still rendering.
        val mp = player
        if (mp == null) {
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
        exitPositionMs = if (episodeCompleted) 0L else if (currentMs >= 0L) currentMs else savedMs
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
        val p = player
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (p != null) { if (p.isPlaying) { persistPosition(true); p.pause() } else p.play() }
                reveal(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (p != null && p.isSeekable) { p.time = (p.time - 10000).coerceAtLeast(0); uiHandler.postDelayed({ persistPosition(true) }, 500L) }
                reveal(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (p != null && p.isSeekable) { p.time = p.time + 10000; uiHandler.postDelayed({ persistPosition(true) }, 500L) }
                reveal(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        persistPosition(true)
        super.onPause()
    }

    override fun finish() {
        if (isFinishing) return
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
        })
        Log.i("DCTV_PROGRESS", "return to Flutter positionMs=$ms durationMs=$duration completed=$episodeCompleted")
        super.finish()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(hideUi)
        uiHandler.removeCallbacks(progressSaver)
        requestSerial++
        persistPosition(true)
        releasePlayer()
        videoSurface = null
        super.onDestroy()
    }
}
