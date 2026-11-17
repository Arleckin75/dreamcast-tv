package com.dreamcast.nativeapp.update

import android.app.Application
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class UpdateInstallerTest {
    @Test
    fun `cancellation interrupts a stalled checksum request`() = runBlocking {
        assertCancellation(checksum = true, body = false)
    }

    @Test
    fun `cancellation interrupts waiting for APK headers`() = runBlocking {
        assertCancellation(checksum = false, body = false)
    }

    @Test
    fun `cancellation interrupts reading the APK and deletes the partial file`() = runBlocking {
        assertCancellation(checksum = false, body = true)
    }

    private suspend fun assertCancellation(checksum: Boolean, body: Boolean) = coroutineScope {
        val server = MockWebServer()
        server.start()
        val calls = ConcurrentLinkedQueue<Call>()
        val client = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { calls.add(call) }
            })
            .build()
        val context = RuntimeEnvironment.getApplication()
        val installer = UpdateInstaller(context, client)
        val release = parseLatestRelease(
            AppUpdatesTest.releaseJson(
                "v0.9.5",
                apkUrl = server.url("/apk").toString(),
                checksumUrl = server.url("/sha").toString(),
            )
        )!!
        val target = File(context.cacheDir, "updates/${release.apkName}")
        installer.clean()
        if (!checksum) server.enqueue(MockResponse().setBody("0".repeat(64)))
        server.enqueue(
            if (body) MockResponse().setBody("x".repeat(200_000))
                .throttleBody(1024, 1, TimeUnit.DAYS)
            else MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        )
        val progress = CompletableDeferred<Unit>()
        val download = launch { installer.download(release) { progress.complete(Unit) } }
        try {
            repeat(if (checksum) 1 else 2) {
                assertNotNull(withContext(Dispatchers.IO) {
                    server.takeRequest(5, TimeUnit.SECONDS)
                })
            }
            if (body) {
                withTimeout(5000) { progress.await() }
                assertTrue(target.exists())
                assertTrue(target.length() > 0)
            }
            withTimeout(2000) { download.cancelAndJoin() }
            assertTrue(download.isCancelled)
            assertTrue(calls.last().isCanceled())
            assertFalse(target.exists())
        } finally {
            calls.forEach { it.cancel() }
            download.cancelAndJoin()
            server.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
