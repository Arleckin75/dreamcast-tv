package com.dreamcast.nativeapp

import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
    fun `paused player state keeps its position and pause across recreation`() {
        val state = PlayerSavedState(
            "e7", 42_000, "dash", 720, 1.5f, "SOFTWARE", playWhenReady = false,
        )
        val restored = PlayerSavedState.from(Bundle().also(state::writeTo))!!
        assertEquals(state, restored)
        assertFalse(restored.playWhenReady)
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun `toggle pauses requested playback while the player is not yet playing`() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            player.play()
            assertFalse(player.isPlaying)
            assertTrue(player.playWhenReady)
            player.togglePlayback()
            assertFalse(player.playWhenReady)
            player.togglePlayback()
            assertTrue(player.playWhenReady)
        } finally {
            player.release()
        }
    }

    @Test
    fun `empty bundle restores defaults`() {
        assertEquals(PlayerSavedState(null), PlayerSavedState.from(Bundle()))
    }

    @Test fun repeatingTheRunningChoiceIsIgnored() {
        assertTrue(unchangedChoice("hls", "hls", failed = false))
        assertTrue(unchangedChoice(0, 0, failed = false))
        assertFalse(unchangedChoice("dash", "hls", failed = false))
        // After an error the same choice is a retry.
        assertFalse(unchangedChoice("hls", "hls", failed = true))
    }
}
