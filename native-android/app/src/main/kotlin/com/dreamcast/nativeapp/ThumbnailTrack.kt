package com.dreamcast.nativeapp

import java.net.URI

/** One preview frame: the part of a sprite image shown for [startMs]..[endMs). */
internal data class ThumbnailCue(
    val startMs: Long,
    val endMs: Long,
    val imageUrl: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Seek preview frames from a WebVTT thumbnails track, e.g.
 * `00:00:10.000 --> 00:00:20.000` followed by `img1.webp#xywh=160,0,160,90`.
 */
internal class ThumbnailTrack(val cues: List<ThumbnailCue>) {
    /**
     * One cue per sprite around [positionMs]: the sprite shown there and the sprites just before
     * and after it, so scrubbing from here in either direction finds its frames already loaded.
     */
    fun spritesAround(positionMs: Long): List<ThumbnailCue> {
        val here = frameAt(positionMs) ?: return emptyList()
        val index = cues.indexOf(here)
        val before = cues.subList(0, index).lastOrNull { it.imageUrl != here.imageUrl }
        val after = cues.subList(index + 1, cues.size).firstOrNull { it.imageUrl != here.imageUrl }
        return listOfNotNull(here, after, before)
    }

    /** The frame shown at [positionMs]: the cue containing it, else the closest earlier one. */
    fun frameAt(positionMs: Long): ThumbnailCue? {
        if (cues.isEmpty()) return null
        var low = 0
        var high = cues.lastIndex
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (cues[middle].startMs <= positionMs) low = middle else high = middle - 1
        }
        return cues[low]
    }

    companion object {
        private val timing = Regex("""^\s*(\S+)\s+-->\s+(\S+)""")
        private val region = Regex("""#xywh=(\d+),(\d+),(\d+),(\d+)$""")

        /** Parses [text]; image paths are resolved against [baseUrl]. Bad cues are skipped. */
        fun parse(text: String, baseUrl: String): ThumbnailTrack {
            val base = URI(baseUrl)
            val lines = text.lineSequence().map { it.trim() }.toList()
            val cues = mutableListOf<ThumbnailCue>()
            var index = 0
            while (index < lines.size) {
                val time = timing.find(lines[index])
                index++
                if (time == null) continue
                val start = parseTime(time.groupValues[1]) ?: continue
                val end = parseTime(time.groupValues[2]) ?: continue
                val target = lines.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: continue
                index++
                val box = region.find(target)
                val path = if (box == null) target else target.substring(0, box.range.first)
                val url = runCatching { base.resolve(path).toString() }.getOrNull() ?: continue
                if (!url.startsWith("https://")) continue
                val (x, y, w, h) = box?.destructured?.toList()?.map(String::toInt) ?: listOf(0, 0, 0, 0)
                if (end > start) cues += ThumbnailCue(start, end, url, x, y, w, h)
            }
            return ThumbnailTrack(cues.sortedBy { it.startMs })
        }

        /** `hh:mm:ss.mmm` or `mm:ss.mmm` to milliseconds. */
        internal fun parseTime(value: String): Long? {
            val parts = value.split(':')
            if (parts.size !in 2..3) return null
            val seconds = parts.last().replace(',', '.').toDoubleOrNull() ?: return null
            val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
            val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0
            if (seconds < 0 || minutes < 0 || hours < 0) return null
            return (hours * 3600 + minutes * 60) * 1000 + Math.round(seconds * 1000)
        }
    }
}
