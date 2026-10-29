package com.example

import com.example.nova.agent.ApprovalClass
import com.example.nova.agent.ApprovalPolicy
import com.example.nova.trained.FacebookPageClass
import com.example.nova.trained.FacebookPageSignals
import com.example.nova.trained.FacebookPostLinks
import com.example.nova.trained.FacebookProfileResult
import com.example.nova.trained.FacebookProfileStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/**
 * Trained Task #1 — the Facebook Post Links Collector's pure core.
 *
 * Two evidence groups:
 *  1. Extraction: realistic m.facebook.com anchor payloads in → exact
 *     normalized permalinks out (junk rejected, tracking stripped,
 *     comment links deduped onto their post, first-seen order kept).
 *  2. The trained classifier: ONE FIXTURE PER STATUS of the taxonomy
 *     (OK, PARTIAL, NO_POSTS, PROFILE_LOCKED, PROFILE_UNAVAILABLE,
 *     OPEN_FAILED, FB_ERROR ×2, LOGIN_REQUIRED) plus the precedence
 *     rules between page classes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FacebookPostLinksTest {

    // ------------------------------------------------------------------
    // Extraction fixtures — realistic m.facebook.com anchors
    // ------------------------------------------------------------------

    /** A realistic mixed anchor harvest from a profile page, in DOM order. */
    private val profileAnchors = listOf(
        // nav / chrome junk
        "https://m.facebook.com/home.php?ref=bookmarks",
        "https://m.facebook.com/notifications.php",
        "https://m.facebook.com/some.page/friends",
        "https://m.facebook.com/search/top/?q=latest%20posts",
        "https://m.facebook.com/hashtag/islam",
        // post 1 — standard timeline post, heavy tracking decoration
        "https://m.facebook.com/some.page/posts/123456789012345?__cft__[0]=AZXyz123&__tn__=%2CO%2CP-R",
        // a comment anchor INTO post 2 (must dedupe onto the post itself)
        "https://m.facebook.com/some.page/posts/987654321098765?comment_id=555000111&__cft__[0]=AZabc",
        // post 2 — the plain permalink, from the desktop host
        "https://www.facebook.com/some.page/posts/987654321098765?ref=share",
        // post 3 — classic story permalink
        "https://m.facebook.com/story.php?story_fbid=111222333444555&id=100012345678&__tn__=-R",
        // post 4 — classic permalink endpoint
        "https://m.facebook.com/permalink.php?story_fbid=666777888999000&id=100012345678",
        // post 5 — a photo post (album views are NOT this shape)
        "https://m.facebook.com/photo.php?fbid=777888999000111&set=a.100200300&type=3",
        // post 6 — a video post on the profile
        "https://m.facebook.com/some.page/videos/555444333222111?__tn__=-R",
        // post 7 — a watch video post
        "https://www.facebook.com/watch/?v=999888777666555&ref=watch_permalink",
        // junk that must NOT survive
        "https://m.facebook.com/some.page/photos/a.100200300/777888999000111/",
        "https://www.facebook.com/sharer/sharer.php?u=https%3A%2F%2Fm.facebook.com%2Fsome.page",
        "https://m.facebook.com/reel/123456789000111",
        "https://m.facebook.com/login.php?next=https%3A%2F%2Fm.facebook.com%2Fsome.page",
        "https://m.facebook.com/some.page",
        "https://example.com/some.page/posts/123456",
        "https://m.facebook.com/photo.php?set=a.100200300",
        "https://m.facebook.com/story.php?id=100012345678"
    )

    private val expectedLinks = listOf(
        "https://m.facebook.com/some.page/posts/123456789012345",
        "https://m.facebook.com/some.page/posts/987654321098765",
        "https://m.facebook.com/story.php?story_fbid=111222333444555&id=100012345678",
        "https://m.facebook.com/permalink.php?story_fbid=666777888999000&id=100012345678",
        "https://m.facebook.com/photo.php?fbid=777888999000111",
        "https://m.facebook.com/some.page/videos/555444333222111",
        "https://m.facebook.com/watch/?v=999888777666555"
    )

    @Test
    fun `extracts only post permalinks, normalized and in page order`() {
        assertEquals(expectedLinks, FacebookPostLinks.extractPostLinks(profileAnchors))
    }

    @Test
    fun `extraction from the JSON anchor payload matches the list form`() {
        val payload = buildString {
            append("[")
            append(profileAnchors.joinToString(",") { "\"$it\"" })
            append("]")
        }
        assertEquals(expectedLinks, FacebookPostLinks.extractPostLinksFromJson(payload))
        assertEquals(emptyList<String>(), FacebookPostLinks.extractPostLinksFromJson("not json"))
        assertEquals(emptyList<String>(), FacebookPostLinks.extractPostLinksFromJson(null))
    }

    @Test
    fun `dedupe preserves first-seen order across decorated duplicates`() {
        val hrefs = listOf(
            "https://m.facebook.com/a.page/posts/222?__tn__=x",
            "https://m.facebook.com/a.page/posts/111",
            "https://www.facebook.com/a.page/posts/222?ref=share"
        )
        assertEquals(
            listOf(
                "https://m.facebook.com/a.page/posts/222",
                "https://m.facebook.com/a.page/posts/111"
            ),
            FacebookPostLinks.extractPostLinks(hrefs)
        )
    }

    @Test
    fun `group posts count as post permalinks`() {
        assertEquals(
            listOf("https://m.facebook.com/groups/mygroup/posts/1122334455"),
            FacebookPostLinks.extractPostLinks(
                listOf("https://m.facebook.com/groups/mygroup/posts/1122334455?__tn__=-R")
            )
        )
    }

    // ------------------------------------------------------------------
    // Profile URL normalization
    // ------------------------------------------------------------------

    @Test
    fun `profile urls normalize to the mobile host`() {
        assertEquals(
            "https://m.facebook.com/DawateIslamiEnglish",
            FacebookPostLinks.normalizeProfileUrl("https://www.facebook.com/DawateIslamiEnglish/?ref=br_rs")
        )
        assertEquals(
            "https://m.facebook.com/some.page",
            FacebookPostLinks.normalizeProfileUrl("facebook.com/some.page")
        )
        assertEquals(
            "https://m.facebook.com/some.page",
            FacebookPostLinks.normalizeProfileUrl("https://m.facebook.com/some.page/")
        )
        assertEquals(
            "https://m.facebook.com/profile.php?id=100012345678",
            FacebookPostLinks.normalizeProfileUrl("https://www.facebook.com/profile.php?id=100012345678&sk=about")
        )
        assertEquals(
            "https://m.facebook.com/dawateislami",
            FacebookPostLinks.normalizeProfileUrl("dawateislami")
        )
        assertEquals("", FacebookPostLinks.normalizeProfileUrl("   "))
    }

    @Test
    fun `non-facebook input is returned untouched so the collector can flag it`() {
        assertEquals(
            "https://example.com/someone",
            FacebookPostLinks.normalizeProfileUrl("https://example.com/someone")
        )
        assertFalse(FacebookPostLinks.isFacebookUrl("https://example.com/someone"))
        assertTrue(FacebookPostLinks.isFacebookUrl("https://m.facebook.com/some.page"))
    }

    // ------------------------------------------------------------------
    // Classifier fixtures — ONE PER STATUS
    // ------------------------------------------------------------------

    private val contentSignals = FacebookPageSignals(
        finalUrl = "https://m.facebook.com/some.page",
        title = "Some Page",
        hasLoginForm = false,
        // The rendered-feed fixture: the posts area IS in the DOM.
        hasPostsArea = true,
        pageText = "Some Page · 12K followers · Posts · About · Photos. Latest updates from the page."
    )

    @Test
    fun `fixture OK - content page with all requested links`() {
        assertEquals(FacebookPageClass.CONTENT, FacebookPostLinks.classifyPage(contentSignals))
        assertEquals(
            FacebookProfileStatus.OK,
            FacebookPostLinks.resolveStatus(contentSignals, linkCount = 5, requestedCount = 5)
        )
    }

    @Test
    fun `fixture PARTIAL - content page with fewer links than requested`() {
        assertEquals(
            FacebookProfileStatus.PARTIAL,
            FacebookPostLinks.resolveStatus(contentSignals, linkCount = 3, requestedCount = 5)
        )
    }

    @Test
    fun `fixture NO_POSTS - rendered posts area with zero permalinks`() {
        // NO_POSTS requires POSITIVE evidence the posts area rendered
        // (contentSignals carries hasPostsArea = true).
        assertEquals(
            FacebookProfileStatus.NO_POSTS,
            FacebookPostLinks.resolveStatus(contentSignals, linkCount = 0, requestedCount = 5)
        )
    }

    @Test
    fun `fixture LOGIN_REQUIRED - login wall by url and form`() {
        val wall = FacebookPageSignals(
            finalUrl = "https://m.facebook.com/login.php?next=https%3A%2F%2Fm.facebook.com%2Fsome.page",
            title = "Log in to Facebook",
            hasLoginForm = true,
            pageText = "Log in to Facebook. Email or phone number. Password. Log In. Forgotten password?"
        )
        assertEquals(FacebookPageClass.LOGIN_WALL, FacebookPostLinks.classifyPage(wall))
        assertEquals(
            FacebookProfileStatus.LOGIN_REQUIRED,
            FacebookPostLinks.resolveStatus(wall, linkCount = 0, requestedCount = 5)
        )
    }

    @Test
    fun `fixture LOGIN_REQUIRED - checkpoint url alone is a wall`() {
        val checkpoint = contentSignals.copy(
            finalUrl = "https://m.facebook.com/checkpoint/150109282352/?next=https%3A%2F%2Fm.facebook.com%2F"
        )
        assertEquals(FacebookPageClass.LOGIN_WALL, FacebookPostLinks.classifyPage(checkpoint))
    }

    @Test
    fun `fixture PROFILE_LOCKED - locked profile text`() {
        val locked = contentSignals.copy(
            pageText = "Some Page. This profile is locked. Only their friends can see what they share on their profile."
        )
        assertEquals(FacebookPageClass.PROFILE_LOCKED, FacebookPostLinks.classifyPage(locked))
        assertEquals(
            FacebookProfileStatus.PROFILE_LOCKED,
            FacebookPostLinks.resolveStatus(locked, linkCount = 0, requestedCount = 5)
        )
    }

    @Test
    fun `fixture PROFILE_UNAVAILABLE - content isn't available text`() {
        val gone = contentSignals.copy(
            title = "Some Page",
            pageText = "This content isn't available right now. When this happens, it's usually because the owner only shared it with a small group of people, changed who can see it or it's been deleted."
        )
        assertEquals(FacebookPageClass.PROFILE_UNAVAILABLE, FacebookPostLinks.classifyPage(gone))
        assertEquals(
            FacebookProfileStatus.PROFILE_UNAVAILABLE,
            FacebookPostLinks.resolveStatus(gone, linkCount = 0, requestedCount = 5)
        )
    }

    @Test
    fun `fixture OPEN_FAILED - load failure flag wins over page content`() {
        assertEquals(
            FacebookProfileStatus.OPEN_FAILED,
            FacebookPostLinks.resolveStatus(contentSignals, linkCount = 0, requestedCount = 5, openFailed = true)
        )
    }

    @Test
    fun `fixture FB_ERROR - facebook error page text`() {
        val err = contentSignals.copy(
            pageText = "Something's gone wrong. We're working on getting this fixed as soon as we can. Go back."
        )
        assertEquals(FacebookPageClass.FB_ERROR_PAGE, FacebookPostLinks.classifyPage(err))
        assertEquals(
            FacebookProfileStatus.FB_ERROR,
            FacebookPostLinks.resolveStatus(err, linkCount = 0, requestedCount = 5)
        )
    }

    @Test
    fun `fixture FB_ERROR - dead renderer resolves to FB_ERROR`() {
        val crashed = contentSignals.copy(rendererGone = true)
        assertEquals(
            FacebookProfileStatus.FB_ERROR,
            FacebookPostLinks.resolveStatus(crashed, linkCount = 0, requestedCount = 5)
        )
    }

    // ------------------------------------------------------------------
    // Classifier precedence rules
    // ------------------------------------------------------------------

    @Test
    fun `login wall wins over content signals`() {
        val mixed = contentSignals.copy(hasLoginForm = true)
        assertEquals(FacebookPageClass.LOGIN_WALL, FacebookPostLinks.classifyPage(mixed))
    }

    @Test
    fun `fb error page wins over unavailable wording`() {
        val mixed = contentSignals.copy(
            pageText = "Something went wrong. This content isn't available right now."
        )
        assertEquals(FacebookPageClass.FB_ERROR_PAGE, FacebookPostLinks.classifyPage(mixed))
    }

    @Test
    fun `locked wins over unavailable wording`() {
        val mixed = contentSignals.copy(
            pageText = "This profile is locked. This content isn't available to you."
        )
        assertEquals(FacebookPageClass.PROFILE_LOCKED, FacebookPostLinks.classifyPage(mixed))
    }

    @Test
    fun `probe json parses into signals`() {
        val probe = """{"url":"https://m.facebook.com/login.php","title":"Log in","hasPassword":true,"hasLoginForm":false,"text":"Log in to Facebook"}"""
        val signals = FacebookPostLinks.signalsFromProbeJson(probe)
        assertTrue(signals != null)
        assertEquals("https://m.facebook.com/login.php", signals!!.finalUrl)
        assertTrue(signals.hasLoginForm) // password field alone implies the form
        assertEquals(FacebookPageClass.LOGIN_WALL, FacebookPostLinks.classifyPage(signals))
        assertEquals(null, FacebookPostLinks.signalsFromProbeJson("garbage"))
    }

    // ------------------------------------------------------------------
    // Never-gate + presentation
    // ------------------------------------------------------------------

    @Test
    fun `the collector tool is never approval-gated`() {
        assertFalse(
            ApprovalPolicy.requiresApproval("collect_facebook_post_links", JSONObject("{}"), null)
        )
        assertEquals(
            ApprovalClass.READ_ONLY,
            ApprovalPolicy.classify("collect_facebook_post_links", JSONObject("{}"), null)
        )
    }

    @Test
    fun `summary groups counts by status and markdown carries status lines`() {
        val results = listOf(
            FacebookProfileResult(
                profile = "https://www.facebook.com/a.page",
                normalizedUrl = "https://m.facebook.com/a.page",
                status = FacebookProfileStatus.OK,
                links = expectedLinks.take(5)
            ),
            FacebookProfileResult(
                profile = "https://www.facebook.com/b.page",
                normalizedUrl = "https://m.facebook.com/b.page",
                status = FacebookProfileStatus.PARTIAL,
                links = expectedLinks.take(2),
                note = "Only 2 of 5 posts were found on this profile."
            ),
            FacebookProfileResult(
                profile = "https://www.facebook.com/c.page",
                normalizedUrl = "https://m.facebook.com/c.page",
                status = FacebookProfileStatus.PROFILE_LOCKED
            )
        )
        assertEquals(
            "3 profiles: 1 OK, 1 PARTIAL, 1 PROFILE_LOCKED",
            FacebookPostLinks.statusSummaryLine(results)
        )
        val md = FacebookPostLinks.formatResultsMarkdown(results, 5, Date(0))
        assertTrue(md.contains("## https://m.facebook.com/a.page — OK (5 links)"))
        assertTrue(md.contains("## https://m.facebook.com/c.page — PROFILE_LOCKED (0 links)"))
        assertTrue(md.contains("_No links collected._"))
        assertTrue(md.contains("Summary: 3 profiles: 1 OK, 1 PARTIAL, 1 PROFILE_LOCKED"))
    }

    // ------------------------------------------------------------------
    // v2 patch — logged-out gate, zero-link rules, probe keys
    // ------------------------------------------------------------------

    @Test
    fun `fixture LOGIN_REQUIRED - logged-out nag gate without any password field`() {
        // The dawateislami.net failure shape: profile URL loads, no login
        // FORM (no password field), but the page wears the login nag.
        val nag = FacebookPageSignals(
            finalUrl = "https://m.facebook.com/dawateislami.net",
            title = "DawateIslami",
            hasLoginForm = false,
            hasLoginGate = true,
            pageText = "DawateIslami · 9.8M followers. See more on Facebook. Log in to see more posts."
        )
        assertEquals(FacebookPageClass.LOGIN_WALL, FacebookPostLinks.classifyPage(nag))
        assertEquals(
            FacebookProfileStatus.LOGIN_REQUIRED,
            FacebookPostLinks.resolveStatus(nag, linkCount = 0, requestedCount = 5)
        )
    }

    @Test
    fun `fixture LOGIN_REQUIRED - nag phrases alone classify as a wall`() {
        for (phrase in listOf("See more on Facebook", "Log in or sign up to view this page")) {
            val signals = contentSignals.copy(
                hasPostsArea = false,
                pageText = "Some Page. $phrase"
            )
            assertEquals(
                "phrase: $phrase",
                FacebookPageClass.LOGIN_WALL,
                FacebookPostLinks.classifyPage(signals)
            )
        }
    }

    @Test
    fun `zero links without posts-area evidence is never NO_POSTS`() {
        val unseen = contentSignals.copy(hasPostsArea = false)
        assertEquals(
            FacebookProfileStatus.LOGIN_REQUIRED,
            FacebookPostLinks.resolveStatus(unseen, linkCount = 0, requestedCount = 5)
        )
        // And when the harvest positively rendered the posts area, the
        // same zero resolves NO_POSTS.
        assertEquals(
            FacebookProfileStatus.NO_POSTS,
            FacebookPostLinks.finalStatus(
                FacebookPageClass.CONTENT, linkCount = 0, requestedCount = 5,
                postsAreaRendered = true
            )
        )
    }

    @Test
    fun `probe json parses the gate and posts-area keys`() {
        val json = """{"url":"https://m.facebook.com/x","title":"X","hasPassword":false,
            "hasLoginForm":false,"hasLoginGate":true,"hasPostsArea":true,"text":"hi"}"""
        val signals = FacebookPostLinks.signalsFromProbeJson(json, rendererGone = false)
        assertTrue(signals != null)
        assertTrue(signals!!.hasLoginGate)
        assertTrue(signals.hasPostsArea)
    }

    // ------------------------------------------------------------------
    // v2 patch — modern href shapes + probe sample diagnostics
    // ------------------------------------------------------------------

    @Test
    fun `photo slash variant and pfbid page posts are accepted`() {
        val links = FacebookPostLinks.extractPostLinks(
            listOf(
                "https://m.facebook.com/photo/?fbid=102233445566778&set=pcb.998877",
                "https://m.facebook.com/dawateislami.net/posts/pfbid02AbCdEfGhIjKlMnOpQrStUv",
                "https://m.facebook.com/dawateislami.net/videos/998877665544332"
            )
        )
        assertEquals(
            listOf(
                "https://m.facebook.com/photo/?fbid=102233445566778",
                "https://m.facebook.com/dawateislami.net/posts/pfbid02AbCdEfGhIjKlMnOpQrStUv",
                "https://m.facebook.com/dawateislami.net/videos/998877665544332"
            ),
            links
        )
    }

    @Test
    fun `story php accepts parameters in any order`() {
        val links = FacebookPostLinks.extractPostLinks(
            listOf("https://m.facebook.com/story.php?id=100063693365280&story_fbid=2938475610293847&__cft__[0]=AZX")
        )
        assertEquals(
            listOf("https://m.facebook.com/story.php?story_fbid=2938475610293847&id=100063693365280"),
            links
        )
    }

    @Test
    fun `sample hrefs for diagnostics prefer post-like links, strip tracking, cap at ten`() {
        val hrefs = listOf(
            "https://m.facebook.com/dawateislami.net",
            "https://m.facebook.com/dawateislami.net/posts/pfbid02XyZ?__cft__[0]=AZa&__tn__=%2CO",
            "https://m.facebook.com/photo.php?fbid=102233445566778&__cft__[0]=AZb",
            "https://m.facebook.com/some.nav/link",
            "https://m.facebook.com/watch/?v=1234567890&ref=share"
        )
        val sample = FacebookPostLinks.sampleHrefsForDiagnostics(hrefs)
        assertTrue(sample.size <= 10)
        assertTrue(sample.first().contains("/posts/pfbid02XyZ"))
        assertTrue(sample.any { it.contains("photo.php?fbid=102233445566778") && !it.contains("__cft__") })
        assertTrue(sample.all { !it.contains("__tn__") && !it.contains("ref=") })
    }

    // ------------------------------------------------------------------
    // v2 patch — ladder sequencing + M2/M5 anchor hunting (pure)
    // ------------------------------------------------------------------

    @Test
    fun `ladder picks the largest yield and the earliest on ties`() {
        val yields = listOf(
            FacebookPostLinks.MethodYield("M1 mobile", listOf("a", "b")),
            FacebookPostLinks.MethodYield("M2 posts section", listOf("a", "b", "c", "d", "e")),
            FacebookPostLinks.MethodYield("M1+M3 combined", listOf("a", "b", "c", "d", "e"))
        )
        assertEquals("M2 posts section", FacebookPostLinks.selectBestYield(yields)?.method)
        assertTrue(FacebookPostLinks.shouldEscalate(bestLinkCount = 2, requestedCount = 5, budgetLeftMs = 1000))
        assertFalse(FacebookPostLinks.shouldEscalate(bestLinkCount = 5, requestedCount = 5, budgetLeftMs = 1000))
        assertFalse(FacebookPostLinks.shouldEscalate(bestLinkCount = 2, requestedCount = 5, budgetLeftMs = 0))
    }

    @Test
    fun `posts section anchor is found in the page and permalinks are not sections`() {
        val hrefs = listOf(
            "https://m.facebook.com/dawateislami.net/posts/pfbid02AbCdEfGhIjKlMnOpQrStUv",
            "https://m.facebook.com/dawateislami.net/about",
            "https://m.facebook.com/dawateislami.net/posts/?__cft__[0]=AZx",
            "https://m.facebook.com/other.page/posts/"
        )
        assertEquals(
            "https://m.facebook.com/dawateislami.net/posts/?__cft__[0]=AZx",
            FacebookPostLinks.findPostsSectionAnchor(hrefs, "https://m.facebook.com/dawateislami.net")
        )
        assertEquals(
            "https://m.facebook.com/some.page/?sk=posts",
            FacebookPostLinks.findPostsSectionAnchor(
                listOf("https://m.facebook.com/some.page/?sk=posts"),
                "https://m.facebook.com/some.page"
            )
        )
        assertNull(
            FacebookPostLinks.findPostsSectionAnchor(
                listOf("https://m.facebook.com/other.page/posts/"),
                "https://m.facebook.com/dawateislami.net"
            )
        )
    }

    @Test
    fun `see more pagination href is found by anchor text`() {
        val anchors = listOf(
            "https://m.facebook.com/dawateislami.net" to "Home",
            "https://m.facebook.com/stories.php?aftercursor=abc123" to "See more stories"
        )
        assertEquals(
            "https://m.facebook.com/stories.php?aftercursor=abc123",
            FacebookPostLinks.findSeeMoreHref(anchors)
        )
        assertNull(FacebookPostLinks.findSeeMoreHref(listOf("https://example.com/x" to "See more")))
    }

    @Test
    fun `host variants swap only the facebook host`() {
        assertEquals(
            "https://www.facebook.com/dawateislami.net",
            FacebookPostLinks.hostVariant("https://m.facebook.com/dawateislami.net", "www.facebook.com")
        )
        assertEquals(
            "https://mbasic.facebook.com/profile.php?id=100012345678",
            FacebookPostLinks.hostVariant(
                "https://m.facebook.com/profile.php?id=100012345678", "mbasic.facebook.com"
            )
        )
    }

    // ------------------------------------------------------------------
    // v2 patch — M3 page-source mining (pure)
    // ------------------------------------------------------------------

    @Test
    fun `mining reads a realistic graphql payload and rejects junk ids`() {
        // Segment A: a Relay-style story whose canonical link is the
        // permalink_url (escaped). Its story_fbid has NO digit owner id
        // within reach (the base64 story id is not an owner), so no
        // story.php link may be minted from it.
        val segmentA = """
            {"node":{"__typename":"Story","id":"UzpfSTExMDA2MzY5MzM2NTI4MDoxMjM0",
            "story_fbid":"pfbid02QwErTyUiOpAsDfGhJkLzXcVbNm123",
            "actor_id":100063693365280,"target_id":100063693365280,
            "permalink_url":"https:\/\/www.facebook.com\/dawateislami.net\/posts\/pfbid02QwErTyUiOpAsDfGhJkLzXcVbNm123",
            "message":{"text":"A post"}}}
        """.trimIndent()
        // Padding so segment B's ids sit far outside segment A's
        // story_fbid pairing window.
        val padding = " ".repeat(600)
        // Segment B: an UNPAIRED story_fbid (a long note pushes every
        // other id far outside its ±200-char pairing window — the miner
        // must leave it unlinked rather than guess an owner), then a
        // second story known only by its top_level_post_id, with a
        // comment (whose id must NEVER become a link) and a fan user
        // id (also never a link).
        val segmentB = """
            {"node":{"story_fbid":"555444333222111","note":"an embedded story reference with no
            owner id anywhere near it — this long text pushes every other id in the payload far
            outside the pairing window so the miner must leave this story unlinked"}},
            {"node":{"__typename":"Story","top_level_post_id":"3387654321098765",
            "feedback":{"comments":{"edges":[{"node":{"id":"3387654321098765_1122334455",
            "actor_id":100099887766,"body":"MashaAllah"}}]}}},
            {"node":{"__typename":"User","id":"77700111222333","name":"A Fan"}},
            {"node":{"__typename":"Photo","fbid":102233445566778}},
            {"node":{"__typename":"Video","video_id":998877665544332}}
        """.trimIndent()
        val mined = FacebookPostLinks.mineLinksFromPageSource(
            segmentA + padding + segmentB, "https://m.facebook.com/dawateislami.net"
        )
        assertEquals(
            listOf(
                "https://m.facebook.com/dawateislami.net/posts/pfbid02QwErTyUiOpAsDfGhJkLzXcVbNm123",
                "https://m.facebook.com/dawateislami.net/posts/3387654321098765",
                "https://m.facebook.com/dawateislami.net/videos/998877665544332",
                "https://m.facebook.com/photo.php?fbid=102233445566778"
            ),
            mined
        )
    }

    @Test
    fun `mining pairs story fbid with a neighbouring owner id only`() {
        val paired = """{"story_fbid":"2938475610293847","id":"100063693365280","x":1}"""
        assertEquals(
            listOf("https://m.facebook.com/story.php?story_fbid=2938475610293847&id=100063693365280"),
            FacebookPostLinks.mineLinksFromPageSource(paired, "https://m.facebook.com/dawateislami.net")
        )
        // Unicode-escaped photo URL in the payload.
        val escaped = """{"url":"https:\u002F\u002Fm.facebook.com\u002Fphoto\u002F?fbid=777888999000111\u0026set=a.123"}"""
        assertEquals(
            listOf("https://m.facebook.com/photo/?fbid=777888999000111"),
            FacebookPostLinks.mineLinksFromPageSource(escaped, "https://m.facebook.com/dawateislami.net")
        )
        // Pure junk: counts and short ids mint nothing.
        val junk = """{"id":123,"count":555555555555,"fbid":42,"video_id":7}"""
        assertEquals(
            emptyList<String>(),
            FacebookPostLinks.mineLinksFromPageSource(junk, "https://m.facebook.com/dawateislami.net")
        )
    }
}
