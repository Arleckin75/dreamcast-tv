package com.dreamcast.nativeapp

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import com.dreamcast.nativeapp.data.VideoStream

/**
 * The player's menus: what each one lists and which entry is marked. Applying a choice is left
 * to the caller, so the menus know nothing about the running player.
 *
 * [onDismiss] runs when a settings menu closes; the error and season-end menus close the player
 * through their own callbacks instead.
 */
internal class PlayerMenus(private val context: Context, private val onDismiss: () -> Unit) {
    /** [heights] as [qualityChoices] returns them: 0 is "Авто". */
    fun quality(heights: List<Int>, selected: Int, onPick: (Int) -> Unit) {
        showPlayerMenu(
            context,
            "Качество",
            heights.map { if (it == 0) "Авто" else "${it}p" },
            heights.indexOf(selected),
            onDismiss = onDismiss,
        ) { index ->
            onPick(heights[index])
        }
    }

    fun speed(selected: Float, onPick: (Float) -> Unit) {
        val speeds = PLAYBACK_SPEEDS
        showPlayerMenu(
            context,
            "Скорость",
            speeds.map { "${it}×" },
            speeds.indexOf(selected),
            onDismiss = onDismiss,
        ) { index ->
            onPick(speeds[index])
        }
    }

    fun stream(streams: List<VideoStream>, active: VideoStream?, onPick: (VideoStream) -> Unit) {
        showPlayerMenu(
            context,
            "Поток видео",
            streams.map { it.type.uppercase() + " • ${it.quality}p" },
            streams.indexOf(active),
            onDismiss = onDismiss,
        ) { index ->
            onPick(streams[index])
        }
    }

    /**
     * [hardware] and [software]: whether that decoder can play the current video. A decoder that
     * cannot is shown disabled and never reaches [onPick].
     */
    fun decoder(
        mode: DecoderMode,
        hardware: Boolean,
        software: Boolean,
        onPick: (DecoderMode) -> Unit,
    ) {
        fun available(index: Int) =
            index == 0 || (index == 1 && hardware) || (index == 2 && software)
        showPlayerMenu(
            context,
            "Декодирование",
            listOf(
                "Авто",
                if (hardware) "Аппаратное" else "Аппаратное — недоступно для этого видео",
                if (software) "Программное AV1 — dav1d"
                else "Программное AV1 — недоступно для этого видео",
            ),
            mode.ordinal,
            onDismiss = onDismiss,
            enabledItem = ::available,
        ) { index ->
            if (available(index)) onPick(DecoderMode.entries[index])
        }
    }

    /** Audio or subtitles, then the tracks of that kind in [player]. */
    fun tracks(player: () -> Player?) {
        showPlayerMenu(
            context,
            "Звук и субтитры",
            listOf("Звук", "Субтитры"),
            onDismiss = onDismiss,
        ) { index ->
            val current = player() ?: return@showPlayerMenu
            showTrackMenu(
                context,
                current,
                if (index == 0) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT,
                onDismiss,
            )
        }
    }

    fun failure(
        message: String,
        onRetry: () -> Unit,
        onOtherStream: () -> Unit,
        onClose: () -> Unit,
    ) {
        showPlayerMenu(
            context,
            "Ошибка воспроизведения\n$message",
            listOf("Повторить", "Другой поток"),
            onCancel = onClose,
        ) { index ->
            if (index == 0) onRetry() else onOtherStream()
        }
    }

    fun seasonEnd(menu: SeasonEndMenu, onClose: () -> Unit, onPick: (SeasonEndAction) -> Unit) {
        showPlayerMenu(context, menu.title, menu.actions.map { it.label }, onCancel = onClose) {
            index ->
            onPick(menu.actions[index])
        }
    }
}
