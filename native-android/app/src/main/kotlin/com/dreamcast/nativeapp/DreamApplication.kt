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
import kotlinx.coroutines.*

class DreamApplication : Application(), coil.ImageLoaderFactory {
    /**
     * Posters load over the app's shared HTTP connections (SharedHttp). The poster CDN sends a
     * one-year max-age, so Coil's default header handling already keeps them on disk.
     * Low-RAM TVs decode posters as RGB_565 (opaque JPEGs, half the memory) and keep a smaller
     * memory cache; the disk cache has a fixed budget instead of a share of free space.
     */
    override fun newImageLoader(): coil.ImageLoader {
        val lowRam =
            getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true
        return coil.ImageLoader.Builder(this)
            .okHttpClient { com.dreamcast.nativeapp.data.SharedHttp.base }
            .allowRgb565(lowRam)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(if (lowRam) 0.15 else 0.25)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(POSTER_DISK_CACHE_BYTES)
                    .build()
            }
            .build()
    }

    internal val episodeCheckMutex = kotlinx.coroutines.sync.Mutex()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startup: Deferred<Unit>? = null
    private val maintenanceStarted = java.util.concurrent.atomic.AtomicBoolean()

    internal fun maintainCacheAfterFirstFrame() {
        if (maintenanceStarted.compareAndSet(false, true)) backgroundScope.launch {
            try {
                awaitStorageReady()
                storage.maintainCache(force = true)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                android.util.Log.e("DCTV_CACHE", "Cache maintenance failed", error)
            }
        }
    }

    suspend fun awaitStorageReady() {
        startup?.await()
    }

    val storage by lazy { Storage(this) }
    internal val progressWriter by lazy {
        ProgressWriter(
            write = { snapshot ->
                storage.saveProgress(
                    snapshot.release,
                    snapshot.episode,
                    snapshot.positionMs,
                    snapshot.durationMs,
                    snapshot.completed,
                )
            },
            onError = {
                android.util.Log.e("DCTV_PROGRESS", "Could not save playback progress", it)
            },
        )
    }
    lateinit var repository: Repository

    override fun onCreate() {
        super.onCreate()
        NativePerformance.processStart
        repository = Repository(storage)
        startup =
            backgroundScope.async {
                if (storage.profile().isBlank()) storage.saveProfile("TV")
                storage.put("settings.onboarding_completed", true)
            }
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

/** Posters are ~50–150 KB each: room for a few thousand covers on small TV storage. */
private const val POSTER_DISK_CACHE_BYTES = 192L * 1024 * 1024

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
