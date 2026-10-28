package com.example

import com.example.nova.browser.PageElementsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The set-of-marks observation parser: extraction JSON in → grounded
 * element list out. This is the perception half of the browser agent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageElementsParserTest {

    @Test
    fun `parses a google-style page with a search box`() {
        val payload = """
        {
          "url": "https://www.google.com/",
          "title": "Google",
          "elements": [
            {"index": 1, "tag": "a", "type": "", "label": "Gmail", "placeholder": "", "name": "", "href": "https://mail.google.com/", "value": "", "disabled": false, "x": 900, "y": 30, "width": 60, "height": 20, "inViewport": true},
            {"index": 2, "tag": "textarea", "type": "", "label": "Search", "placeholder": "", "name": "q", "href": "", "value": "", "disabled": false, "x": 400, "y": 300, "width": 500, "height": 44, "inViewport": true},
            {"index": 3, "tag": "input", "type": "submit", "label": "Google Search", "placeholder": "", "name": "btnK", "href": "", "value": "Google Search", "disabled": false, "x": 350, "y": 380, "width": 120, "height": 36, "inViewport": true}
          ],
          "text": "Gmail   Images\nSign in"
        }
        """.trimIndent()
        val obs = PageElementsParser.parse(payload)
        assertEquals("https://www.google.com/", obs.url)
        assertEquals("Google", obs.title)
        assertEquals(3, obs.elements.size)
        val searchBox = obs.elements[1]
        assertEquals(2, searchBox.index)
        assertEquals("textarea", searchBox.tag)
        assertEquals("q", searchBox.name)
        assertEquals("Search", searchBox.label)
        // The model-facing format must carry the indices and labels
        val formatted = obs.formatForModel()
        assertTrue(formatted.contains("[2] <textarea"))
        assertTrue(formatted.contains("name=\"q\""))
        assertTrue(formatted.contains("[3] <input type=\"submit\">"))
        assertTrue(formatted.contains("Gmail Images Sign in"))
    }

    @Test
    fun `malformed payloads yield an empty observation, never a crash`() {
        assertTrue(PageElementsParser.parse(null).elements.isEmpty())
        assertTrue(PageElementsParser.parse("").elements.isEmpty())
        assertTrue(PageElementsParser.parse("not json at all").elements.isEmpty())
        assertTrue(PageElementsParser.parse("{}").elements.isEmpty())
        assertTrue(PageElementsParser.parse("""{"elements": "nope"}""").elements.isEmpty())
    }

    @Test
    fun `element cap is enforced`() {
        val items = (1..90).joinToString(",") { i ->
            """{"index": $i, "tag": "a", "label": "link $i", "x": 1, "y": 2, "width": 10, "height": 10}"""
        }
        val obs = PageElementsParser.parse("""{"url": "https://x.test/", "elements": [$items]}""")
        assertEquals(PageElementsParser.MAX_ELEMENTS, obs.elements.size)
    }

    @Test
    fun `empty page observation tells the model honestly`() {
        val obs = PageElementsParser.parse("""{"url": "https://x.test/", "title": "", "elements": [], "text": ""}""")
        val formatted = obs.formatForModel()
        assertTrue(formatted.contains("none found"))
    }
}
