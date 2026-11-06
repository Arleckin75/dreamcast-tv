package com.dreamcast.nativeapp

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class OpenSourceLicensesTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun everyDisplayedLicenseIsReadableFromInstalledApk() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val licenses = listOf(applicationLicense) + playerLicenses()
        for (notice in licenses) for (asset in notice.assets) {
            val text = app.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertTrue("Missing full text for ${notice.title}: $asset", text.length > 500)
        }
        assertTrue(licenses.any { it.title == "dav1d 1.5.1" })
        val bsd = app.assets.open("licenses/dav1d-BSD-2-Clause.txt")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(bsd.contains("Copyright © 2018-2019, VideoLAN and dav1d authors"))
        assertTrue(bsd.contains("Redistribution and use"))
        assertTrue(bsd.contains("THIS SOFTWARE IS PROVIDED"))
    }

    @Test
    fun playerLicenseOpensOfflineFromAbout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { it.model.navigate(Route("about")) }
            compose.waitUntil(15000) {
                compose.onAllNodesWithText("Dream Cast TV")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            val title = "dav1d 1.5.1"
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
            compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.waitForIdle()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            val required = "VideoLAN and dav1d authors"
            try {
                compose.waitUntil(15000) {
                    compose.onAllNodes(hasText(required, substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (error: Exception) {
                val roots = compose.onAllNodes(isRoot())
                val tree = roots.fetchSemanticsNodes().indices.joinToString("\n") {
                    roots[it].printToString().take(12000)
                }
                throw AssertionError("License text did not open: $title\n$tree", error)
            }
            compose.onNode(hasText(required, substring = true)).assertExists()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5000) {
                compose.onAllNodes(hasText(required, substring = true)).fetchSemanticsNodes().isEmpty()
            }
        }
    }
}
