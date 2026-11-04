package com.dreamcast.nativeapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dreamcast.nativeapp.data.Release
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

class EpisodeWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as DreamApplication
        val subscriptions =
            app.storage.records("notifications.release.item.").sortedBy {
                it.second.optJSONObject("release")?.optInt("id") ?: Int.MAX_VALUE
            }
        if (subscriptions.isEmpty()) return Result.success()
        val offset = (app.storage.number("notifications.check.cursor") % subscriptions.size).toInt()
        val selected =
            List(minOf(40, subscriptions.size)) {
                subscriptions[(offset + it) % subscriptions.size]
            }
        app.storage.put(
            "notifications.check.cursor",
            ((offset + selected.size) % subscriptions.size).toString(),
        )
        val latest =
            try {
                val page = app.repository.releases(refresh = true)
                if (page.stale) emptyMap() else page.items.associateBy { it.id }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                emptyMap()
            }
        var failed = false
        for ((_, record) in selected) {
            try {
                val release = Release.fromJson(record.getJSONObject("release"))
                val fresh =
                    latest[release.id]
                        ?: app.repository
                            .releases(
                                query = release.title.ifBlank { release.originalTitle },
                                refresh = true,
                            )
                            .takeUnless { it.stale }
                            ?.items
                            ?.firstOrNull { it.id == release.id }
                        ?: continue
                val count = fresh.currentEpisodes
                val previous = record.optInt("lastKnownEpisodes")
                if (count > previous && previous > 0) {
                    val id = "${release.id}-$count"
                    val message = "Доступно серий: $count (было $previous)"
                    if (app.storage.string("notifications.history.item.$id").isEmpty()) {
                        app.storage.put(
                            "notifications.history.item.$id",
                            JSONObject()
                                .put("id", id)
                                .put("release", fresh.json())
                                .put("message", message)
                                .put("previousCount", previous)
                                .put("currentCount", count)
                                .put("createdAt", Instant.now().toString())
                                .toString(),
                        )
                        notify(fresh, id, message)
                    }
                }
                app.storage.subscribe(fresh, count)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            }
        }
        return if (failed) Result.retry() else Result.success()
    }

    private fun notify(release: Release, id: String, message: String) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 26)
            context
                .getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        "episodes",
                        "Новые серии",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        )
            return
        val intent =
            Intent(context, MainActivity::class.java)
                .putExtra("release", release.json().toString())
                .putExtra("notificationId", id)
        val pending =
            PendingIntent.getActivity(
                context,
                id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        NotificationManagerCompat.from(context)
            .notify(
                id.hashCode(),
                NotificationCompat.Builder(context, "episodes")
                    .setSmallIcon(R.drawable.ic_stat_dream_cast)
                    .setContentTitle(release.title)
                    .setContentText(message)
                    .setAutoCancel(true)
                    .setContentIntent(pending)
                    .build(),
            )
    }
}
