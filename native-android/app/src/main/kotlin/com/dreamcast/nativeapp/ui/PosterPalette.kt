package com.dreamcast.nativeapp.ui

/** A tiny RGB histogram, independent of Android and the UI thread. */
internal data class PosterPalette(val primary: Int, val secondary: Int) {
    companion object {
        fun fromPixels(pixels: IntArray): PosterPalette? {
            val counts = IntArray(4096)
            val reds = IntArray(4096)
            val greens = IntArray(4096)
            val blues = IntArray(4096)
            var total = 0
            var redTotal = 0L
            var greenTotal = 0L
            var blueTotal = 0L
            for (pixel in pixels) {
                if ((pixel ushr 24) < 128) continue
                val r = (pixel ushr 16) and 255
                val g = (pixel ushr 8) and 255
                val b = pixel and 255
                total++
                redTotal += r
                greenTotal += g
                blueTotal += b
                // Ignore black borders and white lettering when finding characteristic colours.
                if (maxOf(r, g, b) < 35 || minOf(r, g, b) > 225) continue
                val bucket = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                counts[bucket]++
                reds[bucket] += r
                greens[bucket] += g
                blues[bucket] += b
            }
            if (total == 0) return null
            fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b
            fun colour(index: Int) =
                rgb(
                    reds[index] / counts[index],
                    greens[index] / counts[index],
                    blues[index] / counts[index],
                )
            fun score(index: Int): Float {
                if (counts[index] == 0) return -1f
                val r = reds[index] / counts[index]
                val g = greens[index] / counts[index]
                val b = blues[index] / counts[index]
                return counts[index] * (0.2f + (maxOf(r, g, b) - minOf(r, g, b)) / 255f)
            }
            val first = counts.indices.filter { counts[it] > 0 }.maxByOrNull(::score)
            if (first == null) {
                val average =
                    rgb(
                        (redTotal / total).toInt(),
                        (greenTotal / total).toInt(),
                        (blueTotal / total).toInt(),
                    )
                return PosterPalette(average, average)
            }
            val primary = colour(first)
            fun distanceSquared(colour: Int): Int {
                val r = ((primary ushr 16) and 255) - ((colour ushr 16) and 255)
                val g = ((primary ushr 8) and 255) - ((colour ushr 8) and 255)
                val b = (primary and 255) - (colour and 255)
                return r * r + g * g + b * b
            }
            val second =
                counts.indices
                    .filter {
                        counts[it] >= maxOf(1, total / 50) && distanceSquared(colour(it)) >= 80 * 80
                    }
                    .maxByOrNull(::score)
            return PosterPalette(primary, second?.let(::colour) ?: primary)
        }
    }
}

/** Retain colours only, never image resources or activity references. */
internal class PosterPaletteCache(private val limit: Int = 128) {
    init {
        require(limit > 0)
    }

    private val entries = LinkedHashMap<String, PosterPalette>(limit, 0.75f, true)

    @Synchronized fun get(url: String) = entries[url]

    @Synchronized
    fun put(url: String, palette: PosterPalette) {
        entries[url] = palette
        if (entries.size > limit)
            entries.entries.iterator().run {
                next()
                remove()
            }
    }
}
