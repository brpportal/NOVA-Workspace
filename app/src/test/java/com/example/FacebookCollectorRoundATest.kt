package com.example

import com.example.nova.trained.FacebookExtractionMode
import com.example.nova.trained.FacebookLinkAttribution
import com.example.nova.trained.FacebookPostLinks
import com.example.nova.trained.FacebookProfileResult
import com.example.nova.trained.FacebookProfileStatus
import com.example.nova.trained.FacebookRunArtifacts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round A (NOVA M3 Control Center) — pure coverage for the new
 * collector surface: the configurable 1–100 limit + count-aware
 * budgets, extraction-mode plumbing, source-list input hygiene,
 * per-link provenance merging, and the TXT/CSV/JSON/summary artifact
 * builders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FacebookCollectorRoundATest {

    // ------------------------------------------------------------------
    // Item 1 — configurable limit + budget formulas
    // ------------------------------------------------------------------

    @Test
    fun `limit clamps into 1 to 100 and the default stays 5`() {
        assertEquals(1, FacebookPostLinks.MIN_POSTS_PER_PROFILE)
        assertEquals(100, FacebookPostLinks.MAX_POSTS_PER_PROFILE)
        assertEquals(5, FacebookPostLinks.DEFAULT_POSTS_PER_PROFILE)
        assertEquals(1, FacebookPostLinks.clampPostsPerProfile(0))
        assertEquals(1, FacebookPostLinks.clampPostsPerProfile(-7))
        assertEquals(1, FacebookPostLinks.clampPostsPerProfile(1))
        assertEquals(5, FacebookPostLinks.clampPostsPerProfile(5))
        assertEquals(7, FacebookPostLinks.clampPostsPerProfile(7))
        assertEquals(100, FacebookPostLinks.clampPostsPerProfile(100))
        assertEquals(100, FacebookPostLinks.clampPostsPerProfile(250))
    }

    @Test
    fun `budgets scale with the requested count under a hard ceiling`() {
        // Formula: 180s + 6s per requested link, ceiling 900s.
        assertEquals(186_000L, FacebookPostLinks.perProfileBudgetMs(1))
        assertEquals(210_000L, FacebookPostLinks.perProfileBudgetMs(5))
        assertEquals(780_000L, FacebookPostLinks.perProfileBudgetMs(100))
        assertTrue(FacebookPostLinks.perProfileBudgetMs(100) <= 900_000L)
        assertTrue(
            FacebookPostLinks.perProfileBudgetMs(50) > FacebookPostLinks.perProfileBudgetMs(5)
        )
        // Scroll caps: min(15 + N, 120) and min(6 + N/2, 40).
        assertEquals(20, FacebookPostLinks.maxScrollsFor(5))
        assertEquals(115, FacebookPostLinks.maxScrollsFor(100))
        assertEquals(8, FacebookPostLinks.fallbackScrollsFor(5))
        assertEquals(40, FacebookPostLinks.fallbackScrollsFor(100))
    }

    // ------------------------------------------------------------------
    // Item 2 — extraction-mode plumbing
    // ------------------------------------------------------------------

    @Test
    fun `mode tool args parse and round-trip`() {
        assertEquals(FacebookExtractionMode.HYBRID, FacebookExtractionMode.fromToolArg(null))
        assertEquals(FacebookExtractionMode.HYBRID, FacebookExtractionMode.fromToolArg(""))
        assertEquals(FacebookExtractionMode.HYBRID, FacebookExtractionMode.fromToolArg("hybrid"))
        assertEquals(FacebookExtractionMode.M1_ONLY, FacebookExtractionMode.fromToolArg("m1"))
        assertEquals(FacebookExtractionMode.M1_ONLY, FacebookExtractionMode.fromToolArg(" M1 "))
        assertEquals(FacebookExtractionMode.M3_ONLY, FacebookExtractionMode.fromToolArg("m3"))
        assertEquals(FacebookExtractionMode.HYBRID, FacebookExtractionMode.fromToolArg("everything"))
        for (mode in FacebookExtractionMode.values()) {
            assertEquals(mode, FacebookExtractionMode.fromToolArg(mode.toolArg()))
        }
    }

    @Test
    fun `yield labels map to method codes`() {
        assertEquals("M1", FacebookPostLinks.methodCodeForYieldLabel("M1 mobile"))
        assertEquals("M2", FacebookPostLinks.methodCodeForYieldLabel("M2 posts section"))
        assertEquals("M3", FacebookPostLinks.methodCodeForYieldLabel("M1+M3 combined"))
        assertEquals("M3", FacebookPostLinks.methodCodeForYieldLabel("M3 source mining"))
        assertEquals("M4", FacebookPostLinks.methodCodeForYieldLabel("M4 desktop"))
        assertEquals("M5", FacebookPostLinks.methodCodeForYieldLabel("M5 mbasic"))
    }

    // ------------------------------------------------------------------
    // Item 3 — input hygiene
    // ------------------------------------------------------------------

    @Test
    fun `source validation requires an http scheme and a host`() {
        assertTrue(FacebookPostLinks.isValidSourceUrl("https://www.facebook.com/some.page"))
        assertTrue(FacebookPostLinks.isValidSourceUrl("http://example.com/x"))
        assertTrue(FacebookPostLinks.isValidSourceUrl("  https://m.facebook.com/a.page  "))
        assertFalse(FacebookPostLinks.isValidSourceUrl("facebook.com/some.page"))
        assertFalse(FacebookPostLinks.isValidSourceUrl("www.facebook.com/some.page"))
        assertFalse(FacebookPostLinks.isValidSourceUrl("dawateislami"))
        assertFalse(FacebookPostLinks.isValidSourceUrl("ftp://example.com/x"))
        assertFalse(FacebookPostLinks.isValidSourceUrl("https://"))
        assertFalse(FacebookPostLinks.isValidSourceUrl(""))
        assertFalse(FacebookPostLinks.isValidSourceUrl("not a url"))
    }

    @Test
    fun `input parse labels sources, lists invalid lines with numbers, counts duplicates`() {
        val text = listOf(
            "https://www.facebook.com/a.page",   // line 1 — Link 1
            "",                                  // line 2 — blank, ignored
            "not a url",                         // line 3 — invalid
            "https://m.facebook.com/a.page/",    // line 4 — duplicate of Link 1 (normalized)
            "https://www.facebook.com/b.page",   // line 5 — Link 2
            "ftp://example.com/x",               // line 6 — invalid scheme
            "https://www.facebook.com/b.page"    // line 7 — exact duplicate
        ).joinToString("\n")
        val parse = FacebookPostLinks.parseCollectorInput(text)
        assertEquals(
            listOf("https://www.facebook.com/a.page", "https://www.facebook.com/b.page"),
            parse.sources
        )
        assertEquals(listOf("Link 1", "Link 2"), parse.labels)
        assertEquals(2, parse.duplicatesRemoved)
        assertEquals(
            listOf(3 to "not a url", 6 to "ftp://example.com/x"),
            parse.invalidLines.map { it.lineNumber to it.raw }
        )
    }

    @Test
    fun `input parse of empty or all-blank text yields nothing`() {
        val parse = FacebookPostLinks.parseCollectorInput("\n  \n\n")
        assertTrue(parse.sources.isEmpty())
        assertTrue(parse.labels.isEmpty())
        assertTrue(parse.invalidLines.isEmpty())
        assertEquals(0, parse.duplicatesRemoved)
    }

    // ------------------------------------------------------------------
    // Item 4 — provenance tracker + object types
    // ------------------------------------------------------------------

    @Test
    fun `provenance tracker keeps the first finder and merges in order`() {
        var tick = 1_000L
        val tracker = FacebookPostLinks.LinkProvenanceTracker(nowMs = { tick += 1; tick })
        assertTrue(tracker.add("https://m.facebook.com/a/posts/1", "M1"))
        assertFalse(tracker.add("https://m.facebook.com/a/posts/1", "M3")) // already found by M1
        assertEquals(
            2,
            tracker.addAll(
                listOf("https://m.facebook.com/a/posts/1", "https://m.facebook.com/a/posts/2", "https://m.facebook.com/photo.php?fbid=9"),
                "M3"
            )
        )
        assertEquals("M1", tracker.methodOf("https://m.facebook.com/a/posts/1"))
        assertEquals("M3", tracker.methodOf("https://m.facebook.com/a/posts/2"))
        assertEquals(
            listOf(
                "https://m.facebook.com/a/posts/1",
                "https://m.facebook.com/a/posts/2",
                "https://m.facebook.com/photo.php?fbid=9"
            ),
            tracker.links()
        )

        // Absorb: the other harvest's NEW links append in its order;
        // links both found keep THIS tracker's (earlier) attribution.
        val other = FacebookPostLinks.LinkProvenanceTracker(nowMs = { 5_000L })
        other.add("https://m.facebook.com/a/posts/2", "M5")
        other.add("https://m.facebook.com/a/videos/7", "M5")
        tracker.absorb(other)
        assertEquals("M3", tracker.methodOf("https://m.facebook.com/a/posts/2"))
        assertEquals("M5", tracker.methodOf("https://m.facebook.com/a/videos/7"))
        assertEquals("https://m.facebook.com/a/videos/7", tracker.links().last())
        assertEquals(4, tracker.size)
    }

    @Test
    fun `object type comes from the url shape`() {
        assertEquals("post", FacebookPostLinks.postObjectType("https://m.facebook.com/a.page/posts/123"))
        assertEquals("photo", FacebookPostLinks.postObjectType("https://m.facebook.com/photo.php?fbid=123"))
        assertEquals("photo", FacebookPostLinks.postObjectType("https://m.facebook.com/photo/?fbid=123"))
        assertEquals("video", FacebookPostLinks.postObjectType("https://m.facebook.com/a.page/videos/123"))
        assertEquals("story", FacebookPostLinks.postObjectType("https://m.facebook.com/story.php?story_fbid=1&id=2"))
        assertEquals("story", FacebookPostLinks.postObjectType("https://m.facebook.com/permalink.php?story_fbid=1&id=2"))
        assertEquals("watch", FacebookPostLinks.postObjectType("https://m.facebook.com/watch/?v=123"))
    }

    // ------------------------------------------------------------------
    // Item 4 — artifact builders
    // ------------------------------------------------------------------

    private val fixedNow = 1_730_000_000_000L

    private fun sampleResults(): List<FacebookProfileResult> = listOf(
        FacebookProfileResult(
            profile = "https://www.facebook.com/a.page",
            normalizedUrl = "https://m.facebook.com/a.page",
            status = FacebookProfileStatus.OK,
            links = listOf(
                "https://m.facebook.com/a.page/posts/111",
                "https://m.facebook.com/photo.php?fbid=222"
            ),
            linkAttribution = mapOf(
                "https://m.facebook.com/a.page/posts/111" to FacebookLinkAttribution("M1", fixedNow),
                "https://m.facebook.com/photo.php?fbid=222" to FacebookLinkAttribution("M3", fixedNow)
            )
        ),
        FacebookProfileResult(
            profile = "https://www.facebook.com/b.page",
            normalizedUrl = "https://m.facebook.com/b.page",
            status = FacebookProfileStatus.PARTIAL,
            links = listOf(
                // Same post as source 1's first link — run-wide duplicate.
                "https://m.facebook.com/a.page/posts/111",
                "https://m.facebook.com/b.page/videos/333"
            ),
            note = "Only 2 of 5 posts were found on this profile.",
            linkAttribution = mapOf(
                "https://m.facebook.com/a.page/posts/111" to FacebookLinkAttribution("M2", fixedNow),
                "https://m.facebook.com/b.page/videos/333" to FacebookLinkAttribution("M5", fixedNow)
            )
        ),
        FacebookProfileResult(
            profile = "https://www.facebook.com/c.page",
            normalizedUrl = "https://m.facebook.com/c.page",
            status = FacebookProfileStatus.PROFILE_LOCKED,
            note = "This profile is locked / private — posts are not visible."
        )
    )

    private fun sampleConfig() = FacebookRunArtifacts.RunConfig(
        sources = listOf(
            "https://www.facebook.com/a.page",
            "https://www.facebook.com/b.page",
            "https://www.facebook.com/c.page"
        ),
        perSourceLimit = 25,
        mode = FacebookExtractionMode.M3_ONLY,
        startedAtMs = fixedNow,
        finishedAtMs = fixedNow + 60_000L,
        invalidSourceLines = 2,
        duplicateSourceLines = 1
    )

    @Test
    fun `results txt dedupes run-wide in first-appearance order`() {
        val txt = FacebookRunArtifacts.buildResultsTxt(sampleResults())
        assertEquals(
            listOf(
                "https://m.facebook.com/a.page/posts/111",
                "https://m.facebook.com/photo.php?fbid=222",
                "https://m.facebook.com/b.page/videos/333"
            ),
            txt.lines().filter { it.isNotBlank() }
        )
    }

    @Test
    fun `csv escaping follows RFC 4180`() {
        assertEquals("plain", FacebookRunArtifacts.escapeCsvField("plain"))
        assertEquals("\"a,b\"", FacebookRunArtifacts.escapeCsvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", FacebookRunArtifacts.escapeCsvField("say \"hi\""))
        assertEquals("\"a\nb\"", FacebookRunArtifacts.escapeCsvField("a\nb"))
        assertEquals("a,\"b,c\"", FacebookRunArtifacts.csvRow(listOf("a", "b,c")))
    }

    @Test
    fun `results csv carries header, provenance, type and honest validation`() {
        val csv = FacebookRunArtifacts.buildResultsCsv(sampleResults(), sampleConfig())
        val lines = csv.split("\r\n").filter { it.isNotBlank() }
        assertEquals(
            "Source #,Source URL,Post URL,Object Type,Method,Discovery Timestamp,Validation,Note",
            lines.first()
        )
        // 3 sources produce 2 + 2 + 0 = 4 data rows.
        assertEquals(5, lines.size)
        val photoRow = lines.first { it.contains("photo.php?fbid=222") }
        assertTrue(photoRow.startsWith("1,https://m.facebook.com/a.page,"))
        assertTrue(photoRow.contains(",photo,M3,"))
        assertTrue(photoRow.contains("syntactically valid (live accessibility not checked)"))
        val videoRow = lines.first { it.contains("/videos/333") }
        assertTrue(videoRow.contains(",video,M5,"))
        // The note with commas inside must be CSV-escaped as one field.
        val partialRow = lines.first { it.contains("posts/111") && it.startsWith("2,") }
        assertTrue(partialRow.contains("\"Only 2 of 5 posts were found on this profile.\"") ||
            partialRow.endsWith("Only 2 of 5 posts were found on this profile."))
    }

    @Test
    fun `results json records config, per-source blocks and summary`() {
        val json = JSONObject(FacebookRunArtifacts.buildResultsJson(sampleResults(), sampleConfig()))
        val config = json.getJSONObject("config")
        assertEquals(25, config.getInt("perSourceLimit"))
        assertEquals("m3", config.getString("method"))
        assertEquals(3, config.getJSONArray("sources").length())
        val sources = json.getJSONArray("sources")
        assertEquals("Link 1", sources.getJSONObject(0).getString("label"))
        assertEquals("OK", sources.getJSONObject(0).getString("status"))
        val firstLink = sources.getJSONObject(0).getJSONArray("links").getJSONObject(0)
        assertEquals("post", firstLink.getString("type"))
        assertEquals("M1", firstLink.getString("method"))
        assertEquals("syntactically valid", firstLink.getString("validation"))
        val summary = json.getJSONObject("summary")
        assertEquals(4, summary.getInt("discoveredCandidates"))
        assertEquals(3, summary.getInt("uniqueResults"))
        assertEquals(1, summary.getInt("duplicatesRemoved"))
    }

    @Test
    fun `summary json counts statuses, invalid lines and per-method yields`() {
        val json = JSONObject(FacebookRunArtifacts.buildSummaryJson(sampleResults(), sampleConfig()))
        assertEquals(4, json.getInt("discoveredCandidates"))
        assertEquals(3, json.getInt("uniqueResults"))
        assertEquals(1, json.getInt("duplicatesRemoved"))
        assertEquals(2, json.getInt("invalidCandidates"))
        assertEquals(1, json.getInt("duplicateSourceLinesRemoved"))
        val statuses = json.getJSONObject("statusCounts")
        assertEquals(1, statuses.getInt("OK"))
        assertEquals(1, statuses.getInt("PARTIAL"))
        assertEquals(1, statuses.getInt("PROFILE_LOCKED"))
        // Yields are over the run-wide UNIQUE set, credited to the first
        // finder: posts/111 → M1 (source 1), photo → M3, videos → M5.
        val yields = json.getJSONObject("methodYields")
        assertEquals(1, yields.getInt("M1"))
        assertEquals(1, yields.getInt("M3"))
        assertEquals(1, yields.getInt("M5"))
        assertFalse(yields.has("M2"))
        assertTrue(json.getString("validationScope").contains("syntax only"))
    }
}
