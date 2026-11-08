package com.dreamcast.nativeapp.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** HTTP client for dreamerscast.com with the browser headers and retries the site needs. */
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
