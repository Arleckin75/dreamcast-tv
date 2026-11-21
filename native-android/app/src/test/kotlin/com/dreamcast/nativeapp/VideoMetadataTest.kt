package com.dreamcast.nativeapp

import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class VideoMetadataTest {
    @Test
    fun `a completed inspection can return an empty format list`() = runBlocking {
        assertEquals(emptyList<Any>(), videoFormatsWithTimeout { emptyList() })
    }

    @Test
    fun `inspection timeout releases resources and becomes a playback error`() = runBlocking {
        var released = false
        try {
            videoFormatsWithTimeout(timeoutMs = 50) {
                try {
                    awaitCancellation()
                } finally {
                    released = true
                }
            }
            fail("Expected a playback error")
        } catch (error: IOException) {
            assertEquals("Превышено время ожидания проверки видеопотока", error.message)
        }
        assertTrue(released)
    }

    @Test
    fun `cancellation by the caller is not converted into a playback error`() = runBlocking {
        var released = false
        try {
            withTimeout(50) {
                videoFormatsWithTimeout(timeoutMs = 5000) {
                    try {
                        awaitCancellation()
                    } finally {
                        released = true
                    }
                }
            }
            fail("Expected caller cancellation")
        } catch (_: TimeoutCancellationException) {}
        assertTrue(released)
    }

    @Test
    fun `inspection failures preserve their error message`() = runBlocking {
        val failure = IOException("Stream unavailable")
        try {
            videoFormatsWithTimeout { throw failure }
            fail("Expected the inspection error")
        } catch (error: IOException) {
            assertEquals(failure.message, error.message)
        }
    }
}
