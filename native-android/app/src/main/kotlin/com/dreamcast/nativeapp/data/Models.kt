package com.dreamcast.nativeapp.data

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

const val BASE_URL = "https://dreamerscast.com"

fun absoluteUrl(value: String?): String? =
    value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let {
            val uri = URI(BASE_URL).resolve(it)
            require(uri.scheme in listOf("https", "http")) { "Недопустимый адрес" }
            uri.toString()
        }

fun imageUrl(value: String?): String? =
    value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let {
            if (it.startsWith("//")) "https:$it"
            else URI("https://cache.dreamerscast.com").resolve(it).toString()
        }

fun JSONObject.text(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

data class Release(
    val id: Int,
    val title: String,
    val originalTitle: String = "",
    val url: String = "$BASE_URL/home/release/$id",
    val posterUrl: String = "",
    val description: String = "",
    val year: Int = 0,
    val genres: String = "",
    val type: String = "",
    val season: String = "",
    val studio: String = "",
    val currentEpisodes: Int = 0,
    val totalEpisodes: Int = 0,
    val rating: String = "",
    val status: String = "",
    val durationMinutes: Int = 0,
) {
    fun json() =
        JSONObject().apply {
            put("id", id)
            put("title", title)
            put("originalTitle", originalTitle)
            put("url", url)
            put("posterUrl", posterUrl)
            put("description", description)
            put("year", year)
            put("genres", genres)
            put("type", type)
            put("season", season)
            put("studio", studio)
            put("currentEpisodes", currentEpisodes)
            put("totalEpisodes", totalEpisodes)
            put("rating", rating)
            put("status", status)
            put("durationMinutes", durationMinutes)
        }

    companion object {
        fun fromJson(json: JSONObject): Release {
            val api = json.has("russian")
            val id = json.getInt("id")
            val russian = json.text(if (api) "russian" else "title").trim()
            val original = json.text(if (api) "original" else "originalTitle")
            return Release(
                id,
                russian.ifEmpty { original },
                original,
                absoluteUrl(json.text("url").ifEmpty { "/home/release/$id" })!!,
                imageUrl(json.text(if (api) "image" else "posterUrl")) ?: "",
                if (api)
                    json.optJSONObject("description")?.let { it.text("text", it.text("short")) }
                        ?: ""
                else json.text("description"),
                json.optInt(if (api) "dateissue" else "year").toString().take(4).toIntOrNull() ?: 0,
                json.text("genres"),
                json.text("type"),
                json.text("season"),
                json.text("studio"),
                json.optInt(if (api) "series" else "currentEpisodes"),
                json.optInt(if (api) "currentSeries" else "totalEpisodes"),
                json.text("rating"),
                if (api) json.optJSONObject("status")?.text("statusName") ?: ""
                else json.text("status"),
                json.optInt(if (api) "duration" else "durationMinutes"),
            )
        }
    }
}

data class CatalogFilter(val year: Int? = null, val genres: Set<String> = emptySet()) {
    val cacheKey: String
        get() = "${year ?: "all"}:${genres.sorted().joinToString(",")}"
}

data class Genre(val id: String, val name: String)

data class CatalogOptions(val years: List<Int>, val genres: List<Genre>)

data class ReleasePage(
    val items: List<Release>,
    val total: Int,
    val page: Int,
    val stale: Boolean = false,
)

data class Detail(val release: Release, val payload: String, val scriptUrl: String)

data class Episode(
    val id: String,
    val releaseId: Int,
    val ordinal: Int,
    val title: String,
    val file: String,
    /** WebVTT track of seek preview frames; empty when the source has none. */
    val thumbnails: String = "",
)

data class VideoStream(val url: String, val type: String, val quality: Int)

data class ScheduleDay(val title: String, val releases: List<Release>)

data class Cached<T>(val value: T, val stale: Boolean)

enum class LibraryStatus(val label: String) {
    watching("Смотрю"),
    completed("Просмотрено"),
    dropped("Брошено"),
    planned("В планах"),
}

data class Bookmark(val release: Release, val status: LibraryStatus)

data class WatchProgress(
    val release: Release,
    val episodeId: String,
    val episodeTitle: String,
    val ordinal: Int,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val updatedAt: Long,
)
