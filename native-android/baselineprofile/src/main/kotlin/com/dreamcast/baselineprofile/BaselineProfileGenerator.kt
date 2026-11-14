package com.dreamcast.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks Dream Cast TV with remote keys the way it is used: cold start, the home rows, a release
 * page with its episode list, and the sections of the top menu. The code that runs here is
 * compiled ahead of time on install, so the first launch and first scrolls do not wait for the
 * JIT on a slow TV box.
 *
 * The UI has no test tags, so the walk is key presses with pauses for the live site to load; it
 * needs network. Run it with scripts/baseline_profile.ps1.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() =
        rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
            Thread.sleep(LOAD_MS) // Home: the latest releases

            // Focus starts on "Подробнее" in the banner; left/right switch the featured release.
            repeat(5) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            repeat(5) { key(KeyEvent.KEYCODE_DPAD_LEFT) }

            // The release page (focus on "Серии"), then its episode list dialog.
            openRelease()

            // "Все релизы" right below the banner opens the catalog grid; the rows under it
            // (continue watching, new episodes, schedule) are passed on the way back.
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            Thread.sleep(LOAD_MS)
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            openRelease() // a release from the catalog

            // Top menu, left to right: catalog, search, notifications, library, schedule, settings.
            for (tab in 1 until TABS) {
                repeat(6) { key(KeyEvent.KEYCODE_DPAD_UP) }
                repeat(TABS) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
                repeat(tab) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                Thread.sleep(SECTION_MS)
                repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            }
        }

    /** OK on the focused card or banner, look at the page and its episodes, and come back. */
    private fun MacrobenchmarkScope.openRelease() {
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        Thread.sleep(LOAD_MS)
        key(KeyEvent.KEYCODE_DPAD_CENTER) // "Серии"
        Thread.sleep(LOAD_MS)
        repeat(6) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(6) { key(KeyEvent.KEYCODE_DPAD_UP) }
        key(KeyEvent.KEYCODE_BACK) // closes the dialog
        Thread.sleep(SECTION_MS)
        key(KeyEvent.KEYCODE_BACK) // leaves the page
        Thread.sleep(SECTION_MS)
    }

    /** Presses [code] in the app; if a Back left the app, reopens it instead of touching others. */
    private fun MacrobenchmarkScope.key(code: Int) {
        if (device.currentPackageName != PACKAGE) {
            startActivityAndWait()
            Thread.sleep(LOAD_MS)
            return
        }
        device.pressKeyCode(code)
        device.waitForIdle()
        Thread.sleep(STEP_MS)
    }

    private companion object {
        /** The profiling build's application ID (app/build.gradle.kts). */
        const val PACKAGE = "com.dreamcast.app.profiling"
        const val LOAD_MS = 6_000L
        const val SECTION_MS = 2_500L
        const val STEP_MS = 300L
        /** Icons in the top menu. */
        const val TABS = 6
    }
}
