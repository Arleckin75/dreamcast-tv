package com.dreamcast.nativeapp

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import java.util.Locale

/** Menu label for an audio or text track: its label and language, or "Дорожка N". */
internal fun trackLabel(format: Format, number: Int): String {
    val language =
        format.language
            ?.takeIf { it != "und" }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.forLanguageTag("ru")) }
            .orEmpty()
    return listOfNotNull(
            format.label?.takeIf { it.isNotBlank() },
            language.takeIf { it.isNotBlank() },
        )
        .distinct()
        .joinToString(" · ")
        .ifBlank { "Дорожка ${number + 1}" }
}

/**
 * Shows the audio or subtitle track menu for [player]: "Авто", "Выключены" and every
 * supported track of [type], and applies the selection to its track parameters.
 */
internal fun showTrackMenu(context: Context, player: Player, type: Int, onDismiss: () -> Unit) {
    val choices =
        player.currentTracks.groups
            .filter { it.type == type }
            .flatMap { group ->
                (0 until group.length)
                    .filter { group.isTrackSupported(it) }
                    .map { index -> group to index }
            }
    val labels =
        listOf("Авто", "Выключены") +
            choices.mapIndexed { number, (group, index) ->
                trackLabel(group.getTrackFormat(index), number)
            }
    val parameters = player.trackSelectionParameters
    val selected =
        if (type in parameters.disabledTrackTypes) 1
        else if (parameters.overrides.values.none { it.type == type }) 0
        else
            choices
                .indexOfFirst { (group, index) -> group.isTrackSelected(index) }
                .let { if (it < 0) 0 else it + 2 }
    showPlayerMenu(
        context,
        if (type == C.TRACK_TYPE_AUDIO) "Звук" else "Субтитры",
        labels,
        selected,
        onDismiss = onDismiss,
    ) { index ->
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(type)
                .setTrackTypeDisabled(type, index == 1)
                .apply {
                    if (index >= 2) {
                        val (group, track) = choices[index - 2]
                        addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(track)))
                    }
                }
                .build()
    }
}
