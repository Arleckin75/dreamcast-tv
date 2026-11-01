package com.dreamcast.app

import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Read-only, debug-only HLS inspection. Never logs URLs, paths, query strings,
 * cookies, authorization or raw playlist lines. Does not download media segments. */
object CosmosHlsDiagnostic {
    private const val TAG = "DCTV_HLS"
    private const val LIMIT = 256 * 1024
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dctv-hls-probe").apply { isDaemon = true }
    }

    fun inspect(url: String, userAgent: String?, referer: String?) {
        executor.execute {
            try {
                inspectOne(url, userAgent, referer, 0, HashSet())
            } catch (e: Exception) {
                Log.w(TAG, "probe failed reason=${e.javaClass.simpleName}")
            }
        }
    }

    private fun inspectOne(
        url: String, userAgent: String?, referer: String?, depth: Int, visited: MutableSet<String>
    ) {
        if (depth > 2 || visited.size >= 5 || !visited.add(url)) return
        val parsed = Uri.parse(url)
        if (parsed.scheme != "https" && parsed.scheme != "http") return
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 6000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL, */*")
            if (!userAgent.isNullOrEmpty()) setRequestProperty("User-Agent", userAgent)
            if (!referer.isNullOrEmpty()) setRequestProperty("Referer", referer)
        }
        try {
            val status = connection.responseCode
            val mime = connection.contentType?.substringBefore(';') ?: "unknown"
            Log.i(TAG, "playlist depth=$depth status=$status contentType=$mime")
            if (status !in 200..299) return
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (bytes.size() < LIMIT) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, LIMIT - bytes.size()))
                    if (count < 0) break
                    bytes.write(buffer, 0, count)
                }
            }
            val content = bytes.toString("UTF-8")
            if (!content.trimStart().startsWith("#EXTM3U")) {
                Log.i(TAG, "notHls bytesRead=${bytes.size()} signature=${classify(bytes.toByteArray())}")
                return
            }
            val lines = content.lineSequence().map { it.trim() }.toList()
            val master = lines.any { it.startsWith("#EXT-X-STREAM-INF:") }
            val segments = lines.filter { it.isNotEmpty() && !it.startsWith('#') }
            val maps = lines.count { it.startsWith("#EXT-X-MAP:") }
            val keys = lines.filter { it.startsWith("#EXT-X-KEY:") }
            val parts = lines.count { it.startsWith("#EXT-X-PART:") }
            val variantAttrs = lines.filter { it.startsWith("#EXT-X-STREAM-INF:") }
            val mediaAttrs = lines.filter { it.startsWith("#EXT-X-MEDIA:") }
            val codecs = Regex("CODECS=\\\"([^\\\"]+)\\\"")
            Log.i(TAG, "hls depth=$depth kind=${if (master) "master" else "media"} " +
                "variants=${variantAttrs.size} segmentRefs=${segments.size} mapTags=$maps " +
                "keyTags=${keys.size} partTags=$parts mediaTags=${mediaAttrs.size} " +
                "endlist=${lines.any { it == "#EXT-X-ENDLIST" }}")
            variantAttrs.take(8).forEachIndexed { index, attrs ->
                val cs = codecs.find(attrs)?.groupValues?.getOrNull(1) ?: "unspecified"
                val resolution = Regex("RESOLUTION=([0-9]+x[0-9]+)").find(attrs)?.groupValues?.get(1) ?: "unspecified"
                val bandwidth = Regex("BANDWIDTH=([0-9]+)").find(attrs)?.groupValues?.get(1) ?: "unspecified"
                Log.i(TAG, "variant index=$index codecs=$cs resolution=$resolution bandwidth=$bandwidth")
            }
            if (!master) {
                val formats = segments.groupingBy { extension(it) }.eachCount()
                Log.i(TAG, "segmentExtensions=$formats initMap=${maps > 0} encrypted=${keys.isNotEmpty()} " +
                    "targetDuration=${readNumber(lines, "#EXT-X-TARGETDURATION:")} " +
                    "version=${readNumber(lines, "#EXT-X-VERSION:")}")
            }
            if (master) {
                val variantLinks = ArrayList<String>()
                lines.forEachIndexed { index, line ->
                    if (line.startsWith("#EXT-X-STREAM-INF:") && index + 1 < lines.size) {
                        val relative = lines[index + 1]
                        if (!relative.startsWith('#') && relative.isNotBlank()) variantLinks.add(relative)
                    }
                }
                // Inspect smallest advertised bandwidth, plus one other variant.
                val choices = variantAttrs.mapIndexedNotNull { index, attrs ->
                    val link = variantLinks.getOrNull(index) ?: return@mapIndexedNotNull null
                    val bandwidth = Regex("BANDWIDTH=([0-9]+)").find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE
                    link to bandwidth
                }.sortedBy { it.second }.take(2)
                choices.forEach { (relative, _) ->
                    val resolved = URL(URL(url), relative).toString()
                    inspectOne(resolved, userAgent, referer, depth + 1, visited)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "playlist depth=$depth exception=${e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    private fun readNumber(lines: List<String>, prefix: String): String =
        lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.matches(Regex("[0-9]+")) } ?: "unknown"

    private fun extension(link: String): String {
        val path = Uri.parse(link).path.orEmpty().lowercase()
        return listOf(".m4s", ".mp4", ".ts", ".aac", ".webm", ".vtt").firstOrNull { path.endsWith(it) } ?: "other"
    }

    private fun classify(bytes: ByteArray): String = when {
        bytes.size >= 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp" -> "mp4"
        bytes.isNotEmpty() && bytes[0] == 0x47.toByte() -> "mpegts-candidate"
        else -> "unknown"
    }
}
