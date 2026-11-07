package com.dreamcast.nativeapp

import android.app.Application
import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Format.Builder.setLanguage normalizes the tag through Android's TextUtils.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class PlayerTrackMenuTest {
    @Test
    fun `unnamed tracks are numbered`() {
        assertEquals("Дорожка 3", trackLabel(Format.Builder().build(), 2))
        assertEquals("Дорожка 1", trackLabel(Format.Builder().setLanguage("und").build(), 0))
    }

    @Test
    fun `labels combine track label and Russian language name`() {
        assertEquals("английский", trackLabel(Format.Builder().setLanguage("en").build(), 0))
        assertEquals(
            "AniLibria · японский",
            trackLabel(Format.Builder().setLabel("AniLibria").setLanguage("ja").build(), 0),
        )
    }
}
