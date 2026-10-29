package com.example.nova.trained

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure logic for Trained Task #2 — the Screenshots Collector, Spark's
 * second core feature (the sibling of Trained Task #1, the Facebook
 * Post Links Collector).
 *
 * The relationship between the two core features: LINKS collection
 * finds the posts; SCREENSHOTS collection captures the evidence. This
 * collector reuses Task #1's machinery wherever the two overlap — the
 * same input parsing ([FacebookPostLinks.parseCollectorInput]), the
 * same page classifier and status taxonomy ([FacebookProfileStatus]),
 * and the same discovery ladder (driven by [FacebookLinksCollector]).
 *
 * Everything in this object is deterministic and side-effect free:
 * line classification, file naming, per-source shot accounting and
 * the index/summary artifact formats. The browser-driving half lives
 * in [ScreenshotsCollector].
 */

/** How one input line is handled by the screenshots run. */
enum class ScreenshotLineKind {
    /** A Facebook profile/Page URL — profile shot + discovered post shots. */
    FACEBOOK_PROFILE,

    /** Any other http(s) URL (a direct post/article link on any site) — one shot. */
    DIRECT_URL
}

/** One captured screenshot file. */
data class ScreenshotShot(
    /** "profile" | "post" | "direct". */
    val kind: String,
    /** File name inside the source folder (profile mode) or run folder (direct mode). */
    val fileName: String,
    /** Workspace-relative path of the PNG actually written. */
    val relativePath: String,
    /** The post permalink this shot belongs to (post shots only). */
    val postUrl: String? = null,
    /** JPEG copy written next to the PNG when the PNG exceeded the size threshold. */
    val jpegRelativePath: String? = null
)

/** One source's outcome in a screenshots run. */
data class ScreenshotSourceResult(
    /** The source URL exactly as the user supplied it. */
    val source: String,
    /** "Link 1".."Link N", aligned with the parsed input. */
    val label: String,
    val kind: ScreenshotLineKind,
    /** The URL actually visited (m.facebook.com form for profiles). */
    val normalizedUrl: String,
    /** Shared taxonomy with the links collector — same names, same meanings. */
    val status: FacebookProfileStatus,
    /** Shots actually captured for this source, in capture order. */
    val shots: List<ScreenshotShot> = emptyList(),
    /** How many shots this source was expected to produce (accounting). */
    val expectedShots: Int = 0,
    /** Post permalinks discovered on the profile (profile mode). */
    val postLinks: List<String> = emptyList(),
    /** Human-readable detail for non-OK outcomes (never a raw exception). */
    val note: String? = null,
    /** Workspace-relative folder holding this source's shots ("" = run folder root). */
    val folder: String = ""
)

/**
 * Live state of the most recent Mode-1 (Tasks-screen) screenshots run,
 * exposed by NovaRepository for the Trained Tasks UI. Mirrors
 * [FacebookCollectRunResult]; [results] grows source by source.
 */
data class ScreenshotsRunResult(
    val taskId: String,
    val postsPerProfile: Int,
    val totalSources: Int,
    val results: List<ScreenshotSourceResult> = emptyList(),
    /** Workspace-relative run folder (AI-Tasks/screenshots-<stamp>). */
    val runFolderPath: String? = null,
    /** Workspace-relative index.md inside the run folder, once written. */
    val indexFilePath: String? = null,
    val running: Boolean = false,
    val summaryLine: String? = null,
    /** The discovery mode the run used (stated in audit + artifacts). */
    val mode: FacebookExtractionMode = FacebookExtractionMode.HYBRID,
    /** Every artifact file written for the run (images, index, summary). */
    val artifactPaths: List<String> = emptyList()
)

object ScreenshotsCollection {

    // Al's standing workflow is 1 profile shot + 2 post shots = 3 per
    // profile, so the default is 2 and the range stays tight (1–10):
    // screenshots are heavy evidence files, not bulk link lists.
    const val MIN_POSTS_PER_PROFILE = 1
    const val MAX_POSTS_PER_PROFILE = 10
    const val DEFAULT_POSTS_PER_PROFILE = 2

    /** PNG size above which a JPEG copy (quality 85) is ALSO saved. */
    const val JPEG_FALLBACK_THRESHOLD_BYTES = 6_000_000L

    /** Clamps a requested posts-per-profile count into the supported 1–10 range. */
    fun clampPostsPerProfile(requested: Int): Int =
        requested.coerceIn(MIN_POSTS_PER_PROFILE, MAX_POSTS_PER_PROFILE)

    // ------------------------------------------------------------------
    // Line classification (pure)
    // ------------------------------------------------------------------

    /**
     * Classifies one valid source line. A Facebook URL that is NOT
     * itself a post permalink (and not a login/checkpoint URL) is a
     * profile/Page — the same detection the links collector uses
     * ([FacebookPostLinks.normalizeProfileUrl] /
     * [FacebookPostLinks.normalizePostUrl]). Everything else —
     * including direct Facebook permalinks (posts, photos, videos,
     * watch, story) and every non-Facebook URL — is a direct shot.
     */
    fun classifyLine(rawUrl: String): ScreenshotLineKind {
        val trimmed = rawUrl.trim()
        // The input parser accepts scheme-less lines and normalizes
        // them to https — classify the same way so both agree.
        val url = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        if (!FacebookPostLinks.isFacebookUrl(url)) return ScreenshotLineKind.DIRECT_URL
        if (FacebookPostLinks.isLoginUrl(url)) return ScreenshotLineKind.DIRECT_URL
        if (FacebookPostLinks.normalizePostUrl(url) != null) return ScreenshotLineKind.DIRECT_URL
        return ScreenshotLineKind.FACEBOOK_PROFILE
    }

    // ------------------------------------------------------------------
    // File naming (pure)
    // ------------------------------------------------------------------

    /**
     * A short filesystem-safe slug for [url]: the last meaningful path
     * segment (profile name / post id), falling back to the host.
     * Lowercase [a-z0-9-], max 40 chars.
     */
    fun slugFor(url: String): String {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            null
        }
        val segments = (uri?.path ?: "").split('/').filter { it.isNotBlank() }
        val candidate = when {
            segments.isEmpty() -> uri?.host ?: "page"
            // profile.php?id=123 → "profile-123"
            segments.last() == "profile.php" -> {
                val id = (uri?.rawQuery ?: "").split('&')
                    .firstOrNull { it.startsWith("id=") }?.removePrefix("id=")
                if (!id.isNullOrBlank()) "profile-$id" else "profile"
            }
            segments.size >= 2 && segments[segments.size - 2] == "posts" -> "post-${segments.last()}"
            segments.size >= 2 && segments[segments.size - 2] == "videos" -> "video-${segments.last()}"
            else -> segments.last()
        }
        val slug = candidate.lowercase(Locale.ROOT)
            .map { c -> if (c.isLetterOrDigit() || c == '-') c else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(40)
            .trim('-')
        return slug.ifBlank { "page" }
    }

    /** Zero-padded 1-based source number: 1 → "01", 12 → "12". */
    fun sourceNumber(index: Int): String = (index + 1).toString().padStart(2, '0')

    /** Profile-mode subfolder for the source at [index] (0-based): "link-01-<slug>". */
    fun sourceFolderName(index: Int, url: String): String =
        "link-${sourceNumber(index)}-${slugFor(url)}"

    /** Direct-mode file name for the source at [index] (0-based): "link-01-<slug>.png". */
    fun directFileName(index: Int, url: String): String =
        "link-${sourceNumber(index)}-${slugFor(url)}.png"

    /** Post shot file name (1-based): "post-1.png", "post-2.png", … */
    fun postFileName(postIndex: Int): String = "post-${postIndex + 1}.png"

    const val PROFILE_FILE_NAME = "profile.png"

    // ------------------------------------------------------------------
    // Per-source shot accounting (pure)
    // ------------------------------------------------------------------

    /**
     * How many shots a profile-mode source is expected to produce:
     * the profile shot plus one per discovered post (capped by the
     * request). Terminal discovery outcomes (locked, unavailable,
     * errors, login) expect only the profile evidence shot.
     */
    fun expectedShotsForProfile(
        discoveryStatus: FacebookProfileStatus,
        linksFound: Int,
        postsPerProfile: Int
    ): Int = when (discoveryStatus) {
        FacebookProfileStatus.OK,
        FacebookProfileStatus.PARTIAL,
        FacebookProfileStatus.NO_POSTS ->
            1 + minOf(clampPostsPerProfile(postsPerProfile), linksFound)
        else -> 1
    }

    /**
     * The final status of a profile-mode source. Discovery's terminal
     * statuses win outright; otherwise the status is pure accounting
     * over the shots: all expected captured → OK; some captured →
     * PARTIAL (the note carries the detail); zero captured on a page
     * that yielded posts → PARTIAL as well (the shortfall IS the
     * failure, and NO_POSTS would be a lie — posts were found).
     * A discovery of NO_POSTS stays NO_POSTS even though the profile
     * shot was captured: the status reflects the missing posts.
     */
    fun resolveProfileStatus(
        discoveryStatus: FacebookProfileStatus,
        profileShotTaken: Boolean,
        postShotsTaken: Int,
        linksFound: Int,
        postsPerProfile: Int
    ): FacebookProfileStatus = when (discoveryStatus) {
        FacebookProfileStatus.PROFILE_LOCKED,
        FacebookProfileStatus.PROFILE_UNAVAILABLE,
        FacebookProfileStatus.OPEN_FAILED,
        FacebookProfileStatus.FB_ERROR,
        FacebookProfileStatus.LOGIN_REQUIRED -> discoveryStatus
        FacebookProfileStatus.NO_POSTS -> FacebookProfileStatus.NO_POSTS
        else -> {
            val expected = expectedShotsForProfile(discoveryStatus, linksFound, postsPerProfile)
            val taken = postShotsTaken + if (profileShotTaken) 1 else 0
            if (taken >= expected) FacebookProfileStatus.OK else FacebookProfileStatus.PARTIAL
        }
    }

    /**
     * The final status of a direct-mode source: the page class (when
     * one was probed — Facebook direct links) and the open outcome
     * decide terminal states; a captured shot on a content page is OK;
     * a page that loaded but refused capture is OPEN_FAILED (from the
     * user's side the item produced nothing).
     */
    fun resolveDirectStatus(
        pageClass: FacebookPageClass?,
        openFailed: Boolean,
        captured: Boolean
    ): FacebookProfileStatus = when {
        openFailed -> FacebookProfileStatus.OPEN_FAILED
        pageClass == FacebookPageClass.LOGIN_WALL -> FacebookProfileStatus.LOGIN_REQUIRED
        pageClass == FacebookPageClass.PROFILE_LOCKED -> FacebookProfileStatus.PROFILE_LOCKED
        pageClass == FacebookPageClass.PROFILE_UNAVAILABLE -> FacebookProfileStatus.PROFILE_UNAVAILABLE
        pageClass == FacebookPageClass.FB_ERROR_PAGE -> FacebookProfileStatus.FB_ERROR
        captured -> FacebookProfileStatus.OK
        else -> FacebookProfileStatus.OPEN_FAILED
    }

    /** "3 sources: 2 OK, 1 PARTIAL" — same shape as the links collector's summary. */
    fun statusSummaryLine(results: List<ScreenshotSourceResult>): String {
        if (results.isEmpty()) return "0 sources"
        val counts = LinkedHashMap<FacebookProfileStatus, Int>()
        for (status in FacebookProfileStatus.values()) {
            val n = results.count { it.status == status }
            if (n > 0) counts[status] = n
        }
        val parts = counts.entries.joinToString(", ") { "${it.value} ${it.key.name}" }
        return "${results.size} sources: $parts"
    }

    fun totalShotsTaken(results: List<ScreenshotSourceResult>): Int =
        results.sumOf { it.shots.size }

    fun totalShotsMissed(results: List<ScreenshotSourceResult>): Int =
        results.sumOf { maxOf(0, it.expectedShots - it.shots.size) }

    // ------------------------------------------------------------------
    // Artifact builders (pure): index.md + summary.json
    // ------------------------------------------------------------------

    /** Run configuration recorded in summary.json. */
    data class RunConfig(
        val sources: List<String>,
        val postsPerProfile: Int,
        val mode: FacebookExtractionMode,
        val runFolder: String,
        val startedAtMs: Long,
        val finishedAtMs: Long,
        /** Measured invalid input lines (Mode 1 input parse; 0 for Mode 2). */
        val invalidSourceLines: Int = 0,
        /** Duplicate source lines removed before the run (Mode 1 input parse). */
        val duplicateSourceLines: Int = 0
    )

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    /**
     * The run index: per source — label, source URL, status, and the
     * screenshot files with the post URLs they belong to. Written as
     * index.md inside the run folder.
     */
    fun buildIndexMarkdown(
        results: List<ScreenshotSourceResult>,
        config: RunConfig,
        stoppedEarlyNote: String? = null
    ): String {
        val sb = StringBuilder()
        sb.append("# Screenshots Collection — ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(config.startedAtMs)))
            .append("\n\n")
        sb.append("Trained task (no AI): one profile screenshot plus the latest ")
            .append(config.postsPerProfile)
            .append(" post screenshot(s) per Facebook profile; one screenshot per direct link.\n")
        sb.append("Discovery method: ").append(config.mode.displayName()).append("\n")
        sb.append("Run folder: ").append(config.runFolder).append("\n")
        sb.append("Summary: ").append(statusSummaryLine(results))
            .append(" · ").append(totalShotsTaken(results)).append(" screenshot(s) taken")
            .append(", ").append(totalShotsMissed(results)).append(" missed\n")
        if (!stoppedEarlyNote.isNullOrBlank()) {
            sb.append("\n> Run stopped early: ").append(stoppedEarlyNote).append("\n")
        }
        for (result in results) {
            sb.append("\n## ").append(result.label).append(" — ")
                .append(result.normalizedUrl.ifBlank { result.source })
                .append(" — ").append(result.status.name)
                .append(" (").append(result.shots.size)
                .append(if (result.shots.size == 1) " screenshot" else " screenshots")
                .append(")\n")
            if (result.kind == ScreenshotLineKind.FACEBOOK_PROFILE) {
                sb.append("Folder: ").append(result.folder).append("/\n")
            }
            if (!result.note.isNullOrBlank()) {
                sb.append(result.note).append("\n")
            }
            if (result.shots.isEmpty()) {
                sb.append("_No screenshots captured._\n")
            } else {
                for (shot in result.shots) {
                    sb.append("- ").append(shot.fileName)
                    when (shot.kind) {
                        "profile" -> sb.append(" — profile page")
                        "post" -> sb.append(" — ").append(shot.postUrl ?: "")
                        else -> sb.append(" — ").append(result.normalizedUrl.ifBlank { result.source })
                    }
                    shot.jpegRelativePath?.let { jpeg ->
                        sb.append(" (also JPEG: ").append(jpeg.substringAfterLast('/')).append(")")
                    }
                    sb.append("\n")
                }
            }
            if (result.kind == ScreenshotLineKind.FACEBOOK_PROFILE && result.postLinks.isNotEmpty()) {
                sb.append("Discovered posts: ").append(result.postLinks.size).append("\n")
                result.postLinks.forEachIndexed { index, link ->
                    sb.append(index + 1).append(". ").append(link).append("\n")
                }
            }
        }
        return sb.toString()
    }

    /** Headline statistics + the full per-source record, as summary.json in the run folder. */
    fun buildSummaryJson(
        results: List<ScreenshotSourceResult>,
        config: RunConfig
    ): String {
        val root = JSONObject()
        root.put("task", "screenshots_collector")
        root.put(
            "config", JSONObject()
                .put("sources", JSONArray(config.sources))
                .put("postsPerProfile", config.postsPerProfile)
                .put("method", config.mode.toolArg())
                .put("methodLabel", config.mode.displayName())
                .put("runFolder", config.runFolder)
                .put("startedAt", stamp(config.startedAtMs))
                .put("finishedAt", stamp(config.finishedAtMs))
                .put("startedAtMs", config.startedAtMs)
                .put("finishedAtMs", config.finishedAtMs)
        )
        val sourcesArr = JSONArray()
        for (result in results) {
            val block = JSONObject()
            block.put("label", result.label)
            block.put("sourceUrl", result.source)
            block.put("normalizedUrl", result.normalizedUrl)
            block.put("kind", result.kind.name)
            block.put("status", result.status.name)
            result.note?.let { block.put("note", it) }
            if (result.folder.isNotBlank()) block.put("folder", result.folder)
            block.put("shotsExpected", result.expectedShots)
            block.put("shotsTaken", result.shots.size)
            val shotsArr = JSONArray()
            for (shot in result.shots) {
                val shotObj = JSONObject()
                    .put("kind", shot.kind)
                    .put("file", shot.fileName)
                    .put("path", shot.relativePath)
                shot.postUrl?.let { shotObj.put("postUrl", it) }
                shot.jpegRelativePath?.let { shotObj.put("jpegPath", it) }
                shotsArr.put(shotObj)
            }
            block.put("screenshots", shotsArr)
            if (result.postLinks.isNotEmpty()) {
                block.put("discoveredPosts", JSONArray(result.postLinks))
            }
            sourcesArr.put(block)
        }
        root.put("sources", sourcesArr)
        val statusCounts = JSONObject()
        for (status in FacebookProfileStatus.values()) {
            val n = results.count { it.status == status }
            if (n > 0) statusCounts.put(status.name, n)
        }
        root.put(
            "summary", JSONObject()
                .put("sources", results.size)
                .put("shotsTaken", totalShotsTaken(results))
                .put("shotsMissed", totalShotsMissed(results))
                .put("invalidSourceLines", config.invalidSourceLines)
                .put("duplicateSourceLinesRemoved", config.duplicateSourceLines)
                .put("statusCounts", statusCounts)
                .put("statusSummary", statusSummaryLine(results))
        )
        return root.toString(2)
    }
}
