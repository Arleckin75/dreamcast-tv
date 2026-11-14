package com.dreamcast.nativeapp.data

import com.dreamcast.nativeapp.NativePerformance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads data from a [DreamcastSource] through the response cache in [Storage].
 *
 * Raw responses are cached and re-parsed, a failed refresh falls back to the last good
 * response, and concurrent loads of the same key share one request.
 */
class Repository(val storage: Storage, val source: DreamcastSource = DreamcastSiteSource()) {
    constructor(storage: Storage, site: SiteClient) : this(storage, DreamcastSiteSource(site))

    private val parsedCache = ParsedCache()
    private val loads = SharedLoads()

    private data class LoadKey(val key: String, val context: Any?, val refresh: Boolean)

    /** Headers media requests for stream URLs must send. */
    val streamHeaders: Map<String, String>
        get() = source.streamHeaders

    /** Playable streams of [episode], preferred first. */
    fun streams(episode: Episode): List<VideoStream> = source.streams(episode)

    /** Reuse a recent signed playlist; renew the page and playlist together after two hours. */
    suspend fun playback(release: Release, refresh: Boolean = false): Pair<Release, List<Episode>> =
        loads.run(LoadKey("playback:${release.id}", release, refresh)) {
            loadPlayback(release, refresh)
        }

    private suspend fun loadPlayback(
        release: Release,
        refresh: Boolean,
    ): Pair<Release, List<Episode>> {
        val entry =
            withContext(Dispatchers.IO) { storage.cache("dreamcast:episodes:${release.id}") }
        val now = System.currentTimeMillis()
        val renew =
            refresh ||
                entry == null ||
                now - entry.updatedAt >= 2 * 3600000L ||
                (entry.expiresAt != null && now >= entry.expiresAt)
        val detail = detail(release, refresh = renew).value
        val playlist = episodes(detail, refresh = renew).value
        return detail.release.copy(
            currentEpisodes = playlist.size,
            totalEpisodes = maxOf(detail.release.totalEpisodes, playlist.size),
        ) to playlist
    }

    private suspend fun <T : Any> cached(
        key: String,
        ttl: Long,
        request: SourceRequest<T>,
        refresh: Boolean = false,
        parseContext: Any? = null,
        onStale: ((Cached<T>) -> Unit)? = null,
    ): Cached<T> =
        loads.run(LoadKey(key, parseContext, refresh)) {
            loadCached(key, ttl, refresh, parseContext, onStale, request)
        }

    private suspend fun <T : Any> loadCached(
        key: String,
        ttl: Long,
        refresh: Boolean,
        parseContext: Any?,
        onStale: ((Cached<T>) -> Unit)?,
        request: SourceRequest<T>,
    ): Cached<T> {
        val started = NativePerformance.now()
        val old =
            withContext(Dispatchers.IO) {
                NativePerformance.trace("DCTV.cache.read") { storage.cache(key) }
            }
        val parsed =
            withContext(Dispatchers.Default) {
                old?.let {
                    runCatching {
                            NativePerformance.trace("DCTV.cache.parse") {
                                parsedCache.parse(key, parseContext, it.value, request.parse)
                            }
                        }
                        .getOrNull()
                }
            }
        val now = System.currentTimeMillis()
        val fresh =
            old != null &&
                now - old.updatedAt < ttl &&
                (old.expiresAt == null || now < old.expiresAt)
        if (!refresh && fresh && parsed != null) {
            NativePerformance.event("repository.native.load", started, "cache")
            return Cached(parsed, false)
        }
        if (!refresh && parsed != null) onStale?.invoke(Cached(parsed, true))
        try {
            val text = request.fetch()
            val result =
                withContext(Dispatchers.Default) {
                    parsedCache.parse(key, parseContext, text, request.parse)
                }
            withContext(Dispatchers.IO) { storage.putCache(key, text) }
            NativePerformance.event("repository.native.load", started, "network")
            return Cached(result, false)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (parsed != null) {
                NativePerformance.event("repository.native.load", started, "stale_cache")
                return Cached(parsed, true)
            }
            throw error
        }
    }

    suspend fun releases(
        query: String = "",
        filter: CatalogFilter = CatalogFilter(),
        page: Int = 1,
        refresh: Boolean = false,
        onStale: ((ReleasePage) -> Unit)? = null,
    ): ReleasePage {
        val normalized = query.trim().lowercase()
        val key =
            when {
                normalized.isNotEmpty() ->
                    "dreamcast:releases:search:$normalized:$page:16" +
                        if (filter == CatalogFilter()) "" else ":${filter.cacheKey}"
                filter.year != null || filter.genres.isNotEmpty() ->
                    "dreamcast:catalog:${filter.cacheKey}:$page:16"
                else -> "dreamcast:releases:ongoing:$page:16"
            }
        val data =
            cached(
                key,
                20 * 60 * 1000L,
                source.releases(query.trim(), filter, page),
                refresh,
                onStale = { onStale?.invoke(it.value.copy(stale = true)) },
            )
        return data.value.copy(stale = data.stale)
    }

    suspend fun options(
        onStale: ((Cached<CatalogOptions>) -> Unit)? = null
    ): Cached<CatalogOptions> =
        cached("dreamcast:catalog:options", 86400000L, source.catalogOptions(), onStale = onStale)

    /**
     * Number of catalog releases per genre id. The site has no such list, so this asks the
     * catalog once per genre, one request at a time with a pause between them, and keeps the
     * result for a day. A failed refresh keeps the previous counts.
     */
    suspend fun genreCounts(genres: List<Genre>): Cached<Map<String, Int>> =
        cached(
            "dreamcast:catalog:genre-counts",
            GENRE_COUNTS_TTL_MS,
            SourceRequest(
                fetch = {
                    val counts = org.json.JSONObject()
                    for ((index, genre) in genres.withIndex()) {
                        if (index > 0) kotlinx.coroutines.delay(GENRE_COUNT_PAUSE_MS)
                        val request = source.releases("", CatalogFilter(genres = setOf(genre.id)), 1)
                        counts.put(genre.id, request.parse(request.fetch()).total)
                    }
                    counts.toString()
                },
                parse = { text ->
                    val json = org.json.JSONObject(text)
                    json.keys().asSequence().associateWith { json.getInt(it) }
                },
            ),
        )

    suspend fun detail(
        release: Release,
        refresh: Boolean = false,
        onStale: ((Cached<Detail>) -> Unit)? = null,
    ): Cached<Detail> =
        cached(
            "dreamcast:detail:${release.id}",
            8 * 3600000L,
            source.detail(release),
            refresh,
            parseContext = release,
            onStale = onStale,
        )

    suspend fun episodes(detail: Detail, refresh: Boolean = false): Cached<List<Episode>> {
        val scripts = TextCache { key, fetch ->
            cached(key, 8 * 3600000L, SourceRequest(fetch) { it }, refresh).value
        }
        return cached(
            "dreamcast:episodes:${detail.release.id}",
            8 * 3600000L,
            source.episodes(detail, scripts),
            refresh,
        )
    }

    suspend fun schedule(
        onStale: ((Cached<List<ScheduleDay>>) -> Unit)? = null
    ): Cached<List<ScheduleDay>> =
        cached("dreamcast:schedule", 30 * 60000L, source.schedule(), onStale = onStale)

    private companion object {
        const val GENRE_COUNTS_TTL_MS = 24 * 3600 * 1000L
        /** Spreads the per-genre requests out so the site sees no burst. */
        const val GENRE_COUNT_PAUSE_MS = 250L
    }
}
