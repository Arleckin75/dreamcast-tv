package com.dreamcast.nativeapp.data

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * HTTP client for dreamerscast.com with the browser headers and retries the site needs.
 *
 * Requests go only to the host of [baseUrl]: an address from anywhere else (a release opened
 * from another app's intent, say) is refused before anything is sent.
 *
 * Each of the [ATTEMPTS] attempts has its own time budget, set on [client]; [totalTimeoutMs]
 * covers all of them and the pauses in between.
 */
class SiteClient(
    val baseUrl: String = BASE_URL,
    private val client: OkHttpClient =
        SharedHttp.client(
            connect = CONNECT_TIMEOUT_S,
            read = READ_TIMEOUT_S,
            call = ATTEMPT_TIMEOUT_S,
        ),
    private val totalTimeoutMs: Long = TOTAL_TIMEOUT_MS,
) {
    private val site =
        requireNotNull(baseUrl.toHttpUrlOrNull()) { "Недопустимый адрес сайта: $baseUrl" }

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
        withTimeoutOrNull(totalTimeoutMs) { executeAttempts(request) }
            ?: throw IOException("Превышено время ожидания сервера")

    private suspend fun executeAttempts(request: Request): String {
        var last: Exception? = null
        repeat(ATTEMPTS) { attempt ->
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
                if (attempt < ATTEMPTS - 1) delay(RETRY_PAUSE_MS shl attempt)
            }
        }
        throw last ?: IOException("Сеть недоступна")
    }

    private fun request(url: String): Request.Builder {
        val target =
            (if (url.startsWith("http")) url else baseUrl.trimEnd('/') + url).toHttpUrlOrNull()
        if (
            target == null ||
                target.scheme != site.scheme ||
                target.host != site.host ||
                target.port != site.port
        )
            throw IOException("Недопустимый адрес: $url")
        return Request.Builder().url(target).apply {
            headers.forEach { (key, value) -> header(key, value) }
        }
    }

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

    private companion object {
        const val ATTEMPTS = 3
        const val CONNECT_TIMEOUT_S = 5L
        const val READ_TIMEOUT_S = 10L

        /** One attempt, from connecting to the last byte of the response. */
        const val ATTEMPT_TIMEOUT_S = 12L

        /** Pause before the second attempt; doubled before the third. */
        const val RETRY_PAUSE_MS = 350L

        /** Three full attempts and both pauses fit: 3 x 12 s + 0.35 s + 0.7 s. */
        const val TOTAL_TIMEOUT_MS = 40_000L
    }
}
