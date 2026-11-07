package com.dreamcast.nativeapp

import android.app.AlertDialog
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class EpisodeCompletionCountdownTest {
    private lateinit var controller: ActivityController<ComponentActivity>
    private var advances = 0

    @Before
    fun setup() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    }

    @After
    fun close() {
        if (!controller.get().isDestroyed) controller.pause().stop().destroy()
    }

    private fun start(): AlertDialog {
        EpisodeCompletionCountdown(controller.get(), "Серия 2") { advances++ }.show()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun waitSeconds(seconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS)
    }

    private fun title(dialog: AlertDialog) =
        dialog.window!!.decorView.findViewWithTag<TextView>("player-menu-title").text.toString()

    @Test
    fun `advances only after five seconds and updates remaining seconds`() {
        val dialog = start()
        assertTrue(title(dialog).contains("через 5 с"))
        waitSeconds(4)
        assertEquals(0, advances)
        assertTrue(title(dialog).contains("через 1 с"))
        waitSeconds(1)
        assertEquals(1, advances)
        assertFalse(dialog.isShowing)
        waitSeconds(5)
        assertEquals(1, advances)
    }

    @Test
    fun `back cancellation never advances later`() {
        val dialog = start()
        waitSeconds(2)
        dialog.cancel()
        waitSeconds(10)
        assertEquals(0, advances)
    }

    @Test
    fun `background cancels automatic transition but allows manual next once`() {
        val dialog = start()
        waitSeconds(2)
        controller.pause().stop()
        waitSeconds(10)
        controller.start().resume()
        waitSeconds(10)
        assertEquals(0, advances)
        assertTrue(title(dialog).contains("Автопереход отменён"))
        fun findNext(view: View): View? {
            if (view.contentDescription == "Следующая серия") return view
            if (view is ViewGroup)
                for (i in 0 until view.childCount) findNext(view.getChildAt(i))?.let {
                    return it
                }
            return null
        }
        val next = findNext(dialog.window!!.decorView)!!
        next.performClick()
        next.performClick()
        waitSeconds(10)
        assertEquals(1, advances)
    }

    @Test
    fun `destroying activity cancels pending transition`() {
        start()
        waitSeconds(2)
        controller.pause().stop().destroy()
        waitSeconds(10)
        assertEquals(0, advances)
    }
}
