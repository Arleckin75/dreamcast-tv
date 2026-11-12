package com.dreamcast.nativeapp.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads a release APK, verifies its SHA-256 and hands it to the system installer. */
class UpdateInstaller(
    private val context: Context,
    private val client: OkHttpClient = UpdateChecker.defaultClient,
) {
    private val directory
        get() = File(context.cacheDir, UPDATE_DIRECTORY)

    /**
     * Downloads [release] into the cache and returns the verified file. [onProgress] receives
     * 0..1, or -1 when the size is unknown. A file whose hash does not match is deleted.
     */
    suspend fun download(release: AppRelease, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val expected =
                parseSha256(get(release.checksumUrl) { it.body.string() })
                    ?: throw IOException("Некорректная контрольная сумма обновления")
            directory.listFiles()?.forEach { it.delete() }
            directory.mkdirs()
            val target = File(directory, release.apkName.substringAfterLast('/'))
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                get(release.apkUrl) { response ->
                    val total = response.body.contentLength().takeIf { it > 0 } ?: release.apkSize
                    response.body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                done += read
                                onProgress(if (total > 0) (done.toFloat() / total).coerceAtMost(1f) else -1f)
                            }
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != expected)
                    throw IOException("Файл обновления повреждён: контрольная сумма не совпала")
                target
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }

    private fun <T> get(url: String, read: (okhttp3.Response) -> T): T =
        client
            .newCall(
                Request.Builder().url(url).header("User-Agent", "DreamCastTV").build()
            )
            .execute()
            .use { response ->
                if (!response.isSuccessful)
                    throw IOException("Не удалось скачать обновление: ошибка ${response.code}")
                read(response)
            }

    /** False on Android 8+ until the user allows this app to install packages. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** The system screen that allows this app to install packages; null before Android 8. */
    fun installPermissionIntent(): Intent? =
        if (Build.VERSION.SDK_INT < 26) null
        else
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Intent that shows the system installer for [apk]. */
    fun installIntent(apk: File): Intent {
        val uri =
            if (Build.VERSION.SDK_INT >= 24)
                FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            else Uri.fromFile(apk)
        @Suppress("DEPRECATION")
        return Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Removes a downloaded APK left from an earlier update. */
    fun clean() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val UPDATE_DIRECTORY = "updates"
    }
}
