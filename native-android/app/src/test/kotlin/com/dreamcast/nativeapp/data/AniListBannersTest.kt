package com.dreamcast.nativeapp.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AniListBannersTest {
    private fun media(romaji: String, year: Int?, banner: String?, synonyms: List<String> = emptyList()) =
        JSONObject()
            .put("seasonYear", year ?: JSONObject.NULL)
            .put("startDate", JSONObject().put("year", year ?: JSONObject.NULL))
            .put("title", JSONObject().put("romaji", romaji).put("english", JSONObject.NULL))
            .put("synonyms", JSONArray(synonyms))
            .put("bannerImage", banner ?: JSONObject.NULL)

    @Test
    fun `exact title in the same year wins over an earlier candidate`() {
        val candidates =
            listOf(media("Other Show", 2026, "other.jpg"), media("Ao no Hako", 2026, "ao.jpg"))
        assertEquals("ao.jpg", AniListBanners.pick(candidates, "Ao no Hako", 2026))
    }

    @Test
    fun `exact title a year apart is accepted`() {
        val candidates =
            listOf(media("Unrelated", 2026, "x.jpg"), media("Ao no Hako", 2025, "ao.jpg"))
        assertEquals("ao.jpg", AniListBanners.pick(candidates, "Ao no Hako", 2026))
    }

    @Test
    fun `exact title two years apart falls back to the same year`() {
        val candidates =
            listOf(media("Ao no Hako", 2024, "old.jpg"), media("Ao no Hako 2", 2026, "new.jpg"))
        assertEquals("new.jpg", AniListBanners.pick(candidates, "Ao no Hako", 2026))
    }

    @Test
    fun `synonyms and punctuation count as the same title`() {
        val candidates =
            listOf(
                media("Tantei wa mou, Shindeiru. Season 2", 2026, "t.jpg", listOf("Tantei wa Mou Shindeiru 2"))
            )
        assertEquals("t.jpg", AniListBanners.pick(candidates, "Tantei wa Mou, Shindeiru 2", 2025))
    }

    @Test
    fun `no candidate near the year gives no banner`() {
        val candidates = listOf(media("Something", 2019, "s.jpg"))
        assertNull(AniListBanners.pick(candidates, "Ao no Hako", 2026))
    }

    @Test
    fun `chosen candidate without a banner gives no banner`() {
        val candidates = listOf(media("Ao no Hako", 2026, null), media("Ao", 2026, "other.jpg"))
        assertNull(AniListBanners.pick(candidates, "Ao no Hako", 2026))
    }

    @Test
    fun `exact title without a year on AniList is accepted`() {
        val candidates =
            listOf(media("Unrelated", 2019, "x.jpg"), media("Ao no Hako 3", null, "ao3.jpg"))
        assertEquals("ao3.jpg", AniListBanners.pick(candidates, "Ao no Hako 3", 2026))
    }

    @Test
    fun `another title without a year is not taken`() {
        val candidates = listOf(media("Something Else", null, "s.jpg"))
        assertNull(AniListBanners.pick(candidates, "Ao no Hako 3", 2026))
    }

    @Test
    fun `short title search does not take an unrelated show of the same year`() {
        val candidates = listOf(media("Mahou Shoujo Something", 2026, "wrong.jpg"))
        assertNull(AniListBanners.pick(candidates, "Kabushikigaisha Magi-Lumiere 2nd Season", 2026, requirePrefix = "Kabushikigaisha Magi"))
    }

    @Test
    fun `short title search takes a show whose name starts with the short title`() {
        val candidates =
            listOf(
                media("Mahou Shoujo Something", 2026, "wrong.jpg"),
                media("Kabushikigaisha Magi-Lumiere Season 2", 2026, "right.jpg"),
            )
        assertEquals("right.jpg", AniListBanners.pick(candidates, "Kabushikigaisha Magi-Lumiere 2nd Season", 2026, requirePrefix = "Kabushikigaisha Magi"))
    }
}
