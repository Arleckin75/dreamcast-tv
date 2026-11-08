package com.dreamcast.nativeapp

import android.view.KeyEvent

/** Remote-control and media keys the player handles. */
internal enum class RemoteAction {
    TOGGLE,
    PAUSE,
    PLAY,
    FORWARD,
    REWIND,
}

internal fun remoteAction(keyCode: Int): RemoteAction? =
    when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> RemoteAction.TOGGLE
        KeyEvent.KEYCODE_MEDIA_PAUSE -> RemoteAction.PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY -> RemoteAction.PLAY
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> RemoteAction.FORWARD
        KeyEvent.KEYCODE_MEDIA_REWIND -> RemoteAction.REWIND
        else -> null
    }

internal val DPAD_KEYS =
    setOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
    )

/** Seek step for D-pad left/right and the rewind and fast-forward keys. */
internal const val SEEK_STEP_MS = 10_000L

internal val QUALITY_HEIGHTS = listOf(0, 480, 720, 1080, 2160)

internal val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
