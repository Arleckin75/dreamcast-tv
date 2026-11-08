package com.dreamcast.nativeapp.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** Playback positions and watch history in SQLite. */
internal class WatchHistoryStore(
    database: Lazy<SQLiteDatabase>,
    private val preferences: PreferenceStore,
) {
    private val database by database

    fun progress(releaseId: Int, episodeId: String): WatchProgress? {
        val args = arrayOf(releaseId.toString(), episodeId)
        database
            .rawQuery("SELECT $ENTRY_COLUMNS FROM watch_entries WHERE release_id=? AND episode_id=?", args)
            .use { if (it.moveToFirst()) return it.toProgress(releaseId, offset = 0) }
        // Older installs may have a position without a watch entry.
        return database
            .rawQuery(
                "SELECT position_ms,duration_ms,updated_at FROM playback_positions WHERE release_id=? AND episode_id=?",
                args,
            )
            .use {
                if (!it.moveToFirst()) null
                else
                    WatchProgress(
                        Release(releaseId, ""),
                        episodeId,
                        "",
                        0,
                        it.getLong(0),
                        it.getLong(1),
                        false,
                        it.getLong(2) * 1000,
                    )
            }
    }

    /** Imports positions the software (VLC) player saved after the last native update. */
    fun syncSoftwareProgress(context: Context) {
        val softwareProgress =
            context.getSharedPreferences("dream_cast_vlc_progress", Context.MODE_PRIVATE)
        val values = softwareProgress.all
        for ((timestampKey, value) in values) {
            if (!timestampKey.endsWith(":updatedAt") || value !is Long) continue
            val key = timestampKey.removeSuffix(":updatedAt")
            val position = values[key] as? Long ?: continue
            if (value <= preferences.number("$UPDATED_AT_PREFIX$key")) continue
            val releaseId = key.substringBefore(':').toIntOrNull() ?: continue
            val entry = progress(releaseId, key.substringAfter(':')) ?: continue
            if (value > preferences.number("$UPDATED_AT_PREFIX$key", entry.updatedAt)) {
                saveProgress(
                    entry.release,
                    Episode(entry.episodeId, entry.release.id, entry.ordinal, entry.episodeTitle, ""),
                    position,
                    entry.durationMs,
                )
            }
        }
    }

    /** The latest unfinished episode of each release, newest first. */
    fun continueProgress(limit: Int = 10): List<WatchProgress> =
        query(
            "WHERE is_watched=0 AND NOT EXISTS (SELECT 1 FROM watch_entries newer WHERE newer.release_id=w.release_id AND (newer.updated_at>w.updated_at OR (newer.updated_at=w.updated_at AND newer.rowid>w.rowid)))",
            emptyArray(),
            limit,
        )

    fun releaseProgress(releaseId: Int): List<WatchProgress> =
        query("WHERE release_id=?", arrayOf(releaseId.toString()))

    fun recentProgress(): List<WatchProgress> = query()

    private fun query(
        where: String = "",
        args: Array<String> = emptyArray(),
        limit: Int? = null,
    ): List<WatchProgress> =
        database
            .rawQuery(
                "SELECT release_id,$ENTRY_COLUMNS FROM watch_entries w $where ORDER BY updated_at DESC,rowid DESC" +
                    (limit?.let { " LIMIT ${it.coerceAtLeast(0)}" } ?: ""),
                args,
            )
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0).toIntOrNull() ?: continue
                        add(cursor.toProgress(id, offset = 1))
                    }
                }
            }

    /** Reads [ENTRY_COLUMNS] starting at column [offset]. */
    private fun Cursor.toProgress(releaseId: Int, offset: Int): WatchProgress =
        WatchProgress(
            Release(releaseId, getString(offset + 1), posterUrl = getString(offset + 3).orEmpty()),
            getString(offset),
            getString(offset + 2),
            getInt(offset + 4),
            getLong(offset + 5),
            getLong(offset + 6),
            getInt(offset + 7) != 0,
            getLong(offset + 8) * 1000,
        )

    fun saveProgress(
        release: Release,
        episode: Episode,
        position: Long,
        duration: Long,
        completed: Boolean = false,
    ) {
        val pos =
            position.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
        val watched = completed || (duration > 0 && pos.toDouble() / duration >= WATCHED_FRACTION)
        val now = System.currentTimeMillis() / 1000
        val knownDuration = duration.takeIf { it > 0 }
        database.transaction {
            execSQL(
                "INSERT OR REPLACE INTO playback_positions VALUES(?,?,?,?,?)",
                arrayOf<Any?>(release.id.toString(), episode.id, pos, knownDuration, now),
            )
            execSQL(
                "INSERT OR REPLACE INTO watch_entries VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    release.id.toString(),
                    episode.id,
                    release.title,
                    episode.title,
                    release.posterUrl,
                    episode.ordinal,
                    pos,
                    knownDuration,
                    if (watched) 1 else 0,
                    now,
                ),
            )
        }
        preferences.put("$UPDATED_AT_PREFIX${release.id}:${episode.id}", System.currentTimeMillis())
    }

    private companion object {
        const val ENTRY_COLUMNS =
            "episode_id,release_title,episode_title,poster_url,episode_ordinal,position_ms,duration_ms,is_watched,updated_at"
        const val UPDATED_AT_PREFIX = "progress.updatedAt."
        const val WATCHED_FRACTION = 0.9
    }
}
