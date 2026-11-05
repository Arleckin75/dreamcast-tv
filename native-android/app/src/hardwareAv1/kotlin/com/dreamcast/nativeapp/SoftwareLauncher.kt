package com.dreamcast.nativeapp

import com.dreamcast.nativeapp.data.*

object SoftwareLauncher {
    suspend fun launch(
        activity: MainActivity,
        release: Release,
        episode: Episode,
        stream: VideoStream,
        progress: WatchProgress?,
        storage: Storage,
    ) {
        error("Программный плеер доступен в softwareAv1")
    }
}
