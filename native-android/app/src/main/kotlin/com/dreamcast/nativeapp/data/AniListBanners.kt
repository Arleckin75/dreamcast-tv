package com.dreamcast.nativeapp.data

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * A wide banner for a release from AniList, found by its original title and year. The only
 * request outside the site host, and only while "Фон релиза: баннер тайтла" is on: the site
 * itself has no wide artwork (its "wall" field is always empty).
 */
object AniListBanners {
    private val client = SharedHttp.client(connect = 5, read = 10, call = 12)

    /** What AniList answered for a release: [banner] is empty when it has none. */
    private data class Answer(val title: String, val year: Int, val banner: String, val checkedAt: Long)

    private val answers = ConcurrentHashMap<Int, Answer>()

    /** How long a found banner is trusted before AniList is asked again. */
    private const val BANNER_RECHECK_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * "No banner" is asked again sooner: a new season often gets its AniList page, or its
     * banner, only after it appears on the site.
     */
    private const val NO_BANNER_RECHECK_MS = 24L * 60 * 60 * 1000

    suspend fun find(storage: Storage, release: Release): String? {
        val title = release.originalTitle
        if (title.isBlank()) return null
        // The answer from this run, else from an earlier one; one for another title or year (the
        // site renamed the release) is not about this release any more.
        val known =
            (answers[release.id] ?: withContext(Dispatchers.IO) { read(storage, release.id) })
                ?.takeIf { it.title == title && it.year == release.year }
        val recheck = if (known?.banner.isNullOrEmpty()) NO_BANNER_RECHECK_MS else BANNER_RECHECK_MS
        if (known != null && System.currentTimeMillis() - known.checkedAt in 0 until recheck) {
            answers[release.id] = known
            return known.banner.ifEmpty { null }
        }
        val result =
            withContext(Dispatchers.IO) {
                runCatching {
                    pick(search(title), title, release.year)
                        ?: pick(search(shortTitle(title)), title, release.year)
                }
            }
        // A network or AniList error is not an answer: keep the previous one, if any, and ask
        // again on the next visit.
        val url =
            result.getOrElse {
                if (it is CancellationException) throw it
                return known?.banner?.ifEmpty { null }
            }
        val answer = Answer(title, release.year, url.orEmpty(), System.currentTimeMillis())
        answers[release.id] = answer
        withContext(Dispatchers.IO) { write(storage, release.id, answer) }
        return url
    }

    /**
     * Answers live in the response cache, so "Очистить весь кэш" clears them too; the user's
     * retention may drop them sooner than [BANNER_RECHECK_MS], which only means an earlier lookup.
     */
    private fun key(id: Int) = "anilist:banner:$id"

    private fun read(storage: Storage, id: Int): Answer? =
        runCatching {
                val json = JSONObject(storage.cache(key(id))?.value ?: return null)
                Answer(
                    json.getString("title"),
                    json.getInt("year"),
                    json.getString("banner"),
                    json.getLong("checkedAt"),
                )
            }
            .getOrNull()

    private fun write(storage: Storage, id: Int, answer: Answer) {
        storage.putCache(
            key(id),
            JSONObject()
                .put("title", answer.title)
                .put("year", answer.year)
                .put("banner", answer.banner)
                .put("checkedAt", answer.checkedAt)
                .toString(),
        )
    }

    private fun search(title: String): List<JSONObject> {
        val query =
            "query(\$s:String){Page(perPage:8){media(search:\$s,type:ANIME)" +
                "{seasonYear startDate{year} title{romaji english} synonyms bannerImage}}}"
        val body =
            JSONObject()
                .put("query", query)
                .put("variables", JSONObject().put("s", title))
                .toString()
                .toRequestBody("application/json".toMediaType())
        val request =
            Request.Builder()
                .url("https://graphql.anilist.co")
                .header("Accept", "application/json")
                .post(body)
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("AniList: HTTP ${response.code}")
            return JSONObject(response.body.string())
                .getJSONObject("data")
                .getJSONObject("Page")
                .getJSONArray("media")
                .objects()
        }
    }

    /**
     * The banner of the best candidate: the exact title in the same year, then the exact title a
     * year apart (sequels and second cours are often dated differently), then the exact title
     * with no year on AniList yet (an announced season without dates), then the first candidate
     * of the same year in AniList's own relevance order.
     */
    internal fun pick(candidates: List<JSONObject>, title: String, year: Int): String? {
        fun yearOf(it: JSONObject) =
            it.optInt("seasonYear").takeIf { y -> y > 0 }
                ?: it.optJSONObject("startDate")?.optInt("year")?.takeIf { y -> y > 0 }
        fun names(it: JSONObject): Set<String> {
            val names = it.optJSONObject("title")
            val synonyms = it.optJSONArray("synonyms")
            return (listOf(names?.text("romaji").orEmpty(), names?.text("english").orEmpty()) +
                    (0 until (synonyms?.length() ?: 0)).map { i -> synonyms!!.optString(i) })
                .map(::normalized)
                .filter(String::isNotEmpty)
                .toSet()
        }
        fun yearGap(it: JSONObject) =
            if (year <= 0) 0 else yearOf(it)?.let { y -> kotlin.math.abs(y - year) } ?: Int.MAX_VALUE
        val wanted = normalized(title)
        val exact = candidates.filter { wanted in names(it) }
        val chosen =
            exact.firstOrNull { yearGap(it) == 0 }
                ?: exact.firstOrNull { yearGap(it) == 1 }
                ?: exact.firstOrNull { yearOf(it) == null }
                ?: candidates.firstOrNull { yearGap(it) == 0 }
        return chosen?.text("bannerImage")?.ifBlank { null }
    }

    private fun normalized(text: String) = text.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    private fun shortTitle(title: String) =
        title.split(':', '~', '-', '(').first().trim(' ', '"').split(' ').take(5).joinToString(" ")
}
