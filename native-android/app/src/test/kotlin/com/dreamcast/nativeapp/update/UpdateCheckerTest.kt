package com.dreamcast.nativeapp.update

import android.app.Application
import com.dreamcast.nativeapp.data.Storage
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
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
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class UpdateCheckerTest {
    private lateinit var storage: Storage
    private lateinit var server: MockWebServer
    private var clock = 1_000_000_000_000L

    @Before
    fun setup() {
        storage = Storage(RuntimeEnvironment.getApplication())
        server = MockWebServer()
        server.start()
    }

    @After
    fun close() {
        server.shutdown()
        storage.close()
    }

    private fun checker(current: String = "0.9.4") =
        UpdateChecker(storage, current, apiUrl = server.url("/latest").toString(), now = { clock })

    private fun enqueueRelease(tag: String) =
        server.enqueue(MockResponse().setBody(AppUpdatesTest.releaseJson(tag)))

    @Test
    fun `automatic check offers a newer version once a day`() = runBlocking {
        enqueueRelease("v0.9.5")
        assertEquals("0.9.5", checker().automatic()?.version)
        clock += UpdateChecker.DAY_MS - 1
        assertNull(checker().automatic())
        assertEquals(1, server.requestCount)
        clock += 1
        enqueueRelease("v0.9.5")
        assertEquals("0.9.5", checker().automatic()?.version)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `skipped version and disabled checks are respected by automatic checks only`() =
        runBlocking {
            checker().skip("0.9.5")
            enqueueRelease("v0.9.5")
            assertNull(checker().automatic())
            enqueueRelease("v0.9.5")
            assertEquals("0.9.5", checker().manual()?.version)

            checker().setAutoCheck(false)
            clock += UpdateChecker.DAY_MS
            assertNull(checker().automatic())
            assertEquals(2, server.requestCount)
            assertFalse(checker().autoCheckEnabled())
        }

    @Test
    fun `same or older release is not an update`() = runBlocking {
        enqueueRelease("v0.9.4")
        assertNull(checker().manual())
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(checker().manual())
    }

    @Test
    fun `manual check reports errors and automatic check hides them`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThrows(IOException::class.java) { runBlocking { checker().manual() } }
        server.enqueue(MockResponse().setResponseCode(503))
        assertNull(checker().automatic())
        // A failed check is retried on the next start instead of waiting a day.
        enqueueRelease("v0.9.5")
        assertEquals("0.9.5", checker().automatic()?.version)
    }

    @Test
    fun `manual check cancellation stops waiting for headers and reading a stalled body`() = runBlocking {
        for (body in listOf(false, true)) assertCheckCancellation(automatic = false, body = body)
    }

    @Test
    fun `automatic check propagates cancellation and can be retried`() = runBlocking {
        for (body in listOf(false, true)) assertCheckCancellation(automatic = true, body = body)
        enqueueRelease("v0.9.5")
        assertEquals("0.9.5", checker().automatic()?.version)
    }

    private suspend fun assertCheckCancellation(automatic: Boolean, body: Boolean) = coroutineScope {
        val calls = ConcurrentLinkedQueue<Call>()
        val readingBody = CompletableDeferred<Unit>()
        val client = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { calls.add(call) }
                override fun responseBodyStart(call: Call) { readingBody.complete(Unit) }
            })
            .build()
        val checker = UpdateChecker(
            storage, "0.9.4", client, server.url("/latest").toString(), now = { clock },
        )
        server.enqueue(
            if (body) MockResponse().setBody(AppUpdatesTest.releaseJson("v0.9.5"))
                .throttleBody(1, 1, TimeUnit.DAYS)
            else MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        )
        val returnedNormally = CompletableDeferred<Boolean>()
        val check = launch {
            try {
                if (automatic) checker.automatic() else checker.manual()
                returnedNormally.complete(true)
            } catch (cancel: CancellationException) {
                returnedNormally.complete(false)
                throw cancel
            }
        }
        try {
            assertNotNull(withContext(Dispatchers.IO) {
                server.takeRequest(5, TimeUnit.SECONDS)
            })
            if (body) withTimeout(5000) { readingBody.await() }
            withTimeout(2000) { check.cancelAndJoin() }
            assertTrue(calls.last().isCanceled())
            assertFalse(returnedNormally.await())
            assertEquals(0L, storage.number(UpdateKeys.LAST_CHECK))
        } finally {
            calls.forEach { it.cancel() }
            check.cancelAndJoin()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `download keeps a matching APK and deletes a corrupted one`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val installer = UpdateInstaller(context)
        val body = ByteArray(200_000) { (it % 251).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val release =
            parseLatestRelease(
                AppUpdatesTest.releaseJson(
                    "v0.9.5",
                    apkUrl = server.url("/apk").toString(),
                    checksumUrl = server.url("/sha").toString(),
                )
            )!!

        server.enqueue(MockResponse().setBody("$hash  ${release.apkName}\n"))
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val progress = mutableListOf<Float>()
        val apk = installer.download(release) { progress += it }
        assertArrayEquals(body, apk.readBytes())
        assertEquals(1f, progress.last())
        assertEquals(File(context.cacheDir, "updates/${release.apkName}"), apk)

        server.enqueue(MockResponse().setBody("${"0".repeat(64)}  ${release.apkName}\n"))
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        assertThrows(IOException::class.java) { runBlocking { installer.download(release) } }
        assertFalse(apk.exists())
    }
}
