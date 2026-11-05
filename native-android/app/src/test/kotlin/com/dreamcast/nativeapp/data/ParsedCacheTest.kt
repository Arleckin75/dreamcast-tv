package com.dreamcast.nativeapp.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ParsedCacheTest {
    @Test
    fun concurrentSourcesForTheSameKeyNeverReturnAnotherSourcesValue() = runBlocking {
        val cache = ParsedCache(maxEntries = 4, maxSourceChars = 64)
        coroutineScope {
            val jobs =
                (0 until 8).map { worker ->
                    launch(Dispatchers.Default) {
                        repeat(200) { iteration ->
                            val text = "$worker:$iteration"
                            assertEquals(
                                listOf(text),
                                cache.parse("shared", null, text) { listOf(it) },
                            )
                            cache.parse("other:$worker", null, text) { listOf(it) }
                        }
                    }
                }
            jobs.forEach { it.join() }
        }
        assertEquals(listOf("final"), cache.parse("shared", null, "final") { listOf(it) })
    }

    @Test
    fun unchangedSourceIsParsedOnceButChangedSourceAndContextAreReparsed() {
        val cache = ParsedCache()
        var calls = 0
        val parser: (String) -> List<String> = {
            calls++
            listOf(it)
        }
        val original = cache.parse("key", "context", "one", parser)
        assertSame(original, cache.parse("key", "context", "one", parser))
        assertEquals(1, calls)
        assertEquals(listOf("two"), cache.parse("key", "context", "two", parser))
        cache.parse("key", "new context", "two", parser)
        assertEquals(3, calls)
    }

    @Test
    fun leastRecentlyUsedEntryIsEvictedAtCountLimit() {
        val cache = ParsedCache(maxEntries = 2)
        var calls = 0
        val parser: (String) -> List<String> = {
            calls++
            listOf(it)
        }
        val first = cache.parse("first", null, "a", parser)
        cache.parse("second", null, "b", parser)
        assertSame(first, cache.parse("first", null, "a", parser))
        cache.parse("third", null, "c", parser)
        assertSame(first, cache.parse("first", null, "a", parser))
        cache.parse("second", null, "b", parser)
        assertEquals(4, calls)
    }

    @Test
    fun sourceSizeBudgetEvictsOldEntriesAndDoesNotRetainOversizedPayload() {
        val cache = ParsedCache(maxSourceChars = 4)
        var calls = 0
        val parser: (String) -> List<String> = {
            calls++
            listOf(it)
        }
        cache.parse("a", null, "123", parser)
        cache.parse("b", null, "45", parser)
        cache.parse("a", null, "123", parser)
        cache.parse("large", null, "12345", parser)
        cache.parse("large", null, "12345", parser)
        assertEquals(5, calls)
    }

    @Test
    fun failedParsingNeverPoisonsExistingEntry() {
        val cache = ParsedCache()
        val original = cache.parse("key", null, "good") { listOf(it) }
        try {
            cache.parse<List<String>>("key", null, "bad") { error("Malformed payload") }
            fail("Parsing must fail")
        } catch (_: IllegalStateException) {}
        assertSame(
            original,
            cache.parse<List<String>>("key", null, "good") { error("Must reuse old value") },
        )
    }
}
