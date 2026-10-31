package com.dreamcast.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterShellArgs
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val trackProbeChannelName = "dream_cast/media3_probe"
    private var trackProbeChannel: MethodChannel? = null
    private val rendererChannelName = "dream_cast/renderer_settings"
    private val rendererPrefsName = "dream_cast_renderer"
    private val impellerKey = "enable_impeller"
    private var rendererChannel: MethodChannel? = null

    // Impeller is selected before Flutter Engine initialization. It cannot be
    // changed for an already running engine; the setting applies on next launch.
    @Suppress("DEPRECATION")
    override fun getFlutterShellArgs(): FlutterShellArgs {
        val args = super.getFlutterShellArgs()
        val enabled = getSharedPreferences(rendererPrefsName, MODE_PRIVATE)
            .getBoolean(impellerKey, false) // Diagnostic default: Skia.
        args.add("--enable-impeller=$enabled")
        android.util.Log.i("DCTV_RENDERER", "Launching with Impeller enabled=$enabled")
        return args
    }

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

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        trackProbeChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, trackProbeChannelName)
        trackProbeChannel?.setMethodCallHandler { call, result ->
            if (call.method == "probe") {
                // Probe exists only in diagnostic builds; release contains no Media3 probe.
                try {
                    val probeClass = Class.forName("com.dreamcast.app.CosmosMedia3Probe")
                    val start = probeClass.getMethod("probe", Context::class.java, String::class.java, Map::class.java)
                    val uri = call.argument<String>("url")
                    val headers = call.argument<Map<String, String>>("headers") ?: emptyMap()
                    if (uri.isNullOrBlank()) result.error("NO_URL", "Missing stream URL", null)
                    else {
                        start.invoke(null, applicationContext, uri, headers)
                        result.success(null)
                    }
                } catch (error: ClassNotFoundException) {
                    result.success(null)
                } catch (error: Exception) {
                    android.util.Log.e("DCTV_TRACKS", "Could not start probe", error)
                    result.error("PROBE_FAILED", error.javaClass.simpleName, null)
                }
            } else if (call.method == "selectCompatible") {
                try {
                    val klass = Class.forName("com.dreamcast.app.CosmosMedia3Probe")
                    val start = klass.getMethod("selectCompatible", Context::class.java, List::class.java, MethodChannel.Result::class.java)
                    val candidates = call.argument<List<Map<String, Any>>>("candidates") ?: emptyList()
                    start.invoke(null, applicationContext, candidates, result)
                } catch (error: ClassNotFoundException) {
                    result.success(null) // Release: preserve existing stream selection.
                } catch (error: Exception) {
                    android.util.Log.e("DCTV_TRACKS", "Source selection probe failed", error)
                    result.success(null) // Never block playback on diagnostics.
                }
            } else if (call.method == "playSoftwareAV1") {
                try {
                    val launcher = Class.forName("com.dreamcast.app.CosmosAv1Launcher")
                    val start = launcher.getMethod("launch", android.app.Activity::class.java, String::class.java, Map::class.java, Long::class.javaPrimitiveType)
                    val uri = call.argument<String>("url")
                    val headers = call.argument<Map<String, String>>("headers") ?: emptyMap()
                    val resumeMs = call.argument<Number>("positionMs")?.toLong() ?: 0L
                    if (uri.isNullOrBlank()) result.error("NO_URL", "No video URL", null)
                    else { start.invoke(null, this, uri, headers, resumeMs); result.success(true) }
                } catch (error: Exception) {
                    android.util.Log.e("DCTV_AV1", "Software player could not launch", error)
                    result.success(false)
                }
            } else result.notImplemented()
        }
        rendererChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, rendererChannelName)
        rendererChannel?.setMethodCallHandler { call, result ->
            val prefs = getSharedPreferences(rendererPrefsName, MODE_PRIVATE)
            when (call.method) {
                "getImpellerEnabled" -> result.success(prefs.getBoolean(impellerKey, false))
                "restartForRendererChange" -> {
                    result.success(null)
                    Handler(Looper.getMainLooper()).post { restartApplication() }
                }
                "setImpellerEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled")
                    if (enabled == null) {
                        result.error("BAD_ARGUMENT", "enabled must be a boolean", null)
                    } else {
                        val saved = prefs.edit().putBoolean(impellerKey, enabled).commit()
                        if (saved) result.success(null)
                        else result.error("SAVE_FAILED", "Could not persist renderer setting", null)
                    }
                }
                else -> result.notImplemented()
            }
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

    /** Recreate the entire process: FlutterShellArgs are only read at engine startup. */
    private fun restartApplication() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val restartIntent = PendingIntent.getActivity(this, 17301, launchIntent, flags)
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + 1000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // Exact alarm permission is not guaranteed on Android 12+.
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, restartIntent)
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, restartIntent)
        }
        android.util.Log.i("DCTV_RENDERER", "Restart scheduled for Impeller change")
        finishAffinity()
        Process.killProcess(Process.myPid())
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
        rendererChannel?.setMethodCallHandler(null)
        rendererChannel = null
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
