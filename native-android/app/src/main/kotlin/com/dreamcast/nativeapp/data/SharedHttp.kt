package com.dreamcast.nativeapp.data

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * One connection pool and dispatcher for every HTTP client in the app: site pages, posters
 * (Coil), seek previews and update checks mostly talk to the same hosts, so they reuse open
 * connections instead of each opening their own.
 *
 * Callers that need other timeouts derive a client with [client]; derived clients still share
 * the pool and the dispatcher.
 */
object SharedHttp {
    val base: OkHttpClient by lazy { OkHttpClient.Builder().build() }

    /** A client with its own timeouts (seconds; 0 = none) on the shared pool. */
    fun client(connect: Long, read: Long, call: Long = 0): OkHttpClient =
        base
            .newBuilder()
            .connectTimeout(connect, TimeUnit.SECONDS)
            .readTimeout(read, TimeUnit.SECONDS)
            .callTimeout(call, TimeUnit.SECONDS)
            .build()
}
