package com.dreamcast.nativeapp

import android.app.Application
import android.os.Looper
import com.dreamcast.app.CosmosFmp4Resolver
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ResolverCancellationTest {
    @Test
    fun cancelledNetworkResolutionDoesNotDelayTheNextSelectionOrDeliverItsCallback() {
        val server = MockWebServer()
        server.start()
        val cancelledCallback = AtomicBoolean()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val old =
                CosmosFmp4Resolver.resolve(server.url("/slow").toString(), null, null, 720) {
                    cancelledCallback.set(true)
                }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            old.cancel()
            server.enqueue(
                MockResponse()
                    .setBody(
                        "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1280x720,CODECS=\"av01.0.05M.08\"\nmedia.m3u8"
                    )
            )
            server.enqueue(
                MockResponse()
                    .setBody(
                        "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\nsegment.m4s\n#EXT-X-ENDLIST"
                    )
            )
            val result = AtomicReference<String?>()
            val current =
                CosmosFmp4Resolver.resolve(server.url("/master.m3u8").toString(), null, null, 720) {
                    result.set(it)
                }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (result.get() == null && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                assertEquals(server.url("/media.m3u8").toString(), result.get())
                assertFalse(cancelledCallback.get())
            } finally {
                current.cancel()
            }
        } finally {
            server.shutdown()
        }
    }
}
