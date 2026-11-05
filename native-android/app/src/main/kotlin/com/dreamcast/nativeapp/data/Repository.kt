package com.dreamcast.nativeapp.data

import com.dreamcast.nativeapp.NativePerformance
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

class SiteClient(
    val baseUrl: String = BASE_URL,
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build(),
) {
    private class HttpFailure(val status: Int) : IOException("Ошибка сервера: $status")

    val headers =
        mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36",
            "Referer" to "$BASE_URL/",
            "Origin" to BASE_URL,
            "x-requested-with" to "XMLHttpRequest",
            "Accept-Language" to "ru-RU,ru;q=0.9",
        )

    private suspend fun execute(request: Request): String =
        withTimeoutOrNull(20_000) { executeAttempts(request) }
            ?: throw IOException("Превышено время ожидания сервера")

    private suspend fun executeAttempts(request: Request): String {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return suspendCancellableCoroutine { continuation ->
                    val call = client.newCall(request)
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(
                        object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                if (continuation.isActive) continuation.resumeWithException(e)
                            }

                            override fun onResponse(call: Call, response: Response) {
                                try {
                                    response.use {
                                        if (!continuation.isActive) return
                                        if (!it.isSuccessful)
                                            continuation.resumeWithException(HttpFailure(it.code))
                                        else continuation.resume(it.body?.string() ?: "")
                                    }
                                } catch (error: IOException) {
                                    if (continuation.isActive)
                                        continuation.resumeWithException(error)
                                }
                            }
                        }
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: IOException) {
                if (error is HttpFailure && error.status !in setOf(408, 429, 500, 502, 503, 504))
                    throw error
                last = error
                if (attempt < 2) delay(350L shl attempt)
            }
        }
        throw last ?: IOException("Сеть недоступна")
    }

    private fun request(url: String) =
        Request.Builder()
            .url(if (url.startsWith("http")) url else baseUrl.trimEnd('/') + url)
            .apply { headers.forEach { (key, value) -> header(key, value) } }

    suspend fun html(url: String) = execute(request(url).get().build())

    suspend fun releases(
        query: String = "",
        filter: CatalogFilter = CatalogFilter(),
        page: Int = 1,
    ): String {
        val form =
            FormBody.Builder()
                .add("search", query)
                .add("status", "")
                .add("pageNumber", page.toString())
                .add("pageSize", "16")
        filter.year?.let { form.add("dateissue[]", it.toString()) }
        filter.genres.sorted().forEach { form.add("genres[]", it) }
        return execute(request("/").post(form.build()).build())
    }
}

class Repository(val storage: Storage, val site: SiteClient = SiteClient()) {
    private val parsedCache = ParsedCache()
    private val loads = SharedLoads()

    private data class LoadKey(val key: String, val context: Any?, val refresh: Boolean)

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
        refresh: Boolean = false,
        parseContext: Any? = null,
        onStale: ((Cached<T>) -> Unit)? = null,
        parse: (String) -> T,
        fetch: suspend () -> String,
    ): Cached<T> =
        loads.run(LoadKey(key, parseContext, refresh)) {
            loadCached(key, ttl, refresh, parseContext, onStale, parse, fetch)
        }

    private suspend fun <T : Any> loadCached(
        key: String,
        ttl: Long,
        refresh: Boolean,
        parseContext: Any?,
        onStale: ((Cached<T>) -> Unit)?,
        parse: (String) -> T,
        fetch: suspend () -> String,
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
                                parsedCache.parse(key, parseContext, it.value, parse)
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
            val text = fetch()
            val result =
                withContext(Dispatchers.Default) {
                    parsedCache.parse(key, parseContext, text, parse)
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
                refresh,
                onStale = { onStale?.invoke(it.value.copy(stale = true)) },
                parse = { text ->
                    val json = JSONObject(text)
                    ReleasePage(
                        json.getJSONArray("releases").objects().map(Release::fromJson),
                        json.getInt("count"),
                        page,
                    )
                },
                fetch = { site.releases(query.trim(), filter, page) },
            )
        return data.value.copy(stale = data.stale)
    }

    suspend fun options(
        onStale: ((Cached<CatalogOptions>) -> Unit)? = null
    ): Cached<CatalogOptions> =
        cached(
            "dreamcast:catalog:options",
            86400000L,
            onStale = onStale,
            parse = {
                SiteParser.options(
                    if (it.trimStart().startsWith("{")) JSONObject(it).getString("html") else it
                )
            },
            fetch = { site.html("/") },
        )

    suspend fun detail(
        release: Release,
        refresh: Boolean = false,
        onStale: ((Cached<Detail>) -> Unit)? = null,
    ): Cached<Detail> =
        cached(
            "dreamcast:detail:${release.id}",
            8 * 3600000L,
            refresh,
            parseContext = release,
            onStale = onStale,
            parse = {
                if (it.trimStart().startsWith("{")) {
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
            fetch = { site.html(release.url) },
        )

    suspend fun episodes(detail: Detail, refresh: Boolean = false): Cached<List<Episode>> {
        val decoded =
            cached(
                "dreamcast:episodes:${detail.release.id}",
                8 * 3600000L,
                refresh,
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
                            )
                        }
                    else PlayerJs.episodes(json, detail.release.id)
                },
                fetch = {
                    val direct =
                        if (detail.payload.startsWith("#2"))
                            runCatching { PlayerJs.decode("", detail.payload) }.getOrNull()
                        else null
                    if (direct != null) return@cached direct.toString()
                    val script =
                        cached(
                                "native:script:${detail.scriptUrl}",
                                8 * 3600000L,
                                refresh = refresh,
                                parse = { it },
                                fetch = { site.html(detail.scriptUrl) },
                            )
                            .value
                    withContext(Dispatchers.Default) {
                        PlayerJs.decode(script, detail.payload).toString()
                    }
                },
            )
        return decoded
    }

    suspend fun schedule(
        onStale: ((Cached<List<ScheduleDay>>) -> Unit)? = null
    ): Cached<List<ScheduleDay>> =
        cached(
            "dreamcast:schedule",
            30 * 60000L,
            onStale = onStale,
            parse = {
                if (it.trimStart().startsWith("{"))
                    JSONObject(it).getJSONArray("days").objects().map { day ->
                        ScheduleDay(
                            day.getString("title"),
                            day.getJSONArray("releases").objects().map(Release::fromJson),
                        )
                    }
                else SiteParser.schedule(it)
            },
            fetch = { site.html("/home/schedule") },
        )
}
