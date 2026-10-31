package com.dreamcast.app

import android.content.Context
import android.content.Intent
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val softwarePlayerRequestCode = 7192
    private var pendingSoftwarePlayerResult: MethodChannel.Result? = null
    private val trackProbeChannelName = "dream_cast/media3_probe"
    private var trackProbeChannel: MethodChannel? = null

    private val navigationChannelName = "dream_cast/tv_navigation"
    private var navigationChannel: MethodChannel? = null
    private val backHandler = Handler(Looper.getMainLooper())
    private var backIsHeld = false
    private var backLongTriggered = false
    private val exitOnLongBack = Runnable {
        if (backIsHeld) {
            backLongTriggered = true
            finish()
        }
    }
    private val pipChannelName = "dream_cast/player_pip"
    private var pipChannel: MethodChannel? = null
    private var pipEnabled = false
    private var pipAspectRatio = Rational(16, 9)

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == softwarePlayerRequestCode) {
            val pending = pendingSoftwarePlayerResult
            pendingSoftwarePlayerResult = null
            pending?.success(if (resultCode == RESULT_OK && data != null) {
                mapOf(
                    "positionMs" to data.getLongExtra("positionMs", 0L),
                    "durationMs" to data.getLongExtra("durationMs", 0L),
                    "completed" to data.getBooleanExtra("completed", false)
                )
            } else null)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        trackProbeChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, trackProbeChannelName)
        trackProbeChannel?.setMethodCallHandler { call, result ->
            if (call.method == "selectCompatible") {
                try {
                    val candidates = call.argument<List<Map<String, Any>>>("candidates") ?: emptyList()
                    CosmosMedia3Probe.selectCompatible(applicationContext, candidates, result)
                } catch (error: Exception) {
                    android.util.Log.e("DCTV_TRACKS", "Source selection probe failed", error)
                    result.success(null) // Never block playback on source selection failures.
                }
            } else if (call.method == "playSoftwareAV1") {
                try {
                    val uri = call.argument<String>("url")
                    val headers = call.argument<Map<String, String>>("headers") ?: emptyMap()
                    val resumeMs = call.argument<Number>("positionMs")?.toLong() ?: 0L
                    val episodeKey = call.argument<String>("episodeKey").orEmpty()
                    val quality = if (call.argument<Int>("quality") == 1080) 1080 else 720
                    if (uri.isNullOrBlank()) result.error("NO_URL", "No video URL", null)
                    else {
                        if (pendingSoftwarePlayerResult != null) {
                            result.error("PLAYER_BUSY", "Software player already running", null)
                        } else {
                            pendingSoftwarePlayerResult = result
                            try { CosmosAv1Launcher.launch(this, uri, headers, resumeMs, episodeKey, quality) }
                            catch (error: Exception) { pendingSoftwarePlayerResult = null; throw error }
                        }
                    }
                } catch (error: Exception) {
                    android.util.Log.e("DCTV_AV1", "Software player could not launch", error)
                    pendingSoftwarePlayerResult?.let { pendingSoftwarePlayerResult = null; it.success(null) } ?: result.success(null)
                }
            } else result.notImplemented()
        }
        navigationChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, navigationChannelName)
        pipChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, pipChannelName)
        pipChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "isSupported" -> result.success(isPipSupported())
                "setEnabled" -> {
                    pipEnabled = call.argument<Boolean>("enabled") == true
                    pipAspectRatio = safeAspectRatio(
                        call.argument<Int>("width") ?: 16,
                        call.argument<Int>("height") ?: 9,
                    )
                    updatePictureInPictureParams()
                    result.success(null)
                }
                "enter" -> result.success(enterPipIfPossible())
                else -> result.notImplemented()
            }
        }
    }

    // Android TV KEYCODE_BACK is handled at the Activity boundary, before Flutter
    // can turn a short press into an exit from the root shell route.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0 && !backIsHeld) {
                    backIsHeld = true
                    backLongTriggered = false
                    backHandler.postDelayed(exitOnLongBack, 1500L)
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                backHandler.removeCallbacks(exitOnLongBack)
                val shouldNavigateBack = backIsHeld && !backLongTriggered && !event.isCanceled
                backIsHeld = false
                if (shouldNavigateBack) navigationChannel?.invokeMethod("shortBack", null)
                return true
            }
        }
        return true
    }

    @Deprecated("Handled for Android versions dispatching Back via the Activity callback")
    override fun onBackPressed() {
        navigationChannel?.invokeMethod("shortBack", null)
    }

    override fun onDestroy() {
        backHandler.removeCallbacks(exitOnLongBack)
        navigationChannel = null
        trackProbeChannel?.setMethodCallHandler(null)
        trackProbeChannel = null
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        if (enterPipIfPossible()) return
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipChannel?.invokeMethod(
            "changed",
            mapOf("active" to isInPictureInPictureMode),
        )
    }

    private fun enterPipIfPossible(): Boolean {
        if (!pipEnabled || !isPipSupported()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            return false
        }
        return try {
            enterPictureInPictureMode(buildPictureInPictureParams())
        } catch (_: IllegalStateException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun isPipSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun updatePictureInPictureParams() {
        if (!isPipSupported()) return
        try {
            setPictureInPictureParams(buildPictureInPictureParams())
        } catch (_: IllegalArgumentException) {
            pipAspectRatio = Rational(16, 9)
            setPictureInPictureParams(buildPictureInPictureParams())
        }
    }

    private fun buildPictureInPictureParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(pipAspectRatio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(pipEnabled)
        }
        return builder.build()
    }

    private fun safeAspectRatio(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toDouble() / height.toDouble()
        return when {
            ratio < 0.42 -> Rational(100, 239)
            ratio > 2.39 -> Rational(239, 100)
            else -> Rational(width, height)
        }
    }
}
