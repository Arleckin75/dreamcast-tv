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
 * page with its episode list, an episode in the player, and the sections of the top menu. The
 * code that runs here is compiled ahead of time on install, so the first launch, first scrolls
 * and first episode do not wait for the JIT on a slow TV box.
 *
 * The profile is one list of methods for every device, so it only contains code that ran here.
 * The episode is therefore also played with software AV1 (dav1d): on a TV with a hardware
 * decoder that path would otherwise never run, and the weak boxes that need it would get a
 * profile without it.
 *
 * The UI has no test tags, so the walk is key presses with pauses for the live site to load; it
 * needs network. Run it with scripts/baseline_profile.ps1.
 *
 * The walk stays inside the app: no Home key, and no Back that could leave the app (the way back
 * to the home screen is relaunching the app's own activity). Every key is sent only while the
 * profiling build is in front; if anything else is (a system dialog, the launcher), the run
 * stops instead of pressing keys there.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() =
        rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
            startActivityAndWait()
            device.waitForIdle()
            Thread.sleep(LOAD_MS) // Home: the latest releases

            // Focus starts on "Подробнее" in the banner; left/right switch the featured release.
            repeat(5) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            repeat(5) { key(KeyEvent.KEYCODE_DPAD_LEFT) }

            // The release page (focus on "Серии"), then its episode list dialog.
            openRelease()

            // The first featured release's first episode: player, then the dav1d path.
            watchEpisode()

            // "Все релизы" right below the banner opens the catalog grid; the rows under it
            // (continue watching, new episodes, schedule) are passed on the way back.
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            Thread.sleep(LOAD_MS)
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            openRelease() // a release from the catalog

            // Top menu, left to right: catalog, search, notifications, library, schedule, settings.
            // Search is skipped: its text field opens the system on-screen keyboard.
            for (tab in 2 until TABS) {
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
        backToHome()
    }

    /** Back to the app's home screen by relaunching its activity, never by a Back that could exit. */
    private fun MacrobenchmarkScope.backToHome() {
        startActivityAndWait()
        Thread.sleep(LOAD_MS)
    }

    /**
     * Plays the first episode of the featured release with the default decoder, switches the
     * player to software AV1 (dav1d) and plays on long enough for the frame checks (every 5 s)
     * and the quality logic to run, then returns to the home screen.
     */
    private fun MacrobenchmarkScope.watchEpisode() {
        key(KeyEvent.KEYCODE_DPAD_CENTER) // the banner's release
        Thread.sleep(LOAD_MS)
        key(KeyEvent.KEYCODE_DPAD_CENTER) // "Серии"
        Thread.sleep(LOAD_MS)
        key(KeyEvent.KEYCODE_DPAD_CENTER) // episode 1 (or its resume button)
        Thread.sleep(PLAY_MS)
        // Up shows the controls with focus on play/pause; the settings button is three to the right.
        key(KeyEvent.KEYCODE_DPAD_UP)
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        key(KeyEvent.KEYCODE_DPAD_CENTER) // "Настройки": quality, speed, decoding, stream
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        key(KeyEvent.KEYCODE_DPAD_CENTER) // "Декодирование": auto, hardware, dav1d
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        Thread.sleep(DAV1D_PLAY_MS)
        key(KeyEvent.KEYCODE_BACK) // leaves the player
        Thread.sleep(SECTION_MS)
        backToHome()
    }

    /** Presses [code] in the app; stops the whole run if anything else is in front. */
    private fun MacrobenchmarkScope.key(code: Int) {
        val front = device.currentPackageName
        check(front == PACKAGE) { "Stopped: $front is in front, no keys are sent outside the app" }
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
        /** First episode start with the default decoder. */
        const val PLAY_MS = 15_000L
        /** Long enough for several 5 s frame checks with dav1d. */
        const val DAV1D_PLAY_MS = 40_000L
        /** Icons in the top menu. */
        const val TABS = 6
    }
}
