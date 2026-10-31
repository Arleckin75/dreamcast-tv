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

/** Debug-only automatic AV1 software fallback. Separate Activity avoids modifying
 * video_player_android's Media3 player on compatible TVs. */
object CosmosAv1Launcher {
    @JvmStatic fun launch(activity: Activity, url: String, headers: Map<String, String>, positionMs: Long) {
        val intent = Intent(activity, CosmosAv1PlayerActivity::class.java)
        intent.putExtra("url", url)
        intent.putExtra("positionMs", positionMs)
        intent.putExtra("userAgent", headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value)
        intent.putExtra("referer", headers.entries.firstOrNull { it.key.equals("referer", true) }?.value)
        activity.startActivity(intent)
    }
}

class CosmosAv1PlayerActivity : Activity() {
    private var engine: LibVLC? = null
    private var player: MediaPlayer? = null
    private var message: TextView? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var uiVisible = true
    private var voutCount = -1
    private var surfaceReady = false
    private var videoSurface: SurfaceView? = null
    private var lastTimeMs = -1L
    private val activeHeight = 720
    private var requestSerial = 0
        private var userAgent: String? = null
    private var referer: String? = null
    private var stagnantSamples = 0
    private var diagnosticTicks = 0
    private val diagnosticSampler = object : Runnable {
        override fun run() {
            val mp = player ?: return
            try {
                val videoTracks = mp.videoTracksCount
                val selectedVideo = mp.videoTrack
                val size = mp.vlcVout
                val clockMs = mp.time
                if (clockMs == lastTimeMs && mp.isPlaying) stagnantSamples++ else stagnantSamples = 0
                lastTimeMs = clockMs
                Log.i("DCTV_AV1", "sample height=$activeHeight tick=$diagnosticTicks playing=${mp.isPlaying} " +
                    "timeMs=$clockMs position=${mp.position} lengthMs=${mp.length} seekable=${mp.isSeekable} " +
                    "stagnantSamples=$stagnantSamples videoTracks=$videoTracks selectedVideo=$selectedVideo " +
                    "voutEvents=$voutCount surfaceReady=$surfaceReady " +
                    "viewsAttached=${size.areViewsAttached()}")
            } catch (e: Exception) {
                Log.w("DCTV_AV1", "sample unavailable: ${e.javaClass.simpleName}")
            }
            diagnosticTicks++
            if (diagnosticTicks < 8) uiHandler.postDelayed(this, 3000L)
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
        CosmosHlsDiagnostic.inspect(url, userAgent, referer)
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
            text = "AV1 720p · программный режим | OK: пауза | ◀ ▶: ±10 сек | Back: выход"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 16f
            setPadding(20, 14, 20, 14)
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        message = status
        setContentView(root)
        start720p(url)
    }

    /** Software AV1 fallback is deliberately limited to the tested 720p fMP4 variant. */
    private fun start720p(master: String) {
        val serial = ++requestSerial
        Log.i("DCTV_QUALITY", "automatic fallback: requested AV1 720p fMP4")
        message?.apply { visibility = View.VISIBLE; text = "Проверка AV1 720p fMP4…" }
        CosmosFmp4Resolver.resolve(master, userAgent, referer, 720) { direct ->
            if (isFinishing || isDestroyed || serial != requestSerial) return@resolve
            if (direct == null) {
                Log.e("DCTV_QUALITY", "AV1 720p fMP4 unavailable; no lower or higher quality fallback")
                message?.text = "AV1 720p fMP4 недоступен. Нажмите Back."
                reveal()
            } else {
                startVlcFmp4(direct, message ?: return@resolve, videoSurface ?: return@resolve)
            }
        }
    }

    private fun releasePlayer() {
        uiHandler.removeCallbacks(diagnosticSampler)
        player?.let { mp ->
            runCatching { mp.setEventListener(null) }
            runCatching { mp.stop() }
            runCatching { mp.vlcVout.detachViews() }
            runCatching { mp.release() }
        }
        player = null
        engine?.release()
        engine = null
        voutCount = -1
    }

    private fun startVlcFmp4(url: String, status: TextView, surface: SurfaceView) {
        Log.i("DCTV_QUALITY", "starting LibVLC with forced ${activeHeight}p fMP4 media playlist")
        try {
            val vlc = LibVLC(this, arrayListOf("--no-video-title-show", "--network-caching=1200", "--avcodec-hw=none", "--verbose=2"))
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
                    MediaPlayer.Event.ESAdded -> Log.i("DCTV_AV1", "ESAdded event")
                    MediaPlayer.Event.ESSelected -> Log.i("DCTV_AV1", "ESSelected event")
                    MediaPlayer.Event.Opening -> Log.i("DCTV_AV1", "Opening")
                    MediaPlayer.Event.Buffering -> Log.i("DCTV_AV1", "Buffering event")
                    MediaPlayer.Event.Playing -> {
                        Log.i("DCTV_AV1", "LibVLC playing; software decode requested")
                        uiHandler.post { status.text = "AV1 720p · программный режим | OK: пауза | ◀ ▶: ±10 сек | Back: выход" }
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        Log.e("DCTV_AV1", "LibVLC playback error")
                        uiHandler.post { status.text = "Ошибка воспроизведения AV1. Нажмите Back."; reveal() }
                    }
                    MediaPlayer.Event.EndReached -> uiHandler.post { finish() }
                }
            }
            mp.play()
            diagnosticTicks = 0
            lastTimeMs = -1L
            stagnantSamples = 0
            uiHandler.postDelayed(diagnosticSampler, 3000L)
            val savedMs = intent.getLongExtra("positionMs", 0L)
            if (savedMs > 5000L) uiHandler.postDelayed({ runCatching { mp.time = savedMs } }, 1800L)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = player
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (p != null) { if (p.isPlaying) p.pause() else p.play() }
                reveal(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (p != null && p.isSeekable) p.time = (p.time - 10000).coerceAtLeast(0)
                reveal(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (p != null && p.isSeekable) p.time = p.time + 10000
                reveal(); return true
            }
            KeyEvent.KEYCODE_BACK -> { finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(hideUi)
        uiHandler.removeCallbacks(diagnosticSampler)
        requestSerial++
        releasePlayer()
        videoSurface = null
        super.onDestroy()
    }
}
