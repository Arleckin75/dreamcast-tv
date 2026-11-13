package com.dreamcast.nativeapp.update

import com.dreamcast.nativeapp.data.Storage
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Settings keys of the update check. */
internal object UpdateKeys {
    /** Whether the app checks for updates by itself on start; on by default. */
    const val AUTO = "update.auto_check"

    /** Time of the last automatic check, epoch milliseconds. */
    const val LAST_CHECK = "update.last_check"

    /** Version the user chose to skip; automatic checks do not offer it again. */
    const val SKIPPED = "update.skipped_version"
}

/**
 * Asks GitHub for the latest release.
 *
 * Automatic checks run at most once a day, can be turned off, and never offer a version the
 * user skipped. A manual check ignores all three.
 */
class UpdateChecker(
    private val storage: Storage,
    private val currentVersion: String,
    private val client: OkHttpClient = defaultClient,
    private val apiUrl: String = RELEASES_API,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun autoCheckEnabled() = storage.boolean(UpdateKeys.AUTO, true)

    fun setAutoCheck(enabled: Boolean) = storage.put(UpdateKeys.AUTO, enabled)

    fun skip(version: String) = storage.put(UpdateKeys.SKIPPED, version)

    /** A newer release to offer on start, or null; errors are swallowed. */
    suspend fun automatic(): AppRelease? {
        if (!autoCheckEnabled()) return null
        val last = storage.number(UpdateKeys.LAST_CHECK)
        if (now() - last in 0 until DAY_MS) return null
        val release = runCatching { latest() }.getOrNull() ?: return null
        storage.put(UpdateKeys.LAST_CHECK, now().toString())
        return release.takeIf {
            isNewerVersion(it.version, currentVersion) &&
                it.version != storage.string(UpdateKeys.SKIPPED)
        }
    }

    /** A newer release, or null when this version is the latest; throws on network errors. */
    suspend fun manual(): AppRelease? =
        latest()?.takeIf { isNewerVersion(it.version, currentVersion) }

    private suspend fun latest(): AppRelease? =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "DreamCastTV/$currentVersion")
                    .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return@use null
                if (!response.isSuccessful)
                    throw IOException("GitHub ответил ошибкой ${response.code}")
                parseLatestRelease(response.body.string())
            }
        }

    internal companion object {
        const val DAY_MS = 24 * 3600 * 1000L

        val defaultClient: OkHttpClient by lazy {
            com.dreamcast.nativeapp.data.SharedHttp.client(connect = 20, read = 60)
        }
    }
}
