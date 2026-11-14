package com.dreamcast.nativeapp

import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class PlayerKeysTest {
    @Test
    fun `remote keys map to player actions`() {
        assertEquals(RemoteAction.TOGGLE, remoteAction(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(RemoteAction.TOGGLE, remoteAction(KeyEvent.KEYCODE_ENTER))
        assertEquals(RemoteAction.TOGGLE, remoteAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(RemoteAction.PAUSE, remoteAction(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(RemoteAction.PLAY, remoteAction(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(RemoteAction.FORWARD, remoteAction(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(RemoteAction.REWIND, remoteAction(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertNull(remoteAction(KeyEvent.KEYCODE_BACK))
        assertNull(remoteAction(KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun `quality menu lists only the stream's heights after Auto`() {
        // Dream Cast HLS: 1080, 720 and 480 variants plus an audio-only one without a height.
        assertEquals(listOf(0, 480, 720, 1080), qualityChoices(listOf(1080, 720, 480, -1)))
        assertEquals(listOf(0, 720, 1080), qualityChoices(listOf(1080, 1080, 720)))
        assertEquals(listOf(0), qualityChoices(emptyList()))
        assertEquals(listOf(0, 1080, 2160), qualityChoices(listOf(2160, 1080)))
    }

    @Test
    fun `saved player state survives a bundle round trip`() {
        val state = PlayerSavedState("e7", 42_000, "dash", 720, 1.5f, "SOFTWARE")
        val bundle = Bundle().also(state::writeTo)
        assertEquals(state, PlayerSavedState.from(bundle))
        assertNull(PlayerSavedState.from(null))
    }

    @Test
    fun `empty bundle restores defaults`() {
        assertEquals(PlayerSavedState(null), PlayerSavedState.from(Bundle()))
    }
}
