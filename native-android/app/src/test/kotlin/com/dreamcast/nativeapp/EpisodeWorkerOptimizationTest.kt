package com.dreamcast.nativeapp

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.dreamcast.nativeapp.data.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class EpisodeWorkerOptimizationTest {
    @Test
    fun overlappingWorkersDoNotOverlapRequestsAndUnchangedSubscriptionsAreNotRewritten() =
        runBlocking {
            val app = DreamApplication()
            ReflectionHelpers.callInstanceMethod<Unit>(
                app,
                "attach",
                ClassParameter.from(
                    Context::class.java,
                    RuntimeEnvironment.getApplication().baseContext,
                ),
            )
            val server = MockWebServer()
            val active = AtomicInteger()
            val peak = AtomicInteger()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val count = active.incrementAndGet()
                        peak.updateAndGet { maxOf(it, count) }
                        try {
                            Thread.sleep(40)
                            return MockResponse()
                                .setBody(
                                    """{"releases":[${Release(7,"Show",currentEpisodes=2).json()}],"count":1}"""
                                )
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            server.start()
            app.repository =
                Repository(app.storage, SiteClient(server.url("/").toString().trimEnd('/')))
            val show = Release(7, "Show", currentEpisodes = 2)
            app.storage.subscribe(show, 2)
            val before = app.storage.string("notifications.release.item.7")
            try {
                val workers = List(2) { TestListenableWorkerBuilder<EpisodeWorker>(app).build() }
                val results = workers.map { async { it.doWork() } }.awaitAll()
                assertTrue(results.all { it == ListenableWorker.Result.success() })
                assertEquals(1, peak.get())
                assertEquals(before, app.storage.string("notifications.release.item.7"))
                assertEquals(2, server.requestCount)
            } finally {
                server.shutdown()
                app.storage.close()
            }
        }
}
