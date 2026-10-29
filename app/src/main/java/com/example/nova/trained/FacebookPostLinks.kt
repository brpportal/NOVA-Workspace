package com.example.nova.trained

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-profile outcome of a Facebook post-links collection. The taxonomy is
 * deliberately fine-grained (Al's "fully offline training" requirement):
 * every distinct failure state is pre-programmed and detected immediately —
 * nothing collapses into a generic ERROR.
 */
enum class FacebookProfileStatus {
    /** Exactly the requested number of post links was collected. */
    OK,

    /** The profile is fine but fewer posts than requested exist / were found. */
    PARTIAL,

    /**
     * The profile loads and zero post permalinks were found after the full
     * effort (attempts + scroll budget + the M1-M5 ladder). LOGIN_REQUIRED
     * is never inferred from a zero-link page: it needs positive login
     * evidence (a /login or /checkpoint URL, a visible login form, or a
     * viewport-covering logged-out interstitial).
     */
    NO_POSTS,

    /** The profile exists but its content is locked / private. */
    PROFILE_LOCKED,

    /** Banned / deactivated / not found — Facebook says the content isn't available. */
    PROFILE_UNAVAILABLE,

    /** Navigation or page load failed (after the one allowed retry). */
    OPEN_FAILED,

    /** Facebook itself failed: an FB error page, or the WebView renderer crashed. */
    FB_ERROR,

    /** A login / checkpoint wall — the only state that pauses the run (Needs you). */
    LOGIN_REQUIRED
}

/**
 * Live state of the most recent Mode-1 (Tasks-screen) collector run,
 * exposed by NovaRepository for the Trained Tasks UI. [results] grows
 * profile by profile while the chain runs.
 */
data class FacebookCollectRunResult(
    val taskId: String,
    val requestedCount: Int,
    val totalProfiles: Int,
    val results: List<FacebookProfileResult> = emptyList(),
    val resultFilePath: String? = null,
    val running: Boolean = false,
    val summaryLine: String? = null
)

/** One profile's collection result. [links] are normalized permalinks, latest first. */
data class FacebookProfileResult(
    /** The profile reference exactly as the user supplied it. */
    val profile: String,
    /** The normalized m.facebook.com URL that was actually visited. */
    val normalizedUrl: String,
    val status: FacebookProfileStatus,
    val links: List<String> = emptyList(),
    /** Human-readable detail for non-OK outcomes (never a raw exception). */
    val note: String? = null,
    /** Escalation-ladder record: methods tried in order + which produced [links]. */
    val methodsSummary: String? = null
)

/**
 * Raw signals probed from a loaded page, fed to the trained classifier
 * ([FacebookPostLinks.classifyPage]). Everything here is a plain value so
 * the classifier is a pure function with one test fixture per status.
 */
data class FacebookPageSignals(
    /** The URL the page ended on after redirects. */
    val finalUrl: String = "",
    val title: String = "",
    /** A password field or a login form is present in the DOM. */
    val hasLoginForm: Boolean = false,
    /**
     * Diagnostic-only: a logged-out gate/banner (e.g. "See more on Facebook")
     * is present. Never sufficient for a login wall on its own.
     */
    val hasLoginGate: Boolean = false,
    /** A viewport-covering logged-out interstitial dominates the page. */
    val hasLoginInterstitial: Boolean = false,
    /** Account/composer/notifications anchors present. */
    val hasLoggedInChrome: Boolean = false,
    /** A Follow/Following/Message action row on the profile. */
    val hasFollowAction: Boolean = false,
    /**
     * Positive evidence the posts area rendered: feed/article containers
     * or a Posts section marker in the DOM.
     */
    val hasPostsArea: Boolean = false,
    /** Excerpt of the visible page text (key phrases are matched against it). */
    val pageText: String = "",
    /** A main-frame load error was recorded for this page (informational; the collector retries before classifying). */
    val loadError: Boolean = false,
    /** The WebView renderer process died while loading this page. */
    val rendererGone: Boolean = false
)

/** Coarse page classification — the "what kind of page is this" half of the taxonomy. */
enum class FacebookPageClass {
    CONTENT,
    LOGIN_WALL,
    PROFILE_LOCKED,
    PROFILE_UNAVAILABLE,
    FB_ERROR_PAGE
}

/**
 * Pure logic for Trained Task #1 — the Facebook Post Links Collector.
 *
 * Everything in this object is deterministic and side-effect free: URL
 * normalization, post-permalink pattern matching, anchor-payload
 * extraction, the page classifier, final-status resolution and the result
 * file format. The browser-driving half lives in [FacebookLinksCollector];
 * keeping the decisions here is what makes the trained behaviour testable
 * "offline" — every status has a fixture in FacebookPostLinksTest.
 */
object FacebookPostLinks {

    const val MIN_POSTS_PER_PROFILE = 3
    const val MAX_POSTS_PER_PROFILE = 7
    const val DEFAULT_POSTS_PER_PROFILE = 5

    // ------------------------------------------------------------------
    // Profile URL normalization
    // ------------------------------------------------------------------

    private fun parseUri(raw: String): URI? {
        return try {
            URI(raw)
        } catch (e: Exception) {
            try {
                URI(raw.replace("[", "%5B").replace("]", "%5D"))
            } catch (e2: Exception) {
                null
            }
        }
    }

    fun normalizeProfileUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        if (!trimmed.contains("://")) {
            val firstSegment = trimmed.substringBefore('/').substringBefore('?')
            if (firstSegment.isNotBlank() && !firstSegment.contains('.') && !firstSegment.contains(' ')) {
                val namePath = trimmed.substringBefore('?').trim('/')
                if (namePath.isNotBlank()) return "https://m.facebook.com/$namePath"
            }
        }
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = parseUri(withScheme) ?: return ""
        val host = uri.host?.lowercase(Locale.ROOT)
        val path = (uri.path ?: "").trimEnd('/').ifBlank { "" }
        return if (host == null) {
            val name = trimmed.trimStart('/')
            if (name.isBlank() || name.contains(" ")) "" else "https://m.facebook.com/$name"
        } else if (host == "facebook.com" || host.endsWith(".facebook.com")) {
            val idParam = if (path.endsWith("profile.php")) {
                parseQueryParams(uri.rawQuery ?: "")["id"]?.let { "?id=$it" } ?: ""
            } else {
                ""
            }
            "https://m.facebook.com$path$idParam"
        } else {
            trimmed
        }
    }

    /** True when [url] points at any facebook.com host. */
    fun isFacebookUrl(url: String): Boolean {
        val host = parseUri(url)?.host?.lowercase(Locale.ROOT) ?: return false
        return host == "facebook.com" || host.endsWith(".facebook.com")
    }

    /** True when [url] is a Facebook login / checkpoint URL (a Needs-you wall). */
    fun isLoginUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        val path = parseUri(url)?.path?.lowercase(Locale.ROOT) ?: ""
        val hasTwoStep = path.split('/').any { it == "two_step" || it == "two_step_verification" || it.startsWith("two_step") }
        return lower.contains("/login") || lower.contains("login.php") ||
            lower.contains("/checkpoint") || lower.contains("checkpoint/") ||
            hasTwoStep
    }

    // ------------------------------------------------------------------
    // Post permalink patterns + normalization
    // ------------------------------------------------------------------

    fun normalizePostUrl(rawHref: String, baseUrl: String = ""): String? {
        val href = rawHref.trim()
        if (href.isBlank()) return null
        val fullHref = if (!href.startsWith("http://") && !href.startsWith("https://")) {
            val usableBase = if (baseUrl.isNotBlank() && parseUri(baseUrl)?.host?.let { isFacebookUrl(baseUrl) } == true) {
                baseUrl
            } else {
                "https://m.facebook.com/"
            }
            try {
                val baseUri = parseUri(usableBase) ?: URI("https://m.facebook.com/")
                baseUri.resolve(href).toString()
            } catch (e: Exception) {
                if (href.startsWith("/")) "https://m.facebook.com$href" else "https://m.facebook.com/$href"
            }
        } else {
            href
        }
        val uri = parseUri(fullHref) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host != "facebook.com" && !host.endsWith(".facebook.com")) return null
        val path = uri.path ?: return null
        val params = parseQueryParams(uri.rawQuery ?: "")

        if (POSTS_PATH_REGEX.containsMatchIn(path) || VIDEOS_PATH_REGEX.containsMatchIn(path)) {
            return "https://m.facebook.com$path"
        }
        return when {
            path.endsWith("/story.php") || path.endsWith("/permalink.php") -> {
                val fbid = params["story_fbid"]?.takeIf { it.isNotBlank() } ?: return null
                val id = params["id"]?.takeIf { it.isNotBlank() }
                buildString {
                    append("https://m.facebook.com").append(path)
                    append("?story_fbid=").append(fbid)
                    if (id != null) append("&id=").append(id)
                }
            }
            path.endsWith("/photo.php") -> {
                val fbid = params["fbid"]?.takeIf { it.isNotBlank() } ?: return null
                "https://m.facebook.com$path?fbid=$fbid"
            }
            path == "/photo" || path == "/photo/" -> {
                val fbid = params["fbid"]?.takeIf { it.isNotBlank() } ?: return null
                "https://m.facebook.com/photo/?fbid=$fbid"
            }
            path == "/watch" || path == "/watch/" -> {
                val v = params["v"]?.takeIf { it.isNotBlank() } ?: return null
                "https://m.facebook.com/watch/?v=$v"
            }
            else -> null
        }
    }

    fun isPostPermalink(url: String, baseUrl: String = ""): Boolean = normalizePostUrl(url, baseUrl) != null

    private val POSTS_PATH_REGEX = Regex("^/.+/posts/[^/]+/?$")
    private val VIDEOS_PATH_REGEX = Regex("^/.+/videos/[^/]+/?$")

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in rawQuery.split("&")) {
            if (pair.isBlank()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) {
                out[pair] = ""
            } else {
                out[pair.substring(0, eq)] = pair.substring(eq + 1)
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Anchor extraction (pure: href list / JSON payload in → links out)
    // ------------------------------------------------------------------

    fun extractPostLinks(anchorHrefs: List<String>, baseUrl: String = ""): List<String> {
        val seen = LinkedHashSet<String>()
        for (href in anchorHrefs) {
            val normalized = normalizePostUrl(href, baseUrl) ?: continue
            seen.add(normalized)
        }
        return seen.toList()
    }

    fun extractPostLinksFromJson(payload: String?, baseUrl: String = ""): List<String> {
        if (payload.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(payload)
            val hrefs = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val h = arr.optString(i)
                if (h.isNotBlank()) hrefs.add(h)
            }
            extractPostLinks(hrefs, baseUrl)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun sampleHrefsForDiagnostics(anchorHrefs: List<String>, limit: Int = 10): List<String> {
        if (anchorHrefs.isEmpty() || limit <= 0) return emptyList()
        val markers = listOf("posts", "story", "photo", "video", "watch", "pfbid", "fbid", "reel")
        val looking = anchorHrefs.filter { href ->
            val lower = href.lowercase(Locale.ROOT)
            markers.any { lower.contains(it) }
        }
        val candidates = (looking + anchorHrefs).map { sanitizeHrefForDiagnostics(it) }
        return candidates.distinct().take(limit)
    }

    private fun sanitizeHrefForDiagnostics(rawHref: String): String {
        val uri = parseUri(rawHref.trim()) ?: return rawHref.take(120)
        val host = uri.host ?: return rawHref.take(120)
        val params = parseQueryParams(uri.rawQuery ?: "")
        val keep = listOf("story_fbid", "fbid", "v", "id").mapNotNull { key ->
            params[key]?.takeIf { it.isNotBlank() }?.let { "$key=$it" }
        }
        val base = "https://$host${uri.path ?: ""}" +
            if (keep.isEmpty()) "" else "?" + keep.joinToString("&")
        return base.take(120)
    }

    private val TABS_ROW_PATTERNS = listOf(
        " all photos reels ",
        " all posts photos ",
        " all photos posts ",
        " all posts reels "
    )

    private val HEADER_STATS_REGEX = Regex("""\b\d[\d,.]*[kmbt]?\s+(?:followers|following)\b""", RegexOption.IGNORE_CASE)

    fun hasProfileTabsRow(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = " " + text.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").trim() + " "
        return TABS_ROW_PATTERNS.any { normalized.contains(it) }
    }

    fun hasHeaderStats(text: String): Boolean {
        if (text.isBlank()) return false
        return HEADER_STATS_REGEX.containsMatchIn(text)
    }

    fun signalsFromProbeJson(
        probeJson: String?,
        loadError: Boolean = false,
        rendererGone: Boolean = false
    ): FacebookPageSignals? {
        if (probeJson.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(probeJson)
            val hasPassword = obj.optBoolean("hasPassword", false)
            val hasLoginForm = obj.optBoolean("hasLoginForm", false)
            val text = obj.optString("text", "")
            val probePostsArea = obj.optBoolean("hasPostsArea", false)
            val tabsRow = hasProfileTabsRow(text)
            val headerStats = hasHeaderStats(text)
            FacebookPageSignals(
                finalUrl = obj.optString("url", ""),
                title = obj.optString("title", ""),
                hasLoginForm = hasPassword || hasLoginForm,
                hasLoginGate = obj.optBoolean("hasLoginGate", false),
                hasLoginInterstitial = obj.optBoolean("hasLoginInterstitial", false),
                hasLoggedInChrome = obj.optBoolean("hasLoggedInChrome", false),
                hasFollowAction = obj.optBoolean("hasFollowAction", false),
                hasPostsArea = probePostsArea || tabsRow || headerStats,
                pageText = text,
                loadError = loadError,
                rendererGone = rendererGone
            )
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // The trained classifier
    // ------------------------------------------------------------------

    private val FB_ERROR_TEXT_PHRASES = listOf(
        "something's gone wrong",
        "something went wrong",
        "we're working on getting this fixed",
        "we are working on getting this fixed"
    )

    private val LOCKED_TEXT_PHRASES = listOf(
        "profile is locked",
        "locked their profile",
        "this account is private",
        "this profile is private",
        "only their friends can see"
    )

    private val UNAVAILABLE_TEXT_PHRASES = listOf(
        "content isn't available",
        "this content isn't available",
        "content is not available",
        "the link you followed may be broken",
        "link you followed may have expired",
        "this page isn't available"
    )

    /**
     * Page classification: determines whether a page is a login wall,
     * error page, locked profile, unavailable profile, or regular content.
     * A LOGIN_WALL verdict requires the user not be logged in (neither
     * logged-in chrome nor follow action) AND positive login evidence: a
     * login/checkpoint URL, a visible login form, or a viewport-covering
     * logged-out interstitial.
     */
    fun classifyPage(signals: FacebookPageSignals): FacebookPageClass {
        val text = signals.pageText.lowercase(Locale.ROOT)
        val loggedIn = signals.hasLoggedInChrome || signals.hasFollowAction
        if (!loggedIn && (isLoginUrl(signals.finalUrl) || signals.hasLoginForm || signals.hasLoginInterstitial)) {
            return FacebookPageClass.LOGIN_WALL
        }
        if (FB_ERROR_TEXT_PHRASES.any { text.contains(it) }) {
            return FacebookPageClass.FB_ERROR_PAGE
        }
        if (LOCKED_TEXT_PHRASES.any { text.contains(it) }) {
            return FacebookPageClass.PROFILE_LOCKED
        }
        if (UNAVAILABLE_TEXT_PHRASES.any { text.contains(it) } ||
            signals.title.contains("page not found", ignoreCase = true)
        ) {
            return FacebookPageClass.PROFILE_UNAVAILABLE
        }
        return FacebookPageClass.CONTENT
    }

    fun finalStatus(
        pageClass: FacebookPageClass,
        linkCount: Int,
        requestedCount: Int,
        openFailed: Boolean = false,
        fbFailed: Boolean = false,
        postsAreaRendered: Boolean = false
    ): FacebookProfileStatus = when {
        openFailed -> FacebookProfileStatus.OPEN_FAILED
        fbFailed -> FacebookProfileStatus.FB_ERROR
        pageClass == FacebookPageClass.LOGIN_WALL -> FacebookProfileStatus.LOGIN_REQUIRED
        pageClass == FacebookPageClass.FB_ERROR_PAGE -> FacebookProfileStatus.FB_ERROR
        pageClass == FacebookPageClass.PROFILE_LOCKED -> FacebookProfileStatus.PROFILE_LOCKED
        pageClass == FacebookPageClass.PROFILE_UNAVAILABLE -> FacebookProfileStatus.PROFILE_UNAVAILABLE
        linkCount >= requestedCount -> FacebookProfileStatus.OK
        linkCount > 0 -> FacebookProfileStatus.PARTIAL
        postsAreaRendered -> FacebookProfileStatus.NO_POSTS
        else -> FacebookProfileStatus.NO_POSTS
    }

    fun resolveStatus(
        signals: FacebookPageSignals,
        linkCount: Int,
        requestedCount: Int,
        openFailed: Boolean = false
    ): FacebookProfileStatus {
        if (signals.rendererGone) return FacebookProfileStatus.FB_ERROR
        return finalStatus(
            pageClass = classifyPage(signals),
            linkCount = linkCount,
            requestedCount = requestedCount,
            openFailed = openFailed,
            postsAreaRendered = signals.hasPostsArea
        )
    }

    // ------------------------------------------------------------------
    // Escalation ladder helpers
    // ------------------------------------------------------------------

    data class MethodYield(val method: String, val links: List<String>)

    fun selectBestYield(yields: List<MethodYield>): MethodYield? =
        yields.maxByOrNull { it.links.size }

    fun shouldEscalate(bestLinkCount: Int, requestedCount: Int, budgetLeftMs: Long): Boolean =
        bestLinkCount < requestedCount && budgetLeftMs > 0

    fun hostVariant(url: String, host: String): String {
        val uri = parseUri(url) ?: return url
        val current = uri.host ?: return url
        if (!current.endsWith("facebook.com")) return url
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        return "https://$host${uri.path ?: ""}$query"
    }

    fun findPostsSectionAnchor(anchorHrefs: List<String>, profileUrl: String): String? {
        val profileUri = parseUri(profileUrl) ?: return null
        val profilePath = (profileUri.path ?: "").trimEnd('/')
        if (profilePath.isBlank()) return null
        val profileId = parseQueryParams(profileUri.rawQuery ?: "")["id"]
        for (href in anchorHrefs) {
            val uri = parseUri(href) ?: continue
            val host = uri.host?.lowercase(Locale.ROOT) ?: continue
            if (host != "facebook.com" && !host.endsWith(".facebook.com")) continue
            if (normalizePostUrl(href) != null) continue
            val path = (uri.path ?: "").trimEnd('/')
            if (path == "$profilePath/posts") return href
            if (path == profilePath) {
                val params = parseQueryParams(uri.rawQuery ?: "")
                if (params["sk"] == "posts" &&
                    (profileId == null || params["id"] == profileId)
                ) {
                    return href
                }
            }
        }
        return null
    }

    fun findSeeMoreHref(anchorsWithText: List<Pair<String, String>>): String? {
        for ((href, text) in anchorsWithText) {
            if (text.trim().lowercase(Locale.ROOT).contains("see more") && isFacebookUrl(href)) {
                return href
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // M3 — page-source mining (pure)
    // ------------------------------------------------------------------

    private const val MAX_MINING_CHARS = 2_000_000
    private val PERMALINK_URL_REGEX = Regex(""""permalink_url"\s*:\s*"([^"]+)"""")
    private val FULL_URL_REGEX = Regex("""https?://[^\s"'<>\\]+""")
    private val PATH_POST_REGEX = Regex("""(?<![\w/])/(?:[A-Za-z0-9._~%-]+/)*?(?:posts|videos)/[A-Za-z0-9_-]+""")
    private val PATH_ENDPOINT_REGEX = Regex(
        """(?<![\w/])/(?:story|permalink)\.php\?story_fbid=[A-Za-z0-9_-]+(?:&id=\d+)?""" +
            """|(?<![\w/])/photo\.php\?fbid=\d+|(?<![\w/])/photo/\?fbid=\d+|(?<![\w/])/watch/\?v=[A-Za-z0-9_-]+"""
    )
    private val TOP_LEVEL_POST_ID_REGEX = Regex(""""top_level_post_id"\s*:\s*"?(\d{8,}|pfbid[A-Za-z0-9_-]+)"?""")
    private val STORY_FBID_REGEX = Regex(""""story_fbid"\s*:\s*"?([A-Za-z0-9_-]{6,})"?""")
    private val OWNER_ID_REGEX = Regex(""""id"\s*:\s*"?(\d{8,})"?""")
    private val FBID_KEY_REGEX = Regex(""""fbid"\s*:\s*"?(\d{8,})"?""")
    private val VIDEO_ID_REGEX = Regex(""""video_id"\s*:\s*"?(\d{8,})"?""")
    private val UNICODE_ESCAPE_REGEX = Regex("""\\u([0-9a-fA-F]{4})""")

    private fun unescapeFacebookPayload(raw: String): String {
        var out = raw.replace("\\/", "/").replace("&amp;", "&")
        out = UNICODE_ESCAPE_REGEX.replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code == null) m.value else code.toChar().toString()
        }
        return out
    }

    fun mineLinksFromPageSource(source: String?, profileUrl: String): List<String> {
        if (source.isNullOrBlank()) return emptyList()
        val capped = if (source.length > MAX_MINING_CHARS) source.substring(0, MAX_MINING_CHARS) else source
        val text = unescapeFacebookPayload(capped)
        val found = LinkedHashSet<String>()
        fun add(raw: String?) {
            if (raw != null) normalizePostUrl(raw)?.let { found.add(it) }
        }
        for (m in PERMALINK_URL_REGEX.findAll(text)) {
            add(m.groupValues[1])
        }
        for (m in FULL_URL_REGEX.findAll(text)) {
            add(m.value.trimEnd(',', ']', '}', ')'))
        }
        for (m in PATH_POST_REGEX.findAll(text)) {
            add("https://m.facebook.com" + m.value)
        }
        for (m in PATH_ENDPOINT_REGEX.findAll(text)) {
            add("https://m.facebook.com" + m.value)
        }
        for (m in STORY_FBID_REGEX.findAll(text)) {
            val windowStart = maxOf(0, m.range.first - 200)
            val windowEnd = minOf(text.length, m.range.last + 201)
            val owner = OWNER_ID_REGEX.find(text.substring(windowStart, windowEnd))
                ?.groupValues?.get(1)
            if (owner != null) {
                add("https://m.facebook.com/story.php?story_fbid=${m.groupValues[1]}&id=$owner")
            }
        }
        val profilePath = (parseUri(profileUrl)?.path ?: "").trimEnd('/')
        if (profilePath.isNotBlank() && !profilePath.endsWith("profile.php")) {
            for (m in TOP_LEVEL_POST_ID_REGEX.findAll(text)) {
                add("https://m.facebook.com$profilePath/posts/${m.groupValues[1]}")
            }
            for (m in VIDEO_ID_REGEX.findAll(text)) {
                add("https://m.facebook.com$profilePath/videos/${m.groupValues[1]}")
            }
        }
        for (m in FBID_KEY_REGEX.findAll(text)) {
            add("https://m.facebook.com/photo.php?fbid=${m.groupValues[1]}")
        }
        return found.toList()
    }

    // ------------------------------------------------------------------
    // Result presentation (saved .md file + run summary)
    // ------------------------------------------------------------------

    fun statusSummaryLine(results: List<FacebookProfileResult>): String {
        if (results.isEmpty()) return "0 profiles"
        val counts = LinkedHashMap<FacebookProfileStatus, Int>()
        for (status in FacebookProfileStatus.values()) {
            val n = results.count { it.status == status }
            if (n > 0) counts[status] = n
        }
        val parts = counts.entries.joinToString(", ") { "${it.value} ${it.key.name}" }
        return "${results.size} profiles: $parts"
    }

    fun formatResultsMarkdown(
        results: List<FacebookProfileResult>,
        requestedCount: Int,
        generatedAt: Date,
        stoppedEarlyNote: String? = null
    ): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(generatedAt)
        val sb = StringBuilder()
        sb.append("# Facebook Post Links — ").append(stamp).append("\n\n")
        sb.append("Trained task (no AI): latest ").append(requestedCount)
            .append(" post links per profile.\n")
        sb.append("Summary: ").append(statusSummaryLine(results)).append("\n")
        if (!stoppedEarlyNote.isNullOrBlank()) {
            sb.append("\n> Run stopped early: ").append(stoppedEarlyNote).append("\n")
        }
        for (result in results) {
            sb.append("\n## ").append(result.normalizedUrl.ifBlank { result.profile })
                .append(" — ").append(result.status.name)
                .append(" (").append(result.links.size)
                .append(if (result.links.size == 1) " link" else " links").append(")\n")
            if (!result.note.isNullOrBlank()) {
                sb.append(result.note).append("\n")
            }
            if (!result.methodsSummary.isNullOrBlank()) {
                sb.append("Methods: ").append(result.methodsSummary).append("\n")
            }
            if (result.links.isEmpty()) {
                sb.append("_No links collected._\n")
            } else {
                result.links.forEachIndexed { index, link ->
                    sb.append(index + 1).append(". ").append(link).append("\n")
                }
            }
        }
        return sb.toString()
    }
}