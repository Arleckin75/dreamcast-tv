package com.dreamcast.nativeapp.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** The single active viewer profile. */
internal class ProfileStore(database: Lazy<SQLiteDatabase>) {
    private val database by database

    fun profile(): String =
        database.rawQuery("SELECT name FROM profiles WHERE is_active=1 LIMIT 1", null).use {
            if (it.moveToFirst()) it.getString(0) else ""
        }

    fun saveProfile(name: String) {
        require(name.trim().length in 1..32)
        val now = System.currentTimeMillis() / 1000
        database.transaction {
            val id =
                rawQuery("SELECT id FROM profiles WHERE is_active=1 LIMIT 1", null).use {
                    if (it.moveToFirst()) it.getString(0) else "profile_$now"
                }
            execSQL("UPDATE profiles SET is_active=0")
            val created =
                rawQuery("SELECT created_at FROM profiles WHERE id=?", arrayOf(id)).use {
                    if (it.moveToFirst()) it.getLong(0) else now
                }
            insertWithOnConflict(
                "profiles",
                null,
                ContentValues().apply {
                    put("id", id)
                    put("name", name.trim())
                    put("created_at", created)
                    put("updated_at", now)
                    put("is_active", 1)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }
}
