package com.dreamcast.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Debug-only. Resolve an AV1 480p fMP4 HLS media playlist, never print stream URLs. */
object Cosmos480pResolver {
    private const val TAG = "DCTV_480P"
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dctv-480p-resolver").apply { isDaemon = true }
    }
    private val ui = Handler(Looper.getMainLooper())
    private val resolution = Regex("(?:^|,)RESOLUTION=854x480(?:,|$)")
    private val codecs = Regex("CODECS=\"([^\"]+)\"")

    fun resolve(masterUrl: String, agent: String?, referer: String?, result: (String?) -> Unit) {
        executor.execute {
            val selected = try { find480p(masterUrl, agent, referer) }
            catch (e: Exception) {
                Log.e(TAG, "resolution failed kind=${e.javaClass.simpleName}")
                null
            }
            ui.post { result(selected) }
        }
    }

    private fun fetch(url: String, agent: String?, referer: String?): String {
        require(Uri.parse(url).scheme in listOf("https", "http"))
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 8000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL, */*")
            if (!agent.isNullOrBlank()) setRequestProperty("User-Agent", agent)
            if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
        }
        try {
            Log.i(TAG, "playlist httpStatus=${conn.responseCode} mime=${conn.contentType?.substringBefore(';')?.take(60)}")
            require(conn.responseCode in 200..299) { "playlist HTTP failure" }
            val output = ByteArrayOutputStream()
            conn.inputStream.use { stream ->
                val buf = ByteArray(8192)
                while (output.size() < 256 * 1024) {
                    val count = stream.read(buf, 0, minOf(buf.size, 256 * 1024 - output.size()))
                    if (count < 0) break
                    output.write(buf, 0, count)
                }
            }
            val bytes = output.toByteArray()
            val text = bytes.toString(Charsets.UTF_8)
            require(text.trimStart().startsWith("#EXTM3U")) { "not HLS" }
            return text
        } finally { conn.disconnect() }
    }

    private fun find480p(url: String, agent: String?, referer: String?): String? {
        val lines = fetch(url, agent, referer).lines().map { it.trim() }
        if (lines.none { it.startsWith("#EXT-X-STREAM-INF:") }) {
            Log.w(TAG, "source is not master playlist; cannot select 480p")
            return null
        }
        val candidates = lines.mapIndexedNotNull { i, s ->
            if (!s.startsWith("#EXT-X-STREAM-INF:") || !resolution.containsMatchIn(s)) return@mapIndexedNotNull null
            val codec = codecs.find(s)?.groupValues?.getOrNull(1).orEmpty()
            if (!codec.split(',').any { it.trim().startsWith("av01") }) return@mapIndexedNotNull null
            val relative = lines.getOrNull(i + 1).orEmpty()
            if (relative.isBlank() || relative.startsWith('#')) return@mapIndexedNotNull null
            URL(URL(url), relative).toString()
        }
        Log.i(TAG, "av1_480p_candidates=${candidates.size}")
        for (candidate in candidates) {
            val media = fetch(candidate, agent, referer).lines().map { it.trim() }
            val segments = media.filter { it.isNotBlank() && !it.startsWith('#') }
            val map = media.any { it.startsWith("#EXT-X-MAP:") }
            val extensions = segments.map { Uri.parse(it).path.orEmpty().substringAfterLast('.', "none").lowercase() }.distinct()
            val fmp4 = map && segments.isNotEmpty() && segments.all {
                Uri.parse(it).path.orEmpty().lowercase().endsWith(".m4s") ||
                Uri.parse(it).path.orEmpty().lowercase().endsWith(".mp4")
            }
            Log.i(TAG, "candidate_480p mediaSegments=${segments.size} initMap=$map extensions=$extensions fmp4=$fmp4")
            if (fmp4) {
                Log.i(TAG, "selected AV1 480p fMP4 direct media playlist (URL redacted)")
                return candidate
            }
        }
        Log.w(TAG, "no AV1 480p fMP4 variant found; refusing master fallback")
        return null
    }
}
