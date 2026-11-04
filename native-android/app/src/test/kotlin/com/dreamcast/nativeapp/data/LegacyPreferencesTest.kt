package com.dreamcast.nativeapp.data

import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class LegacyPreferencesTest {
    private val prefix = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"

    @Test
    fun `reads current Flutter JSON list prefix`() {
        assertEquals(
            listOf("Магия", "Комедия"),
            LegacyPreferences.stringList(prefix + "![\"Магия\",\"Комедия\"]"),
        )
    }

    @Test
    fun `reads old Flutter serialized array lists`() {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(arrayListOf("Магия", "Комедия")) }
        assertEquals(
            listOf("Магия", "Комедия"),
            LegacyPreferences.stringList(
                prefix + Base64.getEncoder().encodeToString(bytes.toByteArray())
            ),
        )
    }

    @Test
    fun `corrupt or foreign serialized objects do not break history`() {
        assertTrue(LegacyPreferences.stringList(prefix + "invalid").isEmpty())
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(java.util.Date()) }
        assertTrue(
            LegacyPreferences.stringList(
                    prefix + Base64.getEncoder().encodeToString(bytes.toByteArray())
                )
                .isEmpty()
        )
    }
}
