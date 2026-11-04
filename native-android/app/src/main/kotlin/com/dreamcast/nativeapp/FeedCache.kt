package com.dreamcast.nativeapp

/** Main-thread cache of recent searches/filters, preserving pagination on detail-screen return. */
internal class FeedCache(private val maxEntries: Int = 8) {
    init {
        require(maxEntries > 0)
    }

    private val entries = LinkedHashMap<String, FeedState>(maxEntries, 0.75f, true)

    fun getOrCreate(key: String): FeedState {
        entries[key]?.let {
            return it
        }
        val feed = FeedState()
        entries[key] = feed
        if (entries.size > maxEntries) {
            val oldest = entries.entries.iterator()
            oldest.next()
            oldest.remove()
        }
        return feed
    }
}
