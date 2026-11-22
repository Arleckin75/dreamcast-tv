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

    override fun detail(release: Release): SourceRequest<Detail> {
        // The address found through the catalog, kept for the page when it has no canonical link.
        var found: String? = null
        return SourceRequest(
            fetch = {
                try {
                    client.html(release.url)
                } catch (missing: SiteClient.HttpFailure) {
                    // The site serves a release only at its full address with the title slug:
                    // watch history saved before 1.0.4 has the bare id, and a renamed release has
                    // a stale slug. Find the current address through the catalog search; if the
                    // search itself fails, the 404 is the error to show.
                    if (missing.status != 404) throw missing
                    val address = runCatching { findAddress(release) }.getOrNull() ?: throw missing
                    client.html(address).also { found = address }
                }
            },
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
                } else
                    SiteParser.detail(
                        it,
                        // Only an address on the site itself is kept, as SiteClient fetched it.
                        found
                            ?.let { address -> absoluteUrl(address)?.takeIf { it.startsWith("$BASE_URL/") } }
                            ?.let { release.copy(url = it) } ?: release,
                    )
            },
        )
    }

    /** The current page address of [release], found by its title in the catalog search. */
    private suspend fun findAddress(release: Release): String? {
        for (query in listOf(release.title, release.originalTitle).filter { it.isNotBlank() }) {
            // A common title matches more releases than one page holds: look a few pages deep.
            for (page in 1..SEARCH_PAGES) {
                val json = JSONObject(client.releases(query, page = page))
                val releases = json.getJSONArray("releases").objects()
                // The catalog gives the address relative to the site, as SiteClient expects it.
                val address = releases.firstOrNull { it.optInt("id") == release.id }?.text("url").orEmpty()
                if (address.isNotBlank()) return address
                if (releases.size < SEARCH_PAGE_SIZE || page * SEARCH_PAGE_SIZE >= json.optInt("count")) break
            }
        }
        return null
    }

    private companion object {
        /** How many catalog search pages are checked for a stale address. */
        const val SEARCH_PAGES = 3
        /** Releases on one catalog page, as [SiteClient.releases] asks for them. */
        const val SEARCH_PAGE_SIZE = 16
    }

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
