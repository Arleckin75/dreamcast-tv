package com.dreamcast.nativeapp.data

/** Reuse parsed disk contents, bounded by entry count and retained source-text size. */
internal class ParsedCache(
    private val maxEntries: Int = 32,
    private val maxSourceChars: Int = 2 * 1024 * 1024,
) {
    private data class Entry(val context: Any?, val text: String, val value: Any)

    private val entries = LinkedHashMap<String, Entry>(maxEntries, 0.75f, true)
    private var sourceChars = 0L

    init {
        require(maxEntries > 0 && maxSourceChars > 0)
    }

    // Repository keys identify a single result type; context covers parser fallback inputs.
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> parse(key: String, context: Any?, text: String, parser: (String) -> T): T {
        synchronized(this) {
            entries[key]?.let { if (it.context == context && it.text == text) return it.value as T }
        }
        // Parse outside the lock so unrelated screens can load concurrently.
        val result = parser(text)
        synchronized(this) {
            entries.remove(key)?.let { sourceChars -= it.text.length }
            if (text.length <= maxSourceChars) {
                entries[key] = Entry(context, text, result)
                sourceChars += text.length
                val oldest = entries.entries.iterator()
                while (entries.size > maxEntries || sourceChars > maxSourceChars) {
                    sourceChars -= oldest.next().value.text.length
                    oldest.remove()
                }
            }
        }
        return result
    }
}
