package com.example

import com.example.nova.browser.PageElementsParser
import com.example.nova.browser.PageObservation
import com.example.nova.browser.ScrollState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scroll addition: scroll state is part of the agent's environment —
 * every observation carries where the viewport sits and whether more
 * content exists below (the stopping signal for long/infinite pages),
 * and the parser carries the metrics out of the extraction payload.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScrollStateTest {

    @Test
    fun `mid-page state reports more content below`() {
        val state = ScrollState(scrollY = 900, viewportHeight = 900, pageHeight = 3600)
        assertTrue(state.canScrollDown)
        assertTrue(state.canScrollUp)
        assertEquals(33, state.percentDown)
        val line = state.describe()
        assertTrue(line.contains("~33% down"))
        assertTrue(line.contains("more content below"))
    }

    @Test
    fun `bottom of page reports no more content below`() {
        val state = ScrollState(scrollY = 2700, viewportHeight = 900, pageHeight = 3600)
        assertFalse(state.canScrollDown)
        assertTrue(state.canScrollUp)
        assertEquals(100, state.percentDown)
        assertTrue(state.describe().contains("at the bottom"))
    }

    @Test
    fun `top of a long page cannot scroll up`() {
        val state = ScrollState(scrollY = 0, viewportHeight = 900, pageHeight = 3600)
        assertFalse(state.canScrollUp)
        assertTrue(state.canScrollDown)
        assertEquals(0, state.percentDown)
    }

    @Test
    fun `page that fits on one screen reports 100 percent and no scrolling`() {
        val state = ScrollState(scrollY = 0, viewportHeight = 900, pageHeight = 800)
        assertFalse(state.canScrollDown)
        assertFalse(state.canScrollUp)
        assertEquals(100, state.percentDown)
        assertTrue(state.describe().contains("fits on one screen"))
    }

    @Test
    fun `missing metrics describe nothing instead of guessing`() {
        assertEquals("", ScrollState().describe())
        assertEquals("", ScrollState(scrollY = 10, viewportHeight = 0, pageHeight = 5000).describe())
        assertFalse(ScrollState().canScrollDown)
    }

    @Test
    fun `parser carries scroll metrics into the observation`() {
        val payload = """
            {"url": "https://example.com/feed", "title": "Feed", "elements": [], "text": "hello",
             "scrollY": 1350, "viewportHeight": 900, "pageHeight": 5400}
        """.trimIndent()
        val obs = PageElementsParser.parse(payload)
        assertEquals(1350, obs.scrollY)
        assertEquals(900, obs.viewportHeight)
        assertEquals(5400, obs.pageHeight)
        assertTrue(obs.scrollState.canScrollDown)
        assertEquals(30, obs.scrollState.percentDown)
    }

    @Test
    fun `parser tolerates payloads without scroll metrics`() {
        val obs = PageElementsParser.parse("""{"url":"https://a.b/","title":"T","elements":[],"text":""}""")
        assertEquals(0, obs.scrollY)
        assertEquals("", obs.scrollState.describe())
    }

    @Test
    fun `observation text carries the scroll line for the model`() {
        val obs = PageObservation(
            url = "https://example.com/feed",
            title = "Feed",
            scrollY = 1800,
            viewportHeight = 900,
            pageHeight = 3600
        )
        val formatted = obs.formatForModel()
        assertTrue(formatted.contains("Scroll: ~66% down"))
        assertTrue(formatted.contains("more content below"))
    }
}
