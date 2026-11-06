package com.dreamcast.nativeapp

import android.content.Intent
import com.dreamcast.nativeapp.data.*

object PlayerLauncher {
    fun launch(activity: MainActivity, release: Release, episode: Episode, stream: VideoStream) {
        activity.startActivity(
            Intent(activity, PlayerActivity::class.java)
                .putExtra("release", release.json().toString())
                .putExtra("episodeId", episode.id)
                .putExtra("stream", stream.url)
        )
    }
}
