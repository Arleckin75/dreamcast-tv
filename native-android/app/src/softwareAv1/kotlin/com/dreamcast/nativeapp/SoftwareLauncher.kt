package com.dreamcast.nativeapp

import android.content.Intent
import com.dreamcast.app.CosmosAv1PlayerActivity
import com.dreamcast.nativeapp.data.*

object SoftwareLauncher {
    fun launch(
        activity: MainActivity,
        release: Release,
        episode: Episode,
        stream: VideoStream,
        progress: WatchProgress?,
        storage: Storage,
    ) {
        activity.startActivityForResult(
            Intent(activity, CosmosAv1PlayerActivity::class.java).apply {
                putExtra("url", stream.url)
                putExtra("episodeKey", "${release.id}:${episode.id}")
                putExtra(
                    "positionMs",
                    if (progress?.watched == true) 0 else progress?.positionMs ?: 0,
                )
                putExtra(
                    "positionUpdatedAtMs",
                    storage.number(
                        "progress.updatedAt.${release.id}:${episode.id}",
                        progress?.updatedAt ?: 0,
                    ),
                )
                putExtra("quality", storage.number("software_av1_quality", 720).toInt())
                putExtra("title", release.title)
                putExtra("subtitle", episode.title)
                putExtra("userAgent", activity.model.repository.site.headers["User-Agent"])
                putExtra("referer", "$BASE_URL/")
                if (episode.ordinal < release.currentEpisodes)
                    putExtra("nextTitle", "Серия ${episode.ordinal+1}")
            },
            7192,
        )
    }
}
