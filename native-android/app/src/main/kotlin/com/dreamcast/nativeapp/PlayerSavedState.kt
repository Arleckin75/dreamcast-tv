package com.dreamcast.nativeapp

import android.os.Bundle

/** Player settings kept across activity recreation. */
internal data class PlayerSavedState(
    val episodeId: String?,
    val positionMs: Long = 0,
    val streamType: String? = null,
    val qualityHeight: Int = 0,
    val playbackSpeed: Float = 1f,
    val decoderMode: String? = null,
) {
    fun writeTo(bundle: Bundle) {
        bundle.putLong("position", positionMs)
        bundle.putString("episodeId", episodeId)
        bundle.putString("streamType", streamType)
        bundle.putInt("qualityHeight", qualityHeight)
        bundle.putFloat("playbackSpeed", playbackSpeed)
        bundle.putString("decoderMode", decoderMode)
    }

    companion object {
        fun from(bundle: Bundle?): PlayerSavedState? =
            bundle?.let {
                PlayerSavedState(
                    episodeId = it.getString("episodeId"),
                    positionMs = it.getLong("position"),
                    streamType = it.getString("streamType"),
                    qualityHeight = it.getInt("qualityHeight"),
                    playbackSpeed = it.getFloat("playbackSpeed", 1f),
                    decoderMode = it.getString("decoderMode"),
                )
            }
    }
}
