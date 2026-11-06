package com.dreamcast.nativeapp

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.dreamcast.nativeapp.data.Repository
import com.dreamcast.nativeapp.data.SiteClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SearchNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun remoteDownLeavesSearchAndOpensAResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as DreamApplication
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") {
                    if (request.body.readUtf8().contains("zzempty"))
                        return MockResponse().setBody("""{"releases":[],"count":0}""")
                    return MockResponse().setBody("""{"releases":[{"id":992001,"russian":"Первый результат","url":"${server.url("/home/release/992001")}"},{"id":992002,"russian":"Второй результат","url":"${server.url("/home/release/992002")}"}],"count":2}""")
                }
                return MockResponse().setBody("<h3>Первый результат</h3>")
            }
        }
        server.start()
        val oldRepository = app.repository
        app.storage.clearCache()
        app.repository = Repository(app.storage, SiteClient(server.url("/").toString().trimEnd('/')))
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { it.model.navigate(Route("search")) }
                val input = compose.onNode(hasSetTextAction())
                compose.waitUntil(15_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                val first = hasText("Первый результат") and isFocusable()
                fun imeVisible(): Boolean {
                    var shown = false
                    scenario.onActivity { activity ->
                        shown = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                    }
                    return shown
                }
                fun dismissKeyboard() {
                    input.performClick()
                    compose.waitUntil(5_000) { imeVisible() }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    compose.waitUntil(5_000) { !imeVisible() }
                    compose.waitForIdle()
                }
                fun search(query: String) {
                    input.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                    input.performTextReplacement(query)
                    dismissKeyboard()
                }
                fun key(code: Int) {
                    compose.waitUntil(5_000) {
                        var ready = false
                        scenario.onActivity { activity ->
                            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                            ready = activity.hasWindowFocus() &&
                                insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
                        }
                        ready
                    }
                    instrumentation.sendKeyDownUpSync(code)
                    compose.waitForIdle()
                }
                search("zzfocus")
                compose.waitUntil(15_000) { compose.onAllNodes(first).fetchSemanticsNodes().isNotEmpty() }
                input.assertIsFocused()
                input.performClick()
                compose.waitUntil(5_000) { imeVisible() }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.waitForIdle()
                input.assertIsFocused()
                dismissKeyboard()
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.onNode(first).assertIsFocused()
                key(KeyEvent.KEYCODE_DPAD_UP)
                input.assertIsFocused()
                dismissKeyboard()
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.onNode(first).assertIsFocused()
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                compose.onNode(hasText("Второй результат") and isFocusable()).assertIsFocused()

                search("zzempty")
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Релизы не найдены").fetchSemanticsNodes().isNotEmpty() }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                input.assertIsFocused()

                search("zzfocus")
                compose.waitUntil(15_000) { compose.onAllNodes(first).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Найти").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.onNode(first).assertIsFocused()
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity {
                    assertEquals("detail", it.model.routes.last().name)
                    assertEquals(992001, it.model.routes.last().release!!.id)
                }
            }
        } finally {
            app.repository = oldRepository
            app.storage.clearCache()
            server.shutdown()
        }
    }
}
