package com.dreamcast.nativeapp

/** Main-thread cache of recent searches/filters, preserving pagination on detail-screen return. */
internal class FeedCache(
    private val maxEntries: Int = 8,
    private val maxRetainedItems: Int = 2048,
) {
    init {
        require(maxEntries > 0 && maxRetainedItems > 0)
    }

    private val entries = LinkedHashMap<String, FeedState>(maxEntries, 0.75f, true)

    fun getOrCreate(key: String): FeedState {
        trim(key)
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

    /** Never truncate the active catalogue; discard inactive feeds first. */
    fun trim(activeKey: String) {
        var count = entries.values.sumOf { it.items.size }
        val iterator = entries.entries.iterator()
        while (count > maxRetainedItems && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key != activeKey) {
                count -= entry.value.items.size
                iterator.remove()
            }
        }
    }
}
