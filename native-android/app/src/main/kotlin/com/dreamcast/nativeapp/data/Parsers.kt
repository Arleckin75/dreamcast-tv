package com.dreamcast.nativeapp.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

object SiteParser {
    fun options(source: String): CatalogOptions {
        val doc = Jsoup.parse(source)
        val years =
            doc.select("#dateissue option")
                .mapNotNull { it.attr("value").toIntOrNull() }
                .distinct()
                .sortedDescending()
        val genres =
            doc.select("#genres option")
                .filter { it.attr("value").isNotBlank() && it.text().isNotBlank() }
                .map { Genre(it.attr("value"), it.text().trim()) }
                .sortedBy { it.name }
        require(years.isNotEmpty() && genres.isNotEmpty()) {
            "На сайте не найдены фильтры каталога"
        }
        return CatalogOptions(years, genres)
    }

    fun detail(source: String, seed: Release): Detail {
        val doc = Jsoup.parse(source)
        fun meta(key: String) =
            doc.selectFirst("meta[property='$key'],meta[name='$key']")?.attr("content").orEmpty()
        val title =
            doc.selectFirst("h3")?.text()?.takeIf { it.isNotBlank() }
                ?: meta("og:title").ifBlank { doc.title() }
        val payload =
            Regex("""(?:new\s+)?Playerjs\s*\(\s*["']([^"']+)["']\s*\)""")
                .find(source)
                ?.groupValues
                ?.get(1) ?: error("Не найден PlayerJS payload")
        val script =
            doc.select("script[src]")
                .firstOrNull { it.attr("src").contains("/js/playerjs") }
                ?.attr("src") ?: error("Не найден скрипт PlayerJS")
        val release =
            seed.copy(
                title = seed.title.ifBlank { title },
                description =
                    seed.description.ifBlank {
                        doc.selectFirst(".postDesc")?.text()
                            ?: meta("description").ifBlank { meta("og:description") }
                    },
                posterUrl =
                    seed.posterUrl.ifBlank {
                        imageUrl(
                            doc.selectFirst(".details_poster img")?.attr("src") ?: meta("og:image")
                        ) ?: ""
                    },
            )
        return Detail(release, payload, absoluteUrl(script)!!)
    }

    fun schedule(source: String): List<ScheduleDay> {
        val doc = Jsoup.parse(source)
        val container = doc.selectFirst("#Content") ?: doc.body()
        val markers = container.select(".timetable").ifEmpty { container.select("h4") }
        val days =
            markers.mapNotNull { marker ->
                val title =
                    (if (marker.tagName() == "h4") marker.text()
                        else marker.selectFirst("h4")?.text())
                        .orEmpty()
                if (title.isBlank()) return@mapNotNull null
                var sibling = marker.nextElementSibling()
                var row: Element? = null
                while (sibling != null) {
                    if (sibling.hasClass("timetable") || sibling.selectFirst("h4") != null) break
                    if (sibling.hasClass("row") && sibling.selectFirst(".poster") != null) {
                        row = sibling
                        break
                    }
                    sibling = sibling.nextElementSibling()
                }
                ScheduleDay(
                    title,
                    // A malformed card is skipped instead of failing the whole schedule.
                    row?.select(".poster")?.mapNotNull { runCatching { scheduleCard(it) }.getOrNull() }
                        ?: emptyList(),
                )
            }
        require(days.any { it.releases.isNotEmpty() }) { "Расписание не найдено" }
        return days
    }

    private fun scheduleCard(card: Element): Release? {
        val href = card.selectFirst("a[href*='/home/release/']")?.attr("href") ?: return null
        val id =
            Regex("/release/(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val metadata =
            card.select(".poster_info").associate {
                it.selectFirst(".s-item")?.text().orEmpty().replace(":", "").trim() to
                    it.selectFirst("span")?.text().orEmpty()
            }
        val episodes = metadata["Эпизодов"].orEmpty()
        val poster =
            Regex("""url\(['"]?([^'")]+)""")
                .find(card.selectFirst(".poster-img")?.attr("style").orEmpty())
                ?.groupValues
                ?.get(1)
        return Release(
            id,
            card.selectFirst(".s-title-russian")?.text().orEmpty().ifBlank {
                card.selectFirst(".s-title-original")?.text().orEmpty()
            },
            card.selectFirst(".s-title-original")?.text().orEmpty(),
            absoluteUrl(href)!!,
            imageUrl(poster).orEmpty(),
            year = metadata["Год"]?.toIntOrNull() ?: 0,
            genres = metadata["Жанр"].orEmpty(),
            type = metadata["Тип"].orEmpty(),
            season = metadata["Сезон"].orEmpty(),
            studio = metadata["Студия"].orEmpty(),
            currentEpisodes =
                Regex("^\\s*(\\d+)").find(episodes)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            totalEpisodes =
                Regex("из\\s+(\\d+)").find(episodes)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        )
    }
}

/** Pure Kotlin decoder: never executes scripts returned by the site. */
object PlayerJs {
    private const val alphabet = "ABCDEFGHIJKLMabcdefghijklmNOPQRSTUVWXYZnopqrstuvwxyz"
    private const val saltAlphabet = alphabet + "0123456789+/="
    private const val standard = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="

    private fun uriDecode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

    private fun uriEncode(value: String) =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")

    fun crypto(value: String): String {
        if (!value.startsWith("#0") && !value.startsWith("#1")) return value
        var source = value.drop(2)
        if (value.startsWith("#1")) {
            val sugar =
                "xx???x=xx?x??="
                    .split('=')
                    .joinToString("") { part ->
                        (part.map { if (it == 'x') '1' else '0' }.joinToString("").toIntOrNull(2)
                                ?: 0)
                            .toChar()
                            .toString()
                    }
                    .dropLast(1)
                    .toInt()
            val shift = alphabet.length - sugar * 2
            val rotated = alphabet.drop(shift) + alphabet.take(shift)
            source =
                source
                    .replace('#', '+')
                    .map { char -> if (char in alphabet) rotated[alphabet.indexOf(char)] else char }
                    .joinToString("")
        }
        val translated =
            source
                .filter { it in saltAlphabet }
                .map { standard[saltAlphabet.indexOf(it)] }
                .joinToString("")
        return String(Base64.getDecoder().decode(translated), Charsets.UTF_8)
    }

    private fun quoted(source: String, offset: Int): Pair<String, Int> {
        require(offset >= 0 && source[offset] == '\'') { "Некорректная строка PlayerJS" }
        val result = StringBuilder()
        var index = offset + 1
        while (index < source.length) {
            val char = source[index++]
            if (char == '\\' && index < source.length) {
                result.append(char).append(source[index++])
                continue
            }
            if (char == '\'')
                return result
                    .toString()
                    .replace("\\'", "'")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\") to index
            result.append(char)
        }
        error("Не найдена закрывающая кавычка PlayerJS")
    }

    private fun Char.isPackerWord() =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_'

    fun unpack(source: String): String {
        val marker = source.indexOf("return p}(")
        if (marker < 0) return source
        val (payload, end) = quoted(source, source.indexOf('\'', marker))
        val args =
            Regex("""\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*""").find(source, end)
                ?: error("Некорректные параметры PlayerJS")
        val radix = args.groupValues[1].toInt()
        val count = args.groupValues[2].toInt()
        require(radix in 2..62 && count <= 100000) { "Некорректный словарь PlayerJS" }
        val (symbols) = quoted(source, source.indexOf('\'', args.range.last + 1))
        val dictionary = symbols.split('|')
        fun encode(number: Int): String {
            val rem = number % radix
            return (if (number < radix) "" else encode(number / radix)) +
                (if (rem > 35) (rem + 29).toChar() else "0123456789abcdefghijklmnopqrstuvwxyz"[rem])
        }
        val substitutions =
            (0 until count).associate {
                encode(it) to (dictionary.getOrNull(it)?.takeIf(String::isNotEmpty) ?: encode(it))
            }
        // JavaScript packers use ASCII word tokens. Avoid ICU word boundaries on
        // Android: scanning a large player script with \b allocates excessively.
        val result = StringBuilder(payload.length)
        var offset = 0
        while (offset < payload.length) {
            val start = offset
            if (payload[offset].isPackerWord()) {
                while (offset < payload.length && payload[offset].isPackerWord()) offset++
                val token = payload.substring(start, offset)
                result.append(substitutions[token] ?: token)
            } else {
                while (offset < payload.length && !payload[offset].isPackerWord()) offset++
                result.append(payload, start, offset)
            }
        }
        return result.toString()
    }

    fun decode(script: String, encoded: String): JSONObject {
        fun candidates(source: String): JSONObject? {
            val inputs =
                listOf(source) +
                    Regex("=+")
                        .findAll(source)
                        .map { source.take(it.range.last + 1) }
                        .filter { it.length >= 8 }
                        .toList()
            for (candidate in inputs.distinct()) {
                val result =
                    runCatching {
                            val normalized = candidate.replace('-', '+').replace('_', '/')
                            val text =
                                String(Base64.getDecoder().decode(normalized), Charsets.UTF_8)
                            val json =
                                JSONObject(
                                    if (text.trimStart().startsWith("{")) text else uriDecode(text)
                                )
                            require(episodes(json, 1).isNotEmpty())
                            json
                        }
                        .getOrNull()
                if (result != null) return result
            }
            return null
        }
        val raw = if (encoded.startsWith("#")) encoded.drop(2) else encoded
        if (encoded.startsWith("#2"))
            candidates(raw)?.let {
                return it
            }
        val unpacked = unpack(script)
        val code =
            Regex("""(?:["']u["']|(?<![A-Za-z0-9_])u)\s*:\s*\\?\s*["']([^"']+)["']""")
                .find(unpacked)
                ?.groupValues
                ?.get(1) ?: error("Не найдены ключи декодирования PlayerJS")
        val keys = JSONObject(crypto(code.trimEnd('\\')))
        var clean = raw
        for (i in 4 downTo 0) {
            val key = keys.text("bk$i")
            if (key.isNotEmpty() && key != "undefined") {
                clean =
                    clean.replace(
                        "//" + Base64.getEncoder().encodeToString(uriEncode(key).toByteArray()),
                        "",
                    )
            }
        }
        return candidates(clean) ?: error("PlayerJS вернул некорректный плейлист")
    }

    fun episodes(json: JSONObject, releaseId: Int): List<Episode> {
        val file = json.opt("file")
        val entries =
            when (file) {
                is String ->
                    if (file.isBlank()) emptyList() else listOf(JSONObject().put("file", file))
                is JSONArray -> file.objects().filter { it.text("file").isNotBlank() }
                else -> emptyList()
            }
        require(entries.isNotEmpty()) { "В плейлисте не найдены серии" }
        return entries.mapIndexed { index, item ->
            Episode(
                item.text("id").ifBlank { "$releaseId-${index + 1}" },
                releaseId,
                index + 1,
                item.text("title").ifBlank { item.text("label").ifBlank { "Серия ${index + 1}" } },
                item.getString("file"),
            )
        }
    }

    fun streams(episode: Episode): List<VideoStream> =
        Regex("https?://[^\\s]+")
            .findAll(episode.file)
            .mapNotNull { match ->
                runCatching {
                        val url = match.value
                        val path = URI(url).path.lowercase()
                        val type =
                            when {
                                path.endsWith(".m3u8") -> "hls"
                                path.endsWith(".mpd") -> "dash"
                                path.endsWith(".mp4") -> "mp4"
                                path.endsWith(".webm") -> "webm"
                                else -> "unknown"
                            }
                        val quality =
                            Regex("(?<!\\d)(2160|1080|720|480|360|240|144)p?(?!\\d)")
                                .find(url)
                                ?.groupValues
                                ?.get(1)
                                ?.toInt() ?: 1080
                        VideoStream(url, type, quality)
                    }
                    .getOrNull()
            }
            .sortedWith(
                compareBy<VideoStream> { if (it.type == "hls") 0 else 1 }
                    .thenByDescending { it.quality }
            )
            .toList()
}
