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
     * The profile loads, its posts area positively rendered, and zero post
     * permalinks exist after the full effort (attempts + scroll budget).
     * Never concluded without that positive posts-area evidence — a page
     * that showed no posts area at all resolves to LOGIN_REQUIRED instead
     * (the usual cause is a logged-out gate the classifier could not see).
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
     * A logged-out gate is present WITHOUT a full login form: visible
     * anchors/CTAs to a login endpoint, or a "See more on Facebook"-style
     * nag container. This is how m.facebook.com answers logged-out
     * profile visits — a banner, not a form — so it needs its own signal.
     */
    val hasLoginGate: Boolean = false,
    /**
     * Positive evidence the posts area rendered: feed/article containers
     * or a Posts section marker in the DOM. NO_POSTS may only be
     * concluded when this is true.
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

    /**
     * Normalizes a user-supplied profile reference to the mobile site:
     * bare names / paths become `https://m.facebook.com/<name>`, any
     * facebook.com host becomes m.facebook.com, tracking query params and
     * fragments are dropped (except `profile.php?id=`, where the id IS the
     * profile). Returns "" for blank input.
     */
    /**
     * java.net.URI is strict RFC: real Facebook hrefs carry parameters
     * like `__cft__[0]=…` whose square brackets are illegal in a query
     * string and would make the constructor throw. Retry once with the
     * brackets percent-encoded (they only ever appear in tracking-param
     * names, which are stripped anyway).
     */
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
            // A bare profile/page name ("dawateislami", optionally with a
            // path) has no dot in its first segment — unlike a domain.
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
            // Input like "somepage" or "/somepage" — a bare profile name/path.
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
            // Not a Facebook URL at all — return as-is; the collector
            // records it PROFILE_UNAVAILABLE with an explanatory note.
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
        return lower.contains("/login") || lower.contains("login.php") ||
            lower.contains("/checkpoint") || lower.contains("checkpoint/")
    }

    // ------------------------------------------------------------------
    // Post permalink patterns + normalization
    // ------------------------------------------------------------------
    //
    // A URL counts as a post permalink ONLY when it matches one of these
    // canonical shapes (checked on the path/query, host must be Facebook):
    //
    //  1. /<page>/posts/<post-id>            — the standard timeline post link
    //     (also /posts/<id> and /groups/<id>/posts/<id> style prefixes)
    //  2. story.php?story_fbid=<id>          — classic story permalink
    //  3. permalink.php?story_fbid=<id>      — classic permalink endpoint
    //  4. photo.php?fbid=<id> and /photo/?fbid=<id> — a photo POST on the
    //     timeline. Both endpoint shapes exist: photo.php is the classic
    //     one, /photo/ is the shape modern Facebook serves for photo
    //     posts (a photo-heavy page's feed is full of them — missing this
    //     shape was a real extraction hole). Album *views* use
    //     /<page>/photos/a.…, which is NOT a permalink and is rejected.
    //  5. /<page>/videos/<id> and /watch/?v=<id> — a video post by the profile
    //
    // Everything else is rejected conservatively: profile roots, nav,
    // friends/notifications/search/hashtag links, comment links (they carry
    // the post path but are anchors INTO a post — after normalization they
    // dedupe onto the post itself, see below), share/dialog endpoints,
    // album links, like/comment action links and ad redirects.
    //
    // Judgement call (documented limit): /reel/<id> is NOT accepted. Reel
    // links are share-style URLs in most profile grids and accepting them
    // would mix a second content type into a "posts" list; they may be
    // added later as their own pattern if Al wants them.

    /**
     * Returns the canonical (normalized) form of [rawHref] when it is a
     * post permalink, or null when it is not. Normalization: host forced
     * to m.facebook.com, fragments dropped, and only the parameters that
     * identify the post are kept — tracking params (`__cft__`, `__tn__`,
     * `ref`, `mibextid`, …) and comment anchors (`comment_id`) are
     * stripped, so the same post reached via different decorated URLs
     * dedupes to one entry.
     */
    fun normalizePostUrl(rawHref: String): String? {
        val href = rawHref.trim()
        if (href.isBlank()) return null
        val uri = parseUri(href) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host != "facebook.com" && !host.endsWith(".facebook.com")) return null
        val path = uri.path ?: return null
        val params = parseQueryParams(uri.rawQuery ?: "")

        // Path-based permalinks: the post id lives in the path, so the
        // entire query string is decoration and is dropped.
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
                // Modern photo-post shape: /photo/?fbid=<id> (the set=
                // album context is decoration, like photo.php's).
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

    /** True when [url] is a Facebook post permalink (see pattern table above). */
    fun isPostPermalink(url: String): Boolean = normalizePostUrl(url) != null

    // /<anything>/posts/<id> — requires a non-empty id segment after "posts".
    private val POSTS_PATH_REGEX = Regex("^/.+/posts/[^/]+/?$")
    // /<anything>/videos/<id> — requires a non-empty id segment after "videos".
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

    /**
     * Filters [anchorHrefs] down to post permalinks: normalized, deduped
     * preserving first-seen order (a profile page lists posts newest
     * first, so first-seen order IS latest-first order).
     */
    fun extractPostLinks(anchorHrefs: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (href in anchorHrefs) {
            val normalized = normalizePostUrl(href) ?: continue
            seen.add(normalized)
        }
        return seen.toList()
    }

    /**
     * Same as [extractPostLinks] but takes the JSON payload produced by
     * the collector's DOM probe: a JSON array of href strings. Malformed
     * payloads yield an empty list, never a crash.
     */
    fun extractPostLinksFromJson(payload: String?): List<String> {
        if (payload.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(payload)
            val hrefs = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val h = arr.optString(i)
                if (h.isNotBlank()) hrefs.add(h)
            }
            extractPostLinks(hrefs)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Diagnostic samples for the audit trail. When a scan found anchors
     * but ZERO matched the permalink patterns, the failure is otherwise
     * invisible in the trail — so up to [limit] hrefs are recorded with
     * the probe step. Post-link-LOOKING hrefs (a post-ish marker is
     * present but the patterns rejected them — the ones that would
     * reveal a pattern gap) come first; remaining slots fall back to
     * the first anchors in DOM order (which reveal a timing/render gap:
     * only nav chrome was in the DOM). Tracking parameters are stripped
     * and each sample truncated, so the step text stays readable in a
     * screenshot. Pure.
     */
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

    /** Strips tracking decoration from an href for diagnostic display. */
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

    /**
     * Parses the page-probe JSON produced by BrowserController into
     * [FacebookPageSignals]. [loadError] / [rendererGone] come from the
     * WebViewClient's own records (JS cannot see them).
     */
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
            FacebookPageSignals(
                finalUrl = obj.optString("url", ""),
                title = obj.optString("title", ""),
                hasLoginForm = hasPassword || hasLoginForm,
                hasLoginGate = obj.optBoolean("hasLoginGate", false),
                hasPostsArea = obj.optBoolean("hasPostsArea", false),
                pageText = obj.optString("text", ""),
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
    //
    // Detection order is deliberate:
    //  1. LOGIN_WALL first — a logged-out visit to a locked or missing
    //     profile usually lands on the login wall; the wall is the
    //     actionable state (Needs you), whatever lies behind it.
    //  2. FB_ERROR_PAGE before UNAVAILABLE — FB's error page and the
    //     "isn't available" page share vocabulary; the explicit
    //     "something's gone wrong" phrasing identifies the error page.
    //  3. PROFILE_LOCKED before PROFILE_UNAVAILABLE — a locked profile
    //     states its lock explicitly; the generic unavailability text is
    //     the fallback for pages that give no lock signal.

    private val LOGIN_TEXT_PHRASES = listOf(
        "you must log in to continue",
        "log in to facebook",
        "log into facebook",
        "you must be logged in",
        "log in to continue"
    )

    /**
     * Logged-out NAG phrases — the banner m.facebook.com shows instead of
     * a feed when there is no session ("See more on Facebook — log in or
     * sign up…"). Distinct from the full login page's vocabulary: the nag
     * replaces/truncates the posts area WITHOUT any password field, which
     * is exactly how a logged-out profile visit used to fall through to
     * NO_POSTS.
     */
    private val LOGIN_NAG_TEXT_PHRASES = listOf(
        "see more on facebook",
        "log in or sign up",
        "sign up to view",
        "log in to view",
        "log in to see more",
        "join facebook to connect"
    )

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

    /** Classifies a loaded page from its probed [signals]. Pure. */
    fun classifyPage(signals: FacebookPageSignals): FacebookPageClass {
        val text = signals.pageText.lowercase(Locale.ROOT)
        if (isLoginUrl(signals.finalUrl) || signals.hasLoginForm || signals.hasLoginGate ||
            LOGIN_TEXT_PHRASES.any { text.contains(it) } ||
            LOGIN_NAG_TEXT_PHRASES.any { text.contains(it) }
        ) {
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

    /**
     * Resolves the final per-profile status from the page class, the
     * collection outcome and the failure flags. Pure.
     *
     * [openFailed] (navigation/load error surviving the one retry) and a
     * dead renderer ([FacebookPageSignals.rendererGone], or [fbFailed]
     * after the FB recovery attempt was exhausted) are decided before the
     * page class — there is no trustworthy page to classify in either case.
     */
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
        // Zero links on a content page. NO_POSTS is only honest when the
        // posts area positively rendered and stayed empty through the
        // full attempt effort. A page that never showed a posts area was
        // most likely gated (logged-out nag in a shape the classifier
        // could not see, a tab other than Posts, a feed that never
        // rendered) — blaming the profile would be a lie, so the run
        // reports the actionable state instead: log in / look by hand.
        postsAreaRendered -> FacebookProfileStatus.NO_POSTS
        else -> FacebookProfileStatus.LOGIN_REQUIRED
    }

    /**
     * One-shot convenience used by the collector and the fixtures:
     * classify [signals], then resolve the status. A dead renderer
     * resolves to FB_ERROR regardless of what the (unreadable) page held.
     */
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
    // Escalation ladder helpers (M1→M5 sequencing — pure)
    // ------------------------------------------------------------------

    /** One ladder method's harvest: the method label + its own ordered link set. */
    data class MethodYield(val method: String, val links: List<String>)

    /**
     * The method whose set becomes the profile's final set: the largest
     * harvest wins; ties keep the EARLIEST method (its order is closest
     * to the visible feed's latest-first order).
     */
    fun selectBestYield(yields: List<MethodYield>): MethodYield? =
        yields.maxByOrNull { it.links.size }

    /** The ladder continues only while the best harvest is short AND budget remains. */
    fun shouldEscalate(bestLinkCount: Int, requestedCount: Int, budgetLeftMs: Long): Boolean =
        bestLinkCount < requestedCount && budgetLeftMs > 0

    /** [url] re-hosted onto another facebook host (www / mbasic variants). */
    fun hostVariant(url: String, host: String): String {
        val uri = parseUri(url) ?: return url
        val current = uri.host ?: return url
        if (!current.endsWith("facebook.com")) return url
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        return "https://$host${uri.path ?: ""}$query"
    }

    /**
     * M2: the profile's own Posts-section anchor, as actually present in
     * the rendered page's anchors — an href is NEVER invented. Matches a
     * `/…/posts` section root for THIS profile, or the profile URL with
     * `sk=posts`. Post permalinks themselves are excluded (they have an
     * id segment after /posts).
     */
    fun findPostsSectionAnchor(anchorHrefs: List<String>, profileUrl: String): String? {
        val profileUri = parseUri(profileUrl) ?: return null
        val profilePath = (profileUri.path ?: "").trimEnd('/')
        if (profilePath.isBlank()) return null
        val profileId = parseQueryParams(profileUri.rawQuery ?: "")["id"]
        for (href in anchorHrefs) {
            val uri = parseUri(href) ?: continue
            val host = uri.host?.lowercase(Locale.ROOT) ?: continue
            if (host != "facebook.com" && !host.endsWith(".facebook.com")) continue
            if (normalizePostUrl(href) != null) continue // a permalink, not a section
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

    /** M5: the first "See more…" pagination anchor (mbasic) among [anchorsWithText]. */
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
    //
    // Facebook embeds its feed as JSON inside the page source, with URLs
    // escaped (\/ and \uXXXX). The miner unescapes first, then accepts,
    // IN PRIORITY ORDER:
    //   0. literal "permalink_url" values — the canonical links exactly
    //      as Facebook itself stores them (gold);
    //   1. full/path URLs that pass the normal permalink patterns;
    //   2. story_fbid values paired with an owner "id" in a ±200-char
    //      window (an unpaired story_fbid is NOT turned into a link —
    //      a wrong link is worse than a missing one);
    //   3. top_level_post_id values, constructed onto THIS profile's
    //      /posts/ path (the id names a post rendered on this profile);
    //   4. "fbid" keys (photo ids) → photo permalinks;
    //   5. "video_id" keys → THIS profile's /videos/<id>.
    // Precision rule: bare numbers under any OTHER key (actor_id,
    // comment ids, user ids, counts) are NEVER read — only the named
    // keys above can mint a link.

    private const val MAX_MINING_CHARS = 2_000_000
    private val PERMALINK_URL_REGEX = Regex(""""permalink_url"\s*:\s*"([^"]+)"""")
    private val FULL_URL_REGEX = Regex("""https?://[^\s"'<>\\]+""")
    private val PATH_POST_REGEX = Regex("""/(?:[A-Za-z0-9._~%-]+/)*?(?:posts|videos)/[A-Za-z0-9_-]+""")
    private val PATH_ENDPOINT_REGEX = Regex(
        """/(?:story|permalink)\.php\?story_fbid=[A-Za-z0-9_-]+(?:&id=\d+)?""" +
            """|/photo\.php\?fbid=\d+|/photo/\?fbid=\d+|/watch/\?v=[A-Za-z0-9_-]+"""
    )
    private val TOP_LEVEL_POST_ID_REGEX = Regex(""""top_level_post_id"\s*:\s*"?(\d{8,}|pfbid[A-Za-z0-9_-]+)"?""")
    private val STORY_FBID_REGEX = Regex(""""story_fbid"\s*:\s*"?([A-Za-z0-9_-]{6,})"?""")
    private val OWNER_ID_REGEX = Regex(""""id"\s*:\s*"?(\d{8,})"?""")
    private val FBID_KEY_REGEX = Regex(""""fbid"\s*:\s*"?(\d{8,})"?""")
    private val VIDEO_ID_REGEX = Regex(""""video_id"\s*:\s*"?(\d{8,})"?""")
    private val UNICODE_ESCAPE_REGEX = Regex("""\\u([0-9a-fA-F]{4})""")

    /** Undoes Facebook's JSON escaping: `\/` → `/`, `&amp;` → `&`, `\uXXXX` → the char. */
    private fun unescapeFacebookPayload(raw: String): String {
        var out = raw.replace("\\/", "/").replace("&amp;", "&")
        out = UNICODE_ESCAPE_REGEX.replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code == null) m.value else code.toChar().toString()
        }
        return out
    }

    /**
     * Mines [source] (raw page HTML/source) for post permalinks of the
     * profile at [profileUrl]. Deterministic order = discovery order;
     * every candidate passes [normalizePostUrl] before it counts.
     */
    fun mineLinksFromPageSource(source: String?, profileUrl: String): List<String> {
        if (source.isNullOrBlank()) return emptyList()
        val capped = if (source.length > MAX_MINING_CHARS) source.substring(0, MAX_MINING_CHARS) else source
        val text = unescapeFacebookPayload(capped)
        val found = LinkedHashSet<String>()
        fun add(raw: String?) {
            if (raw != null) normalizePostUrl(raw)?.let { found.add(it) }
        }
        // 0) permalink_url — Facebook's own canonical links, first.
        for (m in PERMALINK_URL_REGEX.findAll(text)) {
            add(m.groupValues[1])
        }
        // 1) Full URLs (after unescaping, escaped payload URLs are whole).
        for (m in FULL_URL_REGEX.findAll(text)) {
            add(m.value.trimEnd(',', ']', '}', ')'))
        }
        // 1b) Path-only post/endpoint shapes (host prepended; normalize validates).
        for (m in PATH_POST_REGEX.findAll(text)) {
            add("https://m.facebook.com" + m.value)
        }
        for (m in PATH_ENDPOINT_REGEX.findAll(text)) {
            add("https://m.facebook.com" + m.value)
        }
        // 2) story_fbid ONLY when an owner id sits in the same neighbourhood.
        for (m in STORY_FBID_REGEX.findAll(text)) {
            val windowStart = maxOf(0, m.range.first - 200)
            val windowEnd = minOf(text.length, m.range.last + 201)
            val owner = OWNER_ID_REGEX.find(text.substring(windowStart, windowEnd))
                ?.groupValues?.get(1)
            if (owner != null) {
                add("https://m.facebook.com/story.php?story_fbid=${m.groupValues[1]}&id=$owner")
            }
        }
        // 3–5) Id-key constructions. top_level_post_id / video_id need
        //      THIS profile's path — vanity profiles only (profile.php
        //      has no /posts/ or /videos/ root).
        val profilePath = (parseUri(profileUrl)?.path ?: "").trimEnd('/')
        if (profilePath.isNotBlank() && !profilePath.endsWith("profile.php")) {
            for (m in TOP_LEVEL_POST_ID_REGEX.findAll(text)) {
                add("https://m.facebook.com$profilePath/posts/${m.groupValues[1]}")
            }
            for (m in VIDEO_ID_REGEX.findAll(text)) {
                add("https://m.facebook.com$profilePath/videos/${m.groupValues[1]}")
            }
        }
        // 4) "fbid" keys are photo ids in Facebook payloads.
        for (m in FBID_KEY_REGEX.findAll(text)) {
            add("https://m.facebook.com/photo.php?fbid=${m.groupValues[1]}")
        }
        return found.toList()
    }

    // ------------------------------------------------------------------
    // Result presentation (saved .md file + run summary)
    // ------------------------------------------------------------------

    /**
     * The grouped status summary, e.g.
     * "12 profiles: 8 OK, 2 PARTIAL, 1 PROFILE_LOCKED, 1 PROFILE_UNAVAILABLE".
     */
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

    /**
     * The durable result file: one section per profile carrying its status
     * line alongside its links. [stoppedEarlyNote] is included verbatim
     * when the chain was cut short (e.g. a login pause that timed out).
     */
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
