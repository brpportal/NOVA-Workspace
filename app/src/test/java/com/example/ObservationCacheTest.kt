package com.example

import com.example.nova.browser.PageElementsParser
import com.example.nova.browser.PageObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fix round 2, Defect E (loop economy): the observation cache key.
 *
 * Same URL + same DOM size = the page the agent already observed, so the
 * cached observation can be returned (marked "(page unchanged)") instead
 * of paying for a full re-extraction and another provider round-trip.
 * Any navigation or DOM change must produce a different key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ObservationCacheTest {

    @Test
    fun `same url and dom size give the same key`() {
        assertEquals(
            PageElementsParser.cacheKey("https://www.facebook.com/", 152_340),
            PageElementsParser.cacheKey("https://www.facebook.com/", 152_340)
        )
    }

    @Test
    fun `different url gives a different key`() {
        assertNotEquals(
            PageElementsParser.cacheKey("https://www.facebook.com/", 152_340),
            PageElementsParser.cacheKey("https://m.facebook.com/", 152_340)
        )
    }

    @Test
    fun `changed dom gives a different key`() {
        assertNotEquals(
            PageElementsParser.cacheKey("https://www.facebook.com/", 152_340),
            PageElementsParser.cacheKey("https://www.facebook.com/", 152_980)
        )
    }

    @Test
    fun `cached observation is marked page unchanged for the model`() {
        val cached = PageObservation(
            url = "https://www.facebook.com/",
            title = "Facebook",
            fromCache = true
        )
        val formatted = cached.formatForModel()
        assertTrue(formatted.contains("(page unchanged"))
        assertTrue(formatted.contains("https://www.facebook.com/"))
    }

    @Test
    fun `fresh observation has no unchanged marker`() {
        val fresh = PageObservation(url = "https://www.facebook.com/", title = "Facebook")
        assertTrue(!fresh.formatForModel().contains("(page unchanged"))
    }
}
