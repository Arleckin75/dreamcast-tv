package com.dreamcast.nativeapp.data

/**
 * Where catalog, release and playback data comes from.
 *
 * [Repository] owns caching, stale fallbacks and request sharing; a source only knows how to
 * fetch a raw response and how to parse it. Raw text, not parsed objects, is what the repository
 * caches, so a cached response is re-parsed on the next start and a parse failure falls back to
 * the previous response. To move to an official API or a proxy, add another implementation and
 * pass it to [Repository]; the UI does not depend on the source.
 */
interface DreamcastSource {
    /** Headers media requests for stream URLs from this source must send. */
    val streamHeaders: Map<String, String>

    fun releases(query: String, filter: CatalogFilter, page: Int): SourceRequest<ReleasePage>

    fun catalogOptions(): SourceRequest<CatalogOptions>

    /** [release] is the list item being opened; its known fields fill gaps in the page. */
    fun detail(release: Release): SourceRequest<Detail>

    /** [cache] keeps auxiliary downloads, such as the player script, between calls. */
    fun episodes(detail: Detail, cache: TextCache): SourceRequest<List<Episode>>

    fun schedule(): SourceRequest<List<ScheduleDay>>

    /** Playable streams of [episode], preferred first. */
    fun streams(episode: Episode): List<VideoStream>
}

/** One source call: [fetch] downloads the raw response, [parse] turns it into a model. */
class SourceRequest<T : Any>(val fetch: suspend () -> String, val parse: (String) -> T)

/** Text cache the repository offers sources for auxiliary downloads. */
fun interface TextCache {
    suspend fun text(key: String, fetch: suspend () -> String): String
}
