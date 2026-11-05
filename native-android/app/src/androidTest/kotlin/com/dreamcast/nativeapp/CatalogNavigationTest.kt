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

    private fun assertBackdrop(red: Boolean) {
        compose.waitUntil(7000) {
            val bitmap =
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    ?: return@waitUntil false
            try {
                val colour = bitmap.getPixel(24, bitmap.height / 2)
                val r = android.graphics.Color.red(colour)
                val b = android.graphics.Color.blue(colour)
                if (red) r > b + 15 else b > r + 15
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun saveScreenshot(app: DreamApplication, name: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(app.cacheDir, name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
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
        fun poster(colour: Int): ByteArray {
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    32,
                    48,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            bitmap.eraseColor(colour)
            return java.io.ByteArrayOutputStream().use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
                output.toByteArray()
            }
        }
        val redPoster = poster(0xffdc2828.toInt())
        val bluePoster = poster(0xff285adc.toInt())
        val playlist =
            "#2" +
                java.util.Base64.getEncoder()
                    .encodeToString(
                        """{"file":[{"title":"Серия 1","file":"https://example.com/1.mp4"},{"title":"Серия 2","file":"https://example.com/2.mp4"},{"title":"Серия 3","file":"https://example.com/3.mp4"}]}"""
                            .toByteArray()
                    )
        val posts = CopyOnWriteArrayList<String>()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/poster-red.png" || request.path == "/poster-blue.png")
                        return MockResponse()
                            .setHeader("Content-Type", "image/png")
                            .setBody(
                                okio
                                    .Buffer()
                                    .write(
                                        if (request.path == "/poster-red.png") redPoster
                                        else bluePoster
                                    )
                            )
                    if (request.method == "POST") {
                        posts += request.body.readUtf8()
                        return MockResponse()
                            .setBody(
                                """{"releases":[{"id":540,"russian":"Тестовый релиз","url":"${server.url("/home/release/540")}","image":"${server.url("/poster-red.png")}","series":6,"currentSeries":12,"dateissue":2024},{"id":541,"russian":"Другой релиз","image":"${server.url("/poster-blue.png")}","dateissue":2024}],"count":2}"""
                            )
                    }
                    if (request.path?.contains("/home/release/") == true)
                        return MockResponse()
                            .setBody(
                                """<h3>Тестовый релиз</h3><div class="postDesc">Описание</div><script src="/js/playerjs.js"></script><script>new Playerjs('$playlist')</script>"""
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
                val bannerAction = compose.onNodeWithText("Подробнее")
                bannerAction.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                assertBackdrop(red = true)
                saveScreenshot(app, "poster-backdrop-red.png")
                bannerAction.performKeyInput { pressKey(Key.DirectionLeft) }
                compose.onNodeWithText("Тестовый релиз").assertExists()
                bannerAction.performKeyInput { pressKey(Key.DirectionRight) }
                compose.onNodeWithText("Другой релиз").assertExists()
                assertBackdrop(red = false)
                saveScreenshot(app, "poster-backdrop-blue.png")
                bannerAction.performKeyInput { pressKey(Key.DirectionRight) }
                compose.onNodeWithText("Другой релиз").assertExists()
                bannerAction.performKeyInput { pressKey(Key.DirectionLeft) }
                compose.onNodeWithText("Тестовый релиз").assertExists()
                compose.onNodeWithContentDescription("Каталог").remoteClick()
                compose.waitUntil(15000) {
                    compose
                        .onAllNodes(hasText("Год: любой") and isEnabled())
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithText("Год: любой").assertIsEnabled().remoteClick()
                compose.onNode(hasText("2024") and hasAnyAncestor(isDialog())).remoteClick()
                compose.onNodeWithText("Год: 2024").assertExists()
                compose.onNodeWithText("Жанры: все").remoteClick()
                compose
                    .onNode(
                        isToggleable() and hasAnySibling(hasText("Магия")),
                        useUnmergedTree = true,
                    )
                    .performClick()
                    .assertIsOn()
                compose
                    .onNode(
                        isToggleable() and hasAnySibling(hasText("Романтика")),
                        useUnmergedTree = true,
                    )
                    .performClick()
                    .assertIsOn()
                compose.onNodeWithText("Применить").remoteClick()
                compose.onNodeWithText("Жанры: Магия, Романтика").assertExists()
                try {
                    compose.waitUntil(15000) {
                        posts.any {
                            it.contains("dateissue%5B%5D=2024") &&
                                it.contains("genres%5B%5D=magic") &&
                                it.contains("genres%5B%5D=romance")
                        }
                    }
                } catch (error: Throwable) {
                    throw AssertionError(
                        "Expected both genres and year; fixture requests: $posts",
                        error,
                    )
                }
                compose.onNodeWithText("Показать все").remoteClick()
                compose.onNodeWithText("Год: любой").assertExists()
                compose.onNodeWithText("Жанры: все").assertExists()
                compose.waitUntil(15000) {
                    compose.onAllNodesWithText("Тестовый релиз").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Тестовый релиз").performSemanticsAction(
                    SemanticsActions.RequestFocus
                ) {
                    it()
                }
                assertBackdrop(red = true)
                compose.onNodeWithText("Другой релиз").performSemanticsAction(
                    SemanticsActions.RequestFocus
                ) {
                    it()
                }
                assertBackdrop(red = false)
                compose.onNodeWithText("Тестовый релиз").performClick()
                compose.waitUntil(15000) {
                    compose.onAllNodesWithText("В библиотеку").fetchSemanticsNodes().isNotEmpty()
                }
                assertBackdrop(red = true)
                compose.onNodeWithText("Серии").remoteClick()
                compose.waitUntil(15000) {
                    compose.onAllNodesWithText("Серия 1").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasText("Серии") and hasAnyAncestor(isDialog())).assertExists()
                compose.onNodeWithText("Серия 1").assertIsFocused()
                compose.onNodeWithText("Серия 1").performKeyInput { pressKey(Key.DirectionDown) }
                compose.onNodeWithText("Серия 2").assertIsFocused()
                assertBackdrop(red = true)
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {
                    bitmap ->
                    java.io.File(app.cacheDir, "episodes-dialog.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                InstrumentationRegistry.getInstrumentation()
                    .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.onNode(hasText("Серии") and hasAnyAncestor(isDialog())).assertDoesNotExist()
                compose.onNodeWithText("В библиотеку").remoteClick()
                compose.onNodeWithText("В планах").remoteClick()
                compose.waitUntil(5000) {
                    app.storage.string("library.release.status.540") == "planned"
                }
                assertEquals("planned", app.storage.string("library.release.status.540"))
                InstrumentationRegistry.getInstrumentation()
                    .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
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
