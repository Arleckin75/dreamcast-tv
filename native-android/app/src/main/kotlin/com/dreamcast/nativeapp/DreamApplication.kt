package com.dreamcast.nativeapp

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dreamcast.nativeapp.data.Repository
import com.dreamcast.nativeapp.data.Storage
import java.util.concurrent.TimeUnit

class DreamApplication : Application() {
    val storage by lazy { Storage(this) }
    lateinit var repository: Repository

    override fun onCreate() {
        super.onCreate()
        repository = Repository(storage)
        if (storage.profile().isBlank()) storage.saveProfile("TV")
        storage.put("settings.onboarding_completed", true)
        WorkManager.getInstance(this).cancelUniqueWork("dream_cast_episode_notification_periodic")
        WorkManager.getInstance(this).cancelUniqueWork("dream_cast_episode_notification_once")
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "native-episode-notifications",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<EpisodeWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .build(),
            )
    }
}

object EpisodeScheduler {
    fun checkSoon(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "native-episode-notifications-once",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<EpisodeWorker>()
                    .setInitialDelay(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .build(),
            )
    }
}
