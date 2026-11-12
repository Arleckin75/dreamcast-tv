package com.dreamcast.nativeapp.update

import org.json.JSONObject

/** A published GitHub release that carries an installable APK. */
data class AppRelease(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val checksumUrl: String,
)

/** GitHub repository whose releases are checked. */
internal const val RELEASES_API =
    "https://api.github.com/repos/Arleckin75/dreamcast-tv/releases/latest"

/**
 * Reads the response of GitHub's "latest release" endpoint.
 *
 * Returns null for drafts, pre-releases and releases without both the unified APK and its
 * SHA-256 file: an update is only offered when it can be verified.
 */
internal fun parseLatestRelease(json: String): AppRelease? {
    val release = JSONObject(json)
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
    val version = release.optString("tag_name").removePrefix("v").trim()
    if (parseVersion(version) == null) return null
    val assets = release.optJSONArray("assets") ?: return null
    val byName =
        (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .associateBy { it.optString("name") }
    val apk =
        byName.values.firstOrNull { it.optString("name").endsWith("-unified-release.apk") }
            ?: return null
    val apkName = apk.optString("name")
    val checksum = byName["$apkName.sha256"] ?: return null
    return AppRelease(
        version = version,
        notes = release.optString("body").trim(),
        pageUrl = release.optString("html_url"),
        apkName = apkName,
        apkUrl = apk.optString("browser_download_url").ifBlank { return null },
        apkSize = apk.optLong("size"),
        checksumUrl = checksum.optString("browser_download_url").ifBlank { return null },
    )
}

/** Numeric parts of a dotted version ("0.9.4" → [0, 9, 4]); null when it is not one. */
internal fun parseVersion(version: String): List<Int>? =
    version
        .split('.')
        .map { it.toIntOrNull()?.takeIf { part -> part >= 0 } ?: return null }
        .takeIf { it.isNotEmpty() }

/** True when [candidate] is a newer dotted version than [current]; "0.10" > "0.9.4". */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    val a = parseVersion(candidate) ?: return false
    val b = parseVersion(current) ?: return false
    for (index in 0 until maxOf(a.size, b.size)) {
        val diff = a.getOrElse(index) { 0 } - b.getOrElse(index) { 0 }
        if (diff != 0) return diff > 0
    }
    return false
}

/** The hash from a `sha256sum` line ("<64 hex>  file.apk"), lower case; null if malformed. */
internal fun parseSha256(text: String): String? =
    text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf {
        it.matches(Regex("[0-9a-f]{64}"))
    }

/** Release notes shortened for a TV dialog. */
internal fun shortNotes(notes: String, maxLines: Int = 12): String {
    val lines = notes.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
    return if (lines.size <= maxLines) lines.joinToString("\n")
    else (lines.take(maxLines) + "…").joinToString("\n")
}
