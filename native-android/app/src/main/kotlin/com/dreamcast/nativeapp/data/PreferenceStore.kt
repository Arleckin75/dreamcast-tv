package com.dreamcast.nativeapp.data

import android.content.SharedPreferences
import org.json.JSONObject

/** Typed access to the native SharedPreferences file, tolerant of values stored as other types. */
internal class PreferenceStore(private val preferences: SharedPreferences) {
    fun string(key: String, default: String = ""): String {
        return try {
            preferences.getString(key, default) ?: default
        } catch (_: ClassCastException) {
            default
        }
    }

    fun number(key: String, default: Long = 0): Long {
        if (!preferences.contains(key)) return default
        try {
            return preferences.getLong(key, default)
        } catch (_: ClassCastException) {}
        try {
            return preferences.getInt(key, 0).toLong()
        } catch (_: ClassCastException) {}
        try {
            return preferences.getFloat(key, 0f).toLong()
        } catch (_: ClassCastException) {}
        return try {
            preferences.getString(key, null)?.toLongOrNull() ?: default
        } catch (_: ClassCastException) {
            default
        }
    }

    fun boolean(key: String, default: Boolean = false) = preferences.getBoolean(key, default)

    fun put(key: String, value: String) = edit { putString(key, value) }

    fun put(key: String, value: Boolean) = edit { putBoolean(key, value) }

    fun put(key: String, value: Long) = edit { putLong(key, value) }

    fun remove(key: String) = edit { remove(key) }

    /** Applies several changes in one asynchronous commit. */
    fun edit(block: SharedPreferences.Editor.() -> Unit) {
        preferences.edit().apply(block).apply()
    }

    fun snapshot(): Map<String, Any?> = preferences.all.toMap()

    /** JSON records stored under keys with [prefix]; malformed values are skipped. */
    fun records(
        prefix: String,
        snapshot: Map<String, Any?> = snapshot(),
    ): List<Pair<String, JSONObject>> =
        snapshot
            .asSequence()
            .filter { it.key.startsWith(prefix) }
            .mapNotNull { (key, value) ->
                runCatching { key to JSONObject(value as String) }.getOrNull()
            }
            .toList()
}
