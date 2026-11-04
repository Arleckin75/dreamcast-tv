package com.dreamcast.nativeapp

import android.Manifest
import android.content.Intent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dreamcast.nativeapp.data.Repository
import com.dreamcast.nativeapp.data.SiteClient
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class CatalogNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun SemanticsNodeInteraction.remoteClick(): SemanticsNodeInteraction {
        performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        performKeyInput { pressKey(Key.DirectionCenter) }
        return this
    }

    @Test
    fun catalogFiltersLibraryAndBackNavigation() {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as DreamApplication
        if (android.os.Build.VERSION.SDK_INT >= 33)
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val server = MockWebServer()
        val posts = CopyOnWriteArrayList<String>()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") {
                        posts += request.body.readUtf8()
                        return MockResponse()
                            .setBody(
                                """{"releases":[{"id":540,"russian":"Тестовый релиз","url":"${server.url("/home/release/540")}","series":6,"currentSeries":12,"dateissue":2024}],"count":1}"""
                            )
                    }
                    if (request.path?.contains("/home/release/") == true)
                        return MockResponse()
                            .setBody(
                                """<h3>Тестовый релиз</h3><div class="postDesc">Описание</div><script src="/js/playerjs.js"></script><script>new Playerjs('#2placeholder')</script>"""
                            )
                    return MockResponse()
                        .setBody(
                            """<select id="dateissue"><option value="2024">2024</option><option value="2023">2023</option></select><select id="genres"><option value="magic">Магия</option><option value="romance">Романтика</option></select>"""
                        )
                }
            }
        server.start()
        val previous = app.repository
        app.storage.clearCache()
        app.repository =
            Repository(app.storage, SiteClient(server.url("/").toString().trimEnd('/')))
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
                compose.waitUntil(15000) {
                    compose.onAllNodesWithText("Тестовый релиз").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("# Каталог").remoteClick()
                compose.waitUntil(15000) {
                    compose
                        .onAllNodes(hasText("Год: любой") and isEnabled())
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithText("Год: любой").assertIsEnabled().remoteClick()
                compose.onNodeWithText("2024").remoteClick()
                compose.onNodeWithText("Год: 2024").assertExists()
                compose.onNodeWithText("Жанры: все").remoteClick()
                compose.onNodeWithText("Магия").performClick()
                compose.onNodeWithText("Романтика").performClick()
                compose.onNodeWithText("Применить").performClick()
                compose.waitUntil(15000) {
                    posts.any {
                        it.contains("dateissue%5B%5D=2024") &&
                            it.contains("genres%5B%5D=magic") &&
                            it.contains("genres%5B%5D=romance")
                    }
                }
                compose.onNodeWithText("Показать все").remoteClick()
                compose.onNodeWithText("Год: любой").assertExists()
                compose.onNodeWithText("Жанры: все").assertExists()
                compose.waitUntil(15000) {
                    compose.onAllNodesWithText("Тестовый релиз").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Тестовый релиз").performClick()
                compose.waitUntil(15000) {
                    compose.onAllNodesWithText("В библиотеку").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("В библиотеку").remoteClick()
                compose.onNodeWithText("В планах").remoteClick()
                assertEquals("planned", app.storage.string("library.release.status.540"))
                compose.onNodeWithText("‹ Назад").remoteClick()
                compose.onNodeWithText("Год: любой").assertExists()
            }
        } finally {
            app.repository = previous
            app.storage
                .bookmarks()
                .filter { it.release.id == 540 }
                .forEach { app.storage.bookmark(it.release, null) }
            app.storage.clearCache()
            server.shutdown()
        }
    }
}
