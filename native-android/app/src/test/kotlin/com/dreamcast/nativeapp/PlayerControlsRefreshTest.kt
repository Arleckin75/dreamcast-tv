package com.dreamcast.nativeapp

import android.app.Application
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerControlsRefreshTest {
    @Test
    fun hiddenControlsKeepLatestStateWithoutUpdatingViewsAndRefreshOnShow() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val controls =
            PlayerControls(
                context,
                root,
                Color.WHITE,
                Color.BLACK,
                onToggle = {},
                onSeek = {},
                onRetry = {},
                onNext = {},
                nextTitle = null,
                onInteraction = {},
            )
        try {
            val label = ReflectionHelpers.getField<TextView>(controls, "positionLabel")
            val toggle = ReflectionHelpers.getField<View>(controls, "toggle")
            val timeline = ReflectionHelpers.getField<SeekBar>(controls, "timeline")
            controls.update(5000, 60000, true, true)
            assertEquals("00:05", label.text.toString())
            controls.hide(animate = false)
            repeat(5) { controls.update(25000, 60000, false, false) }
            assertEquals("00:05", label.text.toString())
            assertEquals("Пауза", toggle.contentDescription)
            controls.show()
            assertEquals("00:25", label.text.toString())
            assertEquals("Воспроизвести", toggle.contentDescription)
            assertFalse(timeline.isEnabled)
            controls.update(30000, 60000, true, true)
            assertEquals("00:30", label.text.toString())
            assertTrue(timeline.isEnabled)
        } finally {
            controls.dispose()
        }
    }
}
