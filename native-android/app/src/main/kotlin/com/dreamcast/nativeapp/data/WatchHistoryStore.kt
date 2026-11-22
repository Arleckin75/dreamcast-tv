package com.dreamcast.nativeapp.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** Playback positions and watch history in SQLite. */
internal class WatchHistoryStore(database: Lazy<SQLiteDatabase>) {
    private val database by database

    fun progress(releaseId: Int, episodeId: String): WatchProgress? {
        val args = arrayOf(releaseId.toString(), episodeId)
        database
            .rawQuery("SELECT $ENTRY_COLUMNS FROM watch_entries w WHERE release_id=? AND episode_id=?", args)
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
    private fun Cursor.toProgress(releaseId: Int, offset: Int): WatchProgress {
        val title = getString(offset + 1)
        val poster = getString(offset + 3).orEmpty()
        // Without a stored address the release keeps its id-only default, which the site answers
        // with 404; entries saved since 1.0.4 carry the full address.
        val release =
            getString(offset + 9)?.let { Release(releaseId, title, url = it, posterUrl = poster) }
                ?: Release(releaseId, title, posterUrl = poster)
        return WatchProgress(
            release,
            getString(offset),
            getString(offset + 2),
            getInt(offset + 4),
            getLong(offset + 5),
            getLong(offset + 6),
            getInt(offset + 7) != 0,
            getLong(offset + 8) * 1000,
        )
    }

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
                "INSERT OR REPLACE INTO watch_entries" +
                    "(release_id,episode_id,release_title,episode_title,poster_url,episode_ordinal," +
                    "position_ms,duration_ms,is_watched,updated_at,release_url)" +
                    " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
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
                    pageUrl(release),
                ),
            )
        }
    }

    private companion object {
        /**
         * The last column is the newest address stored for the release in any of its episodes,
         * so an entry saved before 1.0.4 gets one as soon as another episode is watched.
         */
        const val ENTRY_COLUMNS =
            "episode_id,release_title,episode_title,poster_url,episode_ordinal,position_ms,duration_ms,is_watched,updated_at," +
                "(SELECT u.release_url FROM watch_entries u WHERE u.release_id=w.release_id AND u.release_url IS NOT NULL ORDER BY u.updated_at DESC,u.rowid DESC LIMIT 1)"

        /** The full page address, or null for the id-only default the site does not serve. */
        fun pageUrl(release: Release): String? =
            release.url.takeIf { it.isNotBlank() && it != Release(release.id, "").url }
        const val WATCHED_FRACTION = 0.9
    }
}
