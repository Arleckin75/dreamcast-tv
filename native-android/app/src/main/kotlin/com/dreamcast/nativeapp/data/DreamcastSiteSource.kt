package com.dreamcast.nativeapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [DreamcastSource] backed by the dreamerscast.com website: HTML pages parsed by [SiteParser]
 * and PlayerJS playlists decoded by [PlayerJs].
 *
 * Each parser also accepts a JSON form of the same response (`{"html": …}`, `{"playerPayload":
 * …}`, `{"episodes": …}`, `{"days": …}`), so a proxy returning prepared JSON works without
 * code changes.
 */
class DreamcastSiteSource(val client: SiteClient = SiteClient()) : DreamcastSource {
    override val streamHeaders: Map<String, String>
        get() = client.headers

    override fun releases(query: String, filter: CatalogFilter, page: Int) =
        SourceRequest(
            fetch = { client.releases(query, filter, page) },
            parse = { text ->
                val json = JSONObject(text)
                ReleasePage(
                    json.getJSONArray("releases").objects().map(Release::fromJson),
                    json.getInt("count"),
                    page,
                )
            },
        )

    override fun catalogOptions() =
        SourceRequest(
            fetch = { client.html("/") },
            parse = {
                SiteParser.options(
                    if (it.isJson()) JSONObject(it).getString("html") else it
                )
            },
        )

    override fun detail(release: Release) =
        SourceRequest(
            fetch = { client.html(release.url) },
            parse = {
                if (it.isJson()) {
                    val json = JSONObject(it)
                    Detail(
                        json.optJSONObject("release")?.let(Release::fromJson)
                            ?: release.copy(
                                title = json.text("title", release.title),
                                description = json.text("description"),
                                posterUrl = json.text("thumbnailUrl", release.posterUrl),
                            ),
                        json.getString("playerPayload"),
                        json.getString("playerScriptUrl"),
                    )
                } else SiteParser.detail(it, release)
            },
        )

    override fun episodes(detail: Detail, cache: TextCache) =
        SourceRequest(
            fetch = fetch@{
                val direct =
                    if (detail.payload.startsWith("#2"))
                        runCatching { PlayerJs.decode("", detail.payload) }.getOrNull()
                    else null
                if (direct != null) return@fetch direct.toString()
                val script =
                    cache.text("native:script:${detail.scriptUrl}") {
                        client.html(detail.scriptUrl)
                    }
                withContext(Dispatchers.Default) {
                    PlayerJs.decode(script, detail.payload).toString()
                }
            },
            parse = {
                val json = JSONObject(it)
                if (json.has("episodes"))
                    json.getJSONArray("episodes").objects().map { episode ->
                        Episode(
                            episode.getString("id"),
                            detail.release.id,
                            episode.getInt("ordinal"),
                            episode.getString("title"),
                            episode.getString("file"),
                            PlayerJs.thumbnailsUrl(episode.text("thumbnails")),
                        )
                    }
                else PlayerJs.episodes(json, detail.release.id)
            },
        )

    override fun schedule() =
        SourceRequest(
            fetch = { client.html("/home/schedule") },
            parse = {
                if (it.isJson())
                    JSONObject(it).getJSONArray("days").objects().map { day ->
                        ScheduleDay(
                            day.getString("title"),
                            day.getJSONArray("releases").objects().map(Release::fromJson),
                        )
                    }
                else SiteParser.schedule(it)
            },
        )

    override fun streams(episode: Episode): List<VideoStream> = PlayerJs.streams(episode)

    private fun String.isJson() = trimStart().startsWith("{")
}
