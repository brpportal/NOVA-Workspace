package com.example.nova.browser

import org.json.JSONObject

/**
 * Pure parser for the page-extraction payload produced by
 * [BrowserController]'s observation script. JSON in → [PageObservation] out,
 * tolerant of malformed payloads (returns an empty observation rather than
 * throwing) and of fields the script omits. Unit-testable on the JVM.
 *
 * Payload shape:
 * {
 *   "url": "...", "title": "...",
 *   "elements": [ { "index": 1, "tag": "input", "type": "search",
 *                   "label": "Search", "placeholder": "...", "name": "q",
 *                   "href": "...", "value": "...", "disabled": false,
 *                   "x": 100, "y": 200, "width": 300, "height": 40,
 *                   "inViewport": true } ],
 *   "text": "page text excerpt"
 * }
 */
object PageElementsParser {

    const val MAX_ELEMENTS = 60

    /**
     * Cheap identity of a page state for the observation cache (fix round
     * 2, Defect E loop economy): same URL + same DOM size means the page
     * the agent already observed — re-extracting it would only waste a
     * provider round-trip. Any navigation or DOM change changes the key.
     */
    fun cacheKey(url: String, domLength: Int): String = "${url.trim()}|dom:$domLength"

    fun parse(payload: String?): PageObservation {
        if (payload.isNullOrBlank()) return PageObservation()
        val root = try {
            JSONObject(payload)
        } catch (e: Exception) {
            return PageObservation()
        }
        val elements = mutableListOf<PageElement>()
        val arr = root.optJSONArray("elements")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                if (elements.size >= MAX_ELEMENTS) break
                val obj = arr.optJSONObject(i) ?: continue
                val index = obj.optInt("index", elements.size + 1)
                if (index <= 0) continue
                elements.add(
                    PageElement(
                        index = index,
                        tag = obj.optString("tag").lowercase().ifBlank { "unknown" },
                        type = obj.optString("type").ifBlank { null },
                        label = obj.optString("label").trim().take(120),
                        placeholder = obj.optString("placeholder").ifBlank { null },
                        name = obj.optString("name").ifBlank { null },
                        href = obj.optString("href").ifBlank { null },
                        currentValue = obj.optString("value").ifBlank { null },
                        disabled = obj.optBoolean("disabled", false),
                        x = obj.optInt("x"),
                        y = obj.optInt("y"),
                        width = obj.optInt("width"),
                        height = obj.optInt("height"),
                        inViewport = obj.optBoolean("inViewport", true)
                    )
                )
            }
        }
        return PageObservation(
            url = root.optString("url"),
            title = root.optString("title"),
            elements = elements,
            textExcerpt = root.optString("text").replace(Regex("\\s+"), " ").trim().take(4000),
            scrollY = root.optInt("scrollY", 0).coerceAtLeast(0),
            viewportHeight = root.optInt("viewportHeight", 0).coerceAtLeast(0),
            pageHeight = root.optInt("pageHeight", 0).coerceAtLeast(0)
        )
    }
}
