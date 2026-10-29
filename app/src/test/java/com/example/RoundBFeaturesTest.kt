package com.example

import com.example.nova.data.AgentTaskEntity
import com.example.nova.data.InterruptedTasks
import com.example.nova.trained.ActivityMetricsAggregator
import com.example.nova.trained.CollectorDefaults
import com.example.nova.trained.CollectorReport
import com.example.nova.trained.FacebookExtractionMode
import com.example.nova.trained.FacebookProfileResult
import com.example.nova.trained.FacebookProfileStatus
import com.example.nova.trained.FacebookRunArtifacts
import com.example.nova.trained.ScreenshotLineKind
import com.example.nova.trained.ScreenshotShot
import com.example.nova.trained.ScreenshotSourceResult
import com.example.nova.trained.ScreenshotsCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round B (NOVA M3 Control Center) — pure coverage for: the PDF
 * report MODEL (counts + the honest not-measured wording + per-source
 * rows), interrupted-task selection, collector-defaults
 * clamping/round-trip, and activity-metrics aggregation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoundBFeaturesTest {

    // ------------------------------------------------------------------
    // 1 — Links report model
    // ------------------------------------------------------------------

    private fun linksConfig() = FacebookRunArtifacts.RunConfig(
        sources = listOf("https://www.facebook.com/a", "https://www.facebook.com/b"),
        perSourceLimit = 10,
        mode = FacebookExtractionMode.HYBRID,
        startedAtMs = 1_000L,
        finishedAtMs = 61_000L,
        invalidSourceLines = 2,
        duplicateSourceLines = 1
    )

    @Test
    fun linksReportModel_countsAndHonestWording() {
        val shared = "https://www.facebook.com/a/posts/111"
        val results = listOf(
            FacebookProfileResult(
                profile = "https://www.facebook.com/a",
                normalizedUrl = "https://m.facebook.com/a",
                status = FacebookProfileStatus.OK,
                links = listOf(shared, "https://www.facebook.com/a/posts/222")
            ),
            FacebookProfileResult(
                profile = "https://www.facebook.com/b",
                normalizedUrl = "https://m.facebook.com/b",
                status = FacebookProfileStatus.PARTIAL,
                // One duplicate of the first source's link + one new.
                links = listOf(shared, "https://www.facebook.com/b/posts/333")
            )
        )
        val model = CollectorReport.buildLinksReport(
            results, linksConfig(), "task-abcdef", "COMPLETED",
            listOf("results.md", "facebook-post-links-x-report.pdf")
        )
        val counts = model.counts.toMap()
        assertEquals("2", counts["Sources"])
        assertEquals("10", counts["Per-source limit"])
        assertEquals("4", counts["Discovered candidates"])
        assertEquals("3", counts["Unique results"])
        assertEquals("1", counts["Duplicates removed"])
        assertTrue(counts["Invalid candidates"]!!.startsWith("2 (invalid source lines"))
        // The honesty core: unmeasured live accessibility is NEVER a number.
        assertEquals(CollectorReport.NOT_MEASURED_LIVE_CHECK, counts["Verified accessible"])
        assertTrue(counts["Verified accessible"]!!.contains("Not measured"))
        assertFalse(counts["Verified accessible"]!!.contains("0 verified"))
        assertEquals("Links Collection", model.kindLabel)
        assertEquals("Spark Automation — Extraction Report", model.title)
        assertTrue(model.reportId.startsWith("RPT-"))
        assertTrue(model.reportId.endsWith("ABCDEF"))
    }

    @Test
    fun linksReportModel_rowsAndFailures() {
        val results = listOf(
            FacebookProfileResult(
                profile = "https://www.facebook.com/ok",
                normalizedUrl = "https://m.facebook.com/ok",
                status = FacebookProfileStatus.OK,
                links = listOf("https://www.facebook.com/ok/posts/1")
            ),
            FacebookProfileResult(
                profile = "https://www.facebook.com/locked",
                normalizedUrl = "https://m.facebook.com/locked",
                status = FacebookProfileStatus.PROFILE_LOCKED,
                note = "This profile is locked."
            )
        )
        val model = CollectorReport.buildLinksReport(
            results, linksConfig(), "t1", "COMPLETED", emptyList()
        )
        assertEquals(2, model.sourceRows.size)
        assertEquals("Link 1", model.sourceRows[0].label)
        assertEquals("1 link", model.sourceRows[0].detail)
        assertEquals("Link 2", model.sourceRows[1].label)
        assertEquals("0 links", model.sourceRows[1].detail)
        assertEquals(1, model.failures.size)
        assertTrue(model.failures[0].startsWith("Link 2 — PROFILE_LOCKED"))
        assertTrue(model.failures[0].contains("This profile is locked."))
        assertTrue(model.warnings.any { it.contains("Reels are excluded") })
    }

    // ------------------------------------------------------------------
    // 1 — Screenshots report model
    // ------------------------------------------------------------------

    @Test
    fun screenshotsReportModel_shotAccounting() {
        val shot = ScreenshotShot(
            kind = "profile", fileName = "profile.png",
            relativePath = "AI-Tasks/screenshots-x/link-01-a/profile.png"
        )
        val results = listOf(
            ScreenshotSourceResult(
                source = "https://www.facebook.com/a", label = "Link 1",
                kind = ScreenshotLineKind.FACEBOOK_PROFILE,
                normalizedUrl = "https://m.facebook.com/a",
                status = FacebookProfileStatus.PARTIAL,
                shots = listOf(shot), expectedShots = 3
            ),
            ScreenshotSourceResult(
                source = "https://example.com/post", label = "Link 2",
                kind = ScreenshotLineKind.DIRECT_URL,
                normalizedUrl = "https://example.com/post",
                status = FacebookProfileStatus.OPEN_FAILED,
                shots = emptyList(), expectedShots = 1,
                note = "The page could not be opened."
            )
        )
        val config = ScreenshotsCollection.RunConfig(
            sources = listOf("https://www.facebook.com/a", "https://example.com/post"),
            postsPerProfile = 2,
            mode = FacebookExtractionMode.M3_ONLY,
            runFolder = "AI-Tasks/screenshots-x",
            startedAtMs = 1_000L, finishedAtMs = 61_000L,
            invalidSourceLines = 1, duplicateSourceLines = 0
        )
        val model = CollectorReport.buildScreenshotsReport(
            results, config, "task-9", "FAILED — boom", listOf("index.md", "report.pdf"),
            stoppedEarlyNote = "Run cancelled before finishing."
        )
        val counts = model.counts.toMap()
        assertEquals("4", counts["Screenshots requested"])
        assertEquals("1", counts["Screenshots taken"])
        assertEquals("3", counts["Screenshots missed"])
        assertTrue(counts["Invalid candidates"]!!.startsWith("1 (invalid source lines"))
        assertEquals("1 of 3 screenshots", model.sourceRows[0].detail)
        assertEquals("0 of 1 screenshot", model.sourceRows[1].detail)
        assertEquals(1, model.failures.size)
        assertTrue(model.failures[0].startsWith("Link 2 — OPEN_FAILED"))
        assertTrue(model.warnings.any { it.contains("visible browser viewport") })
        assertTrue(model.warnings.any { it.contains("stopped early") })
        assertEquals("Screenshots Collection", model.kindLabel)
    }

    // ------------------------------------------------------------------
    // 2 — Interrupted-task selection
    // ------------------------------------------------------------------

    private fun task(status: String, cadence: String = "RUN_ONCE") = AgentTaskEntity(
        id = "id-$status-$cadence", title = "t", prompt = "p",
        status = status, cadence = cadence
    )

    @Test
    fun interruptedSelection_onlyInFlightStatuses() {
        val tasks = listOf(
            task("RUNNING"), task("WAITING"), task("WAITING_APPROVAL"), task("PAUSED"),
            task("PENDING"), task("COMPLETED"), task("FAILED"), task("CANCELLED"),
            task("SAFETY_STOPPED"), task("PENDING", cadence = "DAILY")
        )
        val orphaned = InterruptedTasks.selectOrphaned(tasks)
        assertEquals(
            listOf("RUNNING", "WAITING", "WAITING_APPROVAL", "PAUSED"),
            orphaned.map { it.status }
        )
        assertTrue(InterruptedTasks.INTERRUPTED_STEP.contains("Interrupted"))
        assertTrue(InterruptedTasks.INTERRUPTED_STEP.contains("Partial results, if any, were saved."))
    }

    // ------------------------------------------------------------------
    // 3 — Collector defaults: clamping + round-trip
    // ------------------------------------------------------------------

    @Test
    fun collectorDefaults_fallbackAndClamping() {
        val builtIn = CollectorDefaults.fromSettings(emptyMap())
        assertEquals(FacebookExtractionMode.HYBRID, builtIn.method)
        assertEquals(5, builtIn.linksPerSource)
        assertEquals(2, builtIn.screenshotPostsPerProfile)

        val stored = CollectorDefaults.fromSettings(
            mapOf(
                CollectorDefaults.KEY_METHOD to "m3",
                CollectorDefaults.KEY_LINKS_PER_SOURCE to "250",
                CollectorDefaults.KEY_SCREENSHOT_POSTS to "0"
            )
        )
        assertEquals(FacebookExtractionMode.M3_ONLY, stored.method)
        assertEquals(100, stored.linksPerSource)
        assertEquals(1, stored.screenshotPostsPerProfile)

        val garbage = CollectorDefaults.fromSettings(
            mapOf(
                CollectorDefaults.KEY_METHOD to "nonsense",
                CollectorDefaults.KEY_LINKS_PER_SOURCE to "abc",
                CollectorDefaults.KEY_SCREENSHOT_POSTS to ""
            )
        )
        assertEquals(CollectorDefaults.BUILT_IN, garbage)
    }

    @Test
    fun collectorDefaults_roundTrip() {
        val defaults = CollectorDefaults(FacebookExtractionMode.M1_ONLY, 25, 3)
        val roundTripped = CollectorDefaults.fromSettings(CollectorDefaults.toSettings(defaults))
        assertEquals(defaults, roundTripped)
    }

    // ------------------------------------------------------------------
    // 4 — Activity metrics aggregation
    // ------------------------------------------------------------------

    @Test
    fun activityMetrics_aggregateRealInputsOnly() {
        val metrics = ActivityMetricsAggregator.aggregate(
            taskStatuses = listOf("COMPLETED", "COMPLETED", "FAILED", "RUNNING", "CANCELLED"),
            linksSummaryJsons = listOf(
                """{"uniqueResults": 7, "discoveredCandidates": 9}""",
                """{"uniqueResults": 5}""",
                "{not json"
            ),
            screenshotsSummaryJsons = listOf(
                """{"summary": {"shotsTaken": 3}}""",
                """{"summary": {}}"""
            ),
            createdFilePaths = listOf(
                "AI-Tasks/facebook-post-links-20261029-1200-report.pdf",
                "AI-Tasks/screenshots-20261029-1201/report.pdf",
                "AI-Tasks/facebook-post-links-20261029-1200-results.csv",
                "AI-Tasks/screenshots-20261029-1201/link-01-a/profile.png"
            )
        )
        assertEquals(2, metrics.completedTasks)
        assertEquals(1, metrics.failedTasks)
        // Only parseable summaries count as runs: 2 links + 1 screenshots.
        assertEquals(3, metrics.collectorRuns)
        assertEquals(12, metrics.uniquePostLinks)
        assertEquals(3, metrics.screenshotsTaken)
        assertEquals(2, metrics.pdfReports)
    }
}
