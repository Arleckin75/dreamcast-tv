package com.dreamcast.nativeapp.update

import android.app.Application
import com.dreamcast.nativeapp.data.Storage
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
