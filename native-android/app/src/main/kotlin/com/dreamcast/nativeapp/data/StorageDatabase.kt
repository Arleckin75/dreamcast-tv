package com.dreamcast.nativeapp.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Opens the application database and creates any missing tables. */
internal fun openStorageDatabase(context: Context): SQLiteDatabase {
    // The path is inherited from the Flutter app. Moving it would lose the
    // watch history and caches of existing 0.9.x installs, so it stays.
    val directory = File(context.applicationInfo.dataDir, "app_flutter").apply { mkdirs() }
    val database = SQLiteDatabase.openOrCreateDatabase(File(directory, "dream_cast.sqlite"), null)
    database.rawQuery("PRAGMA busy_timeout=15000", null).use { it.moveToFirst() }
    database.enableWriteAheadLogging()
    SCHEMA.forEach(database::execSQL)
    return database
}

private val SCHEMA =
    listOf(
        "CREATE TABLE IF NOT EXISTS cache_entries (`key` TEXT NOT NULL PRIMARY KEY, value_json TEXT NOT NULL, updated_at INTEGER NOT NULL, expires_at INTEGER, etag TEXT)",
        "CREATE TABLE IF NOT EXISTS profiles (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, is_active INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS playback_positions (release_id TEXT NOT NULL, episode_id TEXT NOT NULL, position_ms INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER, updated_at INTEGER NOT NULL, PRIMARY KEY(release_id,episode_id))",
        "CREATE TABLE IF NOT EXISTS watch_entries (release_id TEXT NOT NULL, episode_id TEXT NOT NULL, release_title TEXT NOT NULL, episode_title TEXT NOT NULL, poster_url TEXT, episode_ordinal INTEGER NOT NULL, position_ms INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER, is_watched INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, PRIMARY KEY(release_id,episode_id))",
        "CREATE INDEX IF NOT EXISTS native_watch_recent ON watch_entries(updated_at DESC)",
        "CREATE INDEX IF NOT EXISTS native_watch_release_recent ON watch_entries(release_id,updated_at DESC)",
    )

/** Runs [block] in a transaction that is committed only if it returns normally. */
internal inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        return block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}
