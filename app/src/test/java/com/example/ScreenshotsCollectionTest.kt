package com.example

import com.example.nova.trained.FacebookExtractionMode
import com.example.nova.trained.FacebookPageClass
import com.example.nova.trained.FacebookProfileStatus
import com.example.nova.trained.ScreenshotLineKind
import com.example.nova.trained.ScreenshotShot
import com.example.nova.trained.ScreenshotSourceResult
import com.example.nova.trained.ScreenshotsCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure tests for Trained Task #2 (Screenshots Collection): line
 * classification (Facebook profile vs direct), file naming/slug,
 * per-source shot accounting (OK/PARTIAL), index/summary builders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenshotsCollectionTest {

    // ---------------------------------------------------------------
    // Classification
    // ---------------------------------------------------------------

    @Test
    fun facebookProfileLinesClassifyAsProfiles() {
        assertEquals(
            ScreenshotLineKind.FACEBOOK_PROFILE,
            ScreenshotsCollection.classifyLine("https://www.facebook.com/dawateislami.net")
        )
        assertEquals(
            ScreenshotLineKind.FACEBOOK_PROFILE,
            ScreenshotsCollection.classifyLine("https://m.facebook.com/some.page")
        )
        assertEquals(
            ScreenshotLineKind.FACEBOOK_PROFILE,
            ScreenshotsCollection.classifyLine("https://www.facebook.com/profile.php?id=100012345678")
        )
        assertEquals(
            ScreenshotLineKind.FACEBOOK_PROFILE,
            ScreenshotsCollection.classifyLine("facebook.com/SomePage/")
        )
    }

    @Test
    fun facebookPermalinksClassifyAsDirect() {
        // A Facebook POST link is not a profile — it gets one direct shot.
        assertEquals(
            ScreenshotLineKind.DIRECT_URL,
            ScreenshotsCollection.classifyLine("https://www.facebook.com/some.page/posts/1234567890")
        )
        assertEquals(
            ScreenshotLineKind.DIRECT_URL,
            ScreenshotsCollection.classifyLine("https://www.facebook.com/story.php?story_fbid=111&id=222")
        )
        assertEquals(
            ScreenshotLineKind.DIRECT_URL,
            ScreenshotsCollection.classifyLine("https://www.facebook.com/photo/?fbid=12345")
        )
        assertEquals(
            ScreenshotLineKind.DIRECT_URL,
            ScreenshotsCollection.classifyLine("https://www.facebook.com/watch/?v=98765")
        )
    }

    @Test
    fun nonFacebookLinesClassifyAsDirect() {
        assertEquals(
            ScreenshotLineKind.DIRECT_URL,
            ScreenshotsCollection.classifyLine("https://example.com/article")
        )
        assertEquals(
            ScreenshotLineKind.DIRECT_URL,
            ScreenshotsCollection.classifyLine("https://www.youtube.com/watch?v=abc123")
        )
    }

    // ---------------------------------------------------------------
    // Config clamp + naming (indices are 0-based, like the run loop)
    // ---------------------------------------------------------------

    @Test
    fun postsPerProfileClampsToOneToTenWithDefaultTwo() {
        assertEquals(2, ScreenshotsCollection.DEFAULT_POSTS_PER_PROFILE)
        assertEquals(1, ScreenshotsCollection.clampPostsPerProfile(0))
        assertEquals(1, ScreenshotsCollection.clampPostsPerProfile(-3))
        assertEquals(2, ScreenshotsCollection.clampPostsPerProfile(2))
        assertEquals(5, ScreenshotsCollection.clampPostsPerProfile(5))
        assertEquals(10, ScreenshotsCollection.clampPostsPerProfile(10))
        assertEquals(10, ScreenshotsCollection.clampPostsPerProfile(99))
    }

    @Test
    fun slugsAndFileNamesAreStable() {
        assertEquals("dawateislami-net", ScreenshotsCollection.slugFor("https://www.facebook.com/dawateislami.net"))
        assertEquals("some-page", ScreenshotsCollection.slugFor("https://www.facebook.com/Some.Page/"))
        assertEquals("story", ScreenshotsCollection.slugFor("https://example.com/news/story?x=1"))
        assertEquals("example-com", ScreenshotsCollection.slugFor("https://example.com/"))
        assertEquals("link-01-dawateislami-net", ScreenshotsCollection.sourceFolderName(0, "https://www.facebook.com/dawateislami.net"))
        assertEquals("link-12-example-com", ScreenshotsCollection.sourceFolderName(11, "https://example.com"))
        assertEquals("link-03-example-com.png", ScreenshotsCollection.directFileName(2, "https://example.com/"))
        assertEquals("profile.png", ScreenshotsCollection.PROFILE_FILE_NAME)
        assertEquals("post-1.png", ScreenshotsCollection.postFileName(0))
        assertEquals("post-2.png", ScreenshotsCollection.postFileName(1))
    }

    // ---------------------------------------------------------------
    // Shot accounting
    // ---------------------------------------------------------------

    @Test
    fun expectedShotsCountsProfilePlusDiscoveredPosts() {
        // Content outcomes: 1 profile shot + min(request, found) post shots.
        assertEquals(3, ScreenshotsCollection.expectedShotsForProfile(FacebookProfileStatus.PARTIAL, 2, 2))
        assertEquals(2, ScreenshotsCollection.expectedShotsForProfile(FacebookProfileStatus.PARTIAL, 1, 2))
        assertEquals(1, ScreenshotsCollection.expectedShotsForProfile(FacebookProfileStatus.NO_POSTS, 0, 2))
        // Terminal outcomes expect only the profile evidence shot.
        assertEquals(1, ScreenshotsCollection.expectedShotsForProfile(FacebookProfileStatus.PROFILE_LOCKED, 5, 2))
        assertEquals(1, ScreenshotsCollection.expectedShotsForProfile(FacebookProfileStatus.LOGIN_REQUIRED, 5, 2))
    }

    @Test
    fun fullProfileCaptureIsOk() {
        assertEquals(
            FacebookProfileStatus.OK,
            ScreenshotsCollection.resolveProfileStatus(
                discoveryStatus = FacebookProfileStatus.PARTIAL,
                profileShotTaken = true, postShotsTaken = 2, linksFound = 2, postsPerProfile = 2
            )
        )
        // Fewer posts exist than requested, but everything that exists
        // was captured -> OK (the shortfall is discovery's PARTIAL,
        // not a capture failure).
        assertEquals(
            FacebookProfileStatus.OK,
            ScreenshotsCollection.resolveProfileStatus(
                discoveryStatus = FacebookProfileStatus.PARTIAL,
                profileShotTaken = true, postShotsTaken = 1, linksFound = 1, postsPerProfile = 2
            )
        )
    }

    @Test
    fun missingOnePostShotIsPartial() {
        assertEquals(
            FacebookProfileStatus.PARTIAL,
            ScreenshotsCollection.resolveProfileStatus(
                discoveryStatus = FacebookProfileStatus.PARTIAL,
                profileShotTaken = true, postShotsTaken = 1, linksFound = 2, postsPerProfile = 2
            )
        )
        // Profile shot itself missing -> PARTIAL too.
        assertEquals(
            FacebookProfileStatus.PARTIAL,
            ScreenshotsCollection.resolveProfileStatus(
                discoveryStatus = FacebookProfileStatus.OK,
                profileShotTaken = false, postShotsTaken = 2, linksFound = 2, postsPerProfile = 2
            )
        )
    }

    @Test
    fun noPostsDiscoveryStaysNoPosts() {
        assertEquals(
            FacebookProfileStatus.NO_POSTS,
            ScreenshotsCollection.resolveProfileStatus(
                discoveryStatus = FacebookProfileStatus.NO_POSTS,
                profileShotTaken = true, postShotsTaken = 0, linksFound = 0, postsPerProfile = 2
            )
        )
    }

    @Test
    fun terminalDiscoveryStatusesPassThrough() {
        assertEquals(
            FacebookProfileStatus.PROFILE_LOCKED,
            ScreenshotsCollection.resolveProfileStatus(
                discoveryStatus = FacebookProfileStatus.PROFILE_LOCKED,
                profileShotTaken = true, postShotsTaken = 0, linksFound = 0, postsPerProfile = 2
            )
        )
        assertEquals(
            FacebookProfileStatus.LOGIN_REQUIRED,
            ScreenshotsCollection.resolveProfileStatus(
                discoveryStatus = FacebookProfileStatus.LOGIN_REQUIRED,
                profileShotTaken = true, postShotsTaken = 0, linksFound = 0, postsPerProfile = 2
            )
        )
        assertEquals(
            FacebookProfileStatus.OPEN_FAILED,
            ScreenshotsCollection.resolveProfileStatus(
                discoveryStatus = FacebookProfileStatus.OPEN_FAILED,
                profileShotTaken = false, postShotsTaken = 0, linksFound = 0, postsPerProfile = 2
            )
        )
    }

    @Test
    fun directStatusMapping() {
        assertEquals(
            FacebookProfileStatus.OK,
            ScreenshotsCollection.resolveDirectStatus(pageClass = null, openFailed = false, captured = true)
        )
        assertEquals(
            FacebookProfileStatus.LOGIN_REQUIRED,
            ScreenshotsCollection.resolveDirectStatus(
                pageClass = FacebookPageClass.LOGIN_WALL, openFailed = false, captured = false
            )
        )
        assertEquals(
            FacebookProfileStatus.PROFILE_LOCKED,
            ScreenshotsCollection.resolveDirectStatus(
                pageClass = FacebookPageClass.PROFILE_LOCKED, openFailed = false, captured = true
            )
        )
        assertEquals(
            FacebookProfileStatus.FB_ERROR,
            ScreenshotsCollection.resolveDirectStatus(
                pageClass = FacebookPageClass.FB_ERROR_PAGE, openFailed = false, captured = true
            )
        )
        assertEquals(
            FacebookProfileStatus.OPEN_FAILED,
            ScreenshotsCollection.resolveDirectStatus(pageClass = null, openFailed = true, captured = false)
        )
        assertEquals(
            FacebookProfileStatus.OPEN_FAILED,
            ScreenshotsCollection.resolveDirectStatus(pageClass = null, openFailed = false, captured = false)
        )
    }

    // ---------------------------------------------------------------
    // Index + summary builders
    // ---------------------------------------------------------------

    private fun sampleResults(): List<ScreenshotSourceResult> {
        val postUrl = "https://www.facebook.com/dawateislami.net/posts/111"
        return listOf(
            ScreenshotSourceResult(
                source = "https://www.facebook.com/dawateislami.net",
                label = "Link 1",
                kind = ScreenshotLineKind.FACEBOOK_PROFILE,
                normalizedUrl = "https://www.facebook.com/dawateislami.net",
                status = FacebookProfileStatus.PARTIAL,
                shots = listOf(
                    ScreenshotShot("profile", "profile.png", "AI-Tasks/screenshots-20261029-1200/link-01-dawateislami-net/profile.png", null),
                    ScreenshotShot("post", "post-1.png", "AI-Tasks/screenshots-20261029-1200/link-01-dawateislami-net/post-1.png", postUrl)
                ),
                expectedShots = 3,
                postLinks = listOf(postUrl, "https://www.facebook.com/dawateislami.net/posts/222"),
                folder = "link-01-dawateislami-net"
            ),
            ScreenshotSourceResult(
                source = "https://example.com/story",
                label = "Link 2",
                kind = ScreenshotLineKind.DIRECT_URL,
                normalizedUrl = "https://example.com/story",
                status = FacebookProfileStatus.OK,
                shots = listOf(
                    ScreenshotShot("direct", "link-02-example-com-story.png", "AI-Tasks/screenshots-20261029-1200/link-02-example-com-story.png", null)
                ),
                expectedShots = 1
            )
        )
    }

    private fun sampleConfig() = ScreenshotsCollection.RunConfig(
        sources = listOf("https://www.facebook.com/dawateislami.net", "https://example.com/story"),
        postsPerProfile = 2,
        mode = FacebookExtractionMode.HYBRID,
        runFolder = "AI-Tasks/screenshots-20261029-1200",
        startedAtMs = 1000,
        finishedAtMs = 2000,
        invalidSourceLines = 1,
        duplicateSourceLines = 0
    )

    @Test
    fun indexMarkdownListsSourcesStatusesFilesAndPostUrls() {
        val md = ScreenshotsCollection.buildIndexMarkdown(sampleResults(), sampleConfig(), null)
        assertTrue(md.contains("Screenshots Collection"))
        assertTrue(md.contains("Run folder: AI-Tasks/screenshots-20261029-1200"))
        assertTrue(md.contains("Link 1"))
        assertTrue(md.contains("https://www.facebook.com/dawateislami.net"))
        assertTrue(md.contains("PARTIAL"))
        assertTrue(md.contains("profile.png"))
        assertTrue(md.contains("post-1.png"))
        assertTrue(md.contains("https://www.facebook.com/dawateislami.net/posts/111"))
        assertTrue(md.contains("Link 2"))
        assertTrue(md.contains("link-02-example-com-story.png"))
        assertTrue(md.contains("3 screenshot(s) taken"))
        assertTrue(md.contains("1 missed"))
    }

    @Test
    fun summaryJsonHasConfigSourcesAndTotals() {
        val json = org.json.JSONObject(
            ScreenshotsCollection.buildSummaryJson(sampleResults(), sampleConfig())
        )
        val config = json.getJSONObject("config")
        assertEquals("AI-Tasks/screenshots-20261029-1200", config.getString("runFolder"))
        assertEquals(2, config.getInt("postsPerProfile"))
        assertEquals("hybrid", config.getString("method"))
        val summary = json.getJSONObject("summary")
        assertEquals(2, summary.getInt("sources"))
        assertEquals(3, summary.getInt("shotsTaken"))
        assertEquals(1, summary.getInt("shotsMissed"))
        assertEquals(1, summary.getInt("invalidSourceLines"))
        val sources = json.getJSONArray("sources")
        assertEquals(2, sources.length())
        val first = sources.getJSONObject(0)
        assertEquals("PARTIAL", first.getString("status"))
        assertEquals("FACEBOOK_PROFILE", first.getString("kind"))
        assertEquals(2, first.getInt("shotsTaken"))
        assertEquals(3, first.getInt("shotsExpected"))
        assertEquals(2, first.getJSONArray("discoveredPosts").length())
        val second = sources.getJSONObject(1)
        assertEquals("DIRECT_URL", second.getString("kind"))
        assertEquals("OK", second.getString("status"))
    }

    @Test
    fun totalsAndSummaryLineCountShotsAcrossSources() {
        val results = sampleResults()
        assertEquals(3, ScreenshotsCollection.totalShotsTaken(results))
        assertEquals(1, ScreenshotsCollection.totalShotsMissed(results))
        assertEquals("2 sources: 1 OK, 1 PARTIAL", ScreenshotsCollection.statusSummaryLine(results))
    }
}
