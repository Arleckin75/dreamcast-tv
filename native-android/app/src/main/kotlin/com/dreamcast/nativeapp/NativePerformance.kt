package com.dreamcast.nativeapp

import android.os.SystemClock
import android.os.Trace
import android.util.Log

/** Fixed event names only: never include request text, media URLs or tokens. */
internal object NativePerformance {
    val processStart = SystemClock.elapsedRealtimeNanos()
    private val firstUiFrame = java.util.concurrent.atomic.AtomicBoolean()

    fun firstFrame() {
        if (firstUiFrame.compareAndSet(false, true))
            event("startup.native.first_framework_frame", processStart)
    }

    fun now() = SystemClock.elapsedRealtimeNanos()

    fun event(name: String, started: Long, source: String = "native") {
        if (BuildConfig.DEBUG)
            Log.i(
                "DCTV_PERF",
                "{\"event\":\"$name\",\"elapsedUs\":${(now() - started) / 1000},\"source\":\"$source\",\"outcome\":\"ok\"}",
            )
    }

    inline fun <T> trace(name: String, block: () -> T): T {
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }
}
