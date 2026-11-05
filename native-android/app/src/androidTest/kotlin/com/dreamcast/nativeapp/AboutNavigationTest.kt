package com.dreamcast.nativeapp

import android.content.Intent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class AboutNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun settingsPanelTitleRemainsVisibleAfterReturningUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario
            ->
            scenario.onActivity { it.model.navigate(Route("settings")) }
            val profile = hasText("Профиль:", substring = true) and isFocusable()
            compose.waitUntil(15000) {
                compose.onAllNodes(profile).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(profile).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            repeat(3) {
                InstrumentationRegistry.getInstrumentation()
                    .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                compose.waitForIdle()
            }
            compose.onNode(isFocused() and (hasText("Кэш") or hasText("О приложении"))).assertExists()
            repeat(3) {
                InstrumentationRegistry.getInstrumentation()
                    .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP)
                compose.waitForIdle()
            }
            compose.onNode(profile).assertIsFocused()
            compose.onNode(profile).performKeyInput { pressKey(Key.DirectionUp) }
            compose.waitForIdle()
            val viewport = compose.onNode(hasScrollAction()).getUnclippedBoundsInRoot()
            val title = compose.onNodeWithText("Профиль").getUnclippedBoundsInRoot()
            assertTrue(
                "Returning to navigation must reveal the profile panel title",
                title.top >= viewport.top,
            )
        }
    }

    @Test
    fun remoteCanRevealTheWholeHeaderAfterScrollingDown() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario
            ->
            scenario.onActivity { it.model.navigate(Route("about")) }
            val header = hasText("Dream Cast TV") and isFocusable()
            compose.waitUntil(15000) {
                compose.onAllNodes(header).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(header).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            repeat(3) {
                InstrumentationRegistry.getInstrumentation()
                    .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                compose.waitForIdle()
            }
            compose.onNode(hasText("Спасибо", substring = true) and isFocusable()).assertIsFocused()
            val scroll = compose.onNode(hasScrollAction())
            assertTrue(
                "The scenario must actually scroll down",
                scroll.fetchSemanticsNode().let {
                    it.config[
                            androidx.compose.ui.semantics.SemanticsProperties
                                .VerticalScrollAxisRange]
                        .value() > 0
                },
            )
            repeat(3) {
                InstrumentationRegistry.getInstrumentation()
                    .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP)
                compose.waitForIdle()
            }
            compose.onNode(header).assertIsFocused()
            // Clipped semantic bounds could pass even when half the header is outside the list.
            val viewport = scroll.getUnclippedBoundsInRoot()
            val bounds = compose.onNode(header).getUnclippedBoundsInRoot()
            assertTrue(
                "Header must be fully visible after returning up",
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom,
            )
            compose.onNode(header).performKeyInput { pressKey(Key.DirectionUp) }
            compose.waitForIdle()
            val after = compose.onNode(header).getUnclippedBoundsInRoot()
            assertTrue(
                "Moving to navigation must keep the header visible",
                after.top >= viewport.top,
            )
        }
    }
}
