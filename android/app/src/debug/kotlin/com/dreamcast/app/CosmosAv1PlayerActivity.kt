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

/** Diagnostic-only software AV1 player. Separate Activity avoids modifying
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
    private var diagnosticTicks = 0
    private val diagnosticSampler = object : Runnable {
        override fun run() {
            val mp = player ?: return
            try {
                val videoTracks = mp.videoTracksCount
                val selectedVideo = mp.videoTrack
                val size = mp.vlcVout
                Log.i("DCTV_AV1", "sample tick=$diagnosticTicks playing=${mp.isPlaying} " +
                    "timeMs=${mp.time} videoTracks=$videoTracks selectedVideo=$selectedVideo " +
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
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                Log.i("DCTV_AV1", "surfaceCreated valid=${holder.surface?.isValid}")
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i("DCTV_AV1", "surfaceChanged width=$width height=$height format=$format")
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                Log.i("DCTV_AV1", "surfaceDestroyed")
            }
        })
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        val status = TextView(this).apply {
            text = "AV1 · программное декодирование | OK: пауза | ◀ ▶: ±10 сек | Back: выход"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 16f
            setPadding(20, 14, 20, 14)
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        message = status
        setContentView(root)
        try {
            val vlc = LibVLC(this, arrayListOf("--no-video-title-show", "--network-caching=1200", "--avcodec-hw=none", "--verbose=2"))
            engine = vlc
            val mp = MediaPlayer(vlc)
            player = mp
            mp.vlcVout.setVideoView(surface)
            mp.vlcVout.attachViews()
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
                    }
                    MediaPlayer.Event.ESAdded -> Log.i("DCTV_AV1", "ESAdded event")
                    MediaPlayer.Event.ESSelected -> Log.i("DCTV_AV1", "ESSelected event")
                    MediaPlayer.Event.Opening -> Log.i("DCTV_AV1", "Opening")
                    MediaPlayer.Event.Buffering -> Log.i("DCTV_AV1", "Buffering event")
                    MediaPlayer.Event.Playing -> {
                        Log.i("DCTV_AV1", "LibVLC playing; software decode requested")
                        uiHandler.post { status.text = "AV1 · программный режим | OK: пауза | ◀ ▶: ±10 сек | Back: выход" }
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
            uiHandler.postDelayed(diagnosticSampler, 3000L)
            val savedMs = intent.getLongExtra("positionMs", 0L)
            if (savedMs > 5000L) uiHandler.postDelayed({ runCatching { mp.time = savedMs } }, 1800L)
            reveal()
            Log.i("DCTV_AV1", "Launched software fallback, stream URL withheld")
        } catch (e: Throwable) {
            Log.e("DCTV_AV1", "Failed to start LibVLC", e)
            status.text = "Не удалось запустить AV1: ${e.javaClass.simpleName}. Нажмите Back."
        }
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
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { reveal(); return true }
            KeyEvent.KEYCODE_BACK -> { finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(hideUi)
        uiHandler.removeCallbacks(diagnosticSampler)
        player?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.vlcVout.detachViews() }
            runCatching { mp.release() }
        }
        engine?.release()
        player = null
        engine = null
        super.onDestroy()
    }
}
