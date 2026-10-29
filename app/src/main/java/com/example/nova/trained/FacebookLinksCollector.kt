package com.example.nova.trained

import com.example.nova.browser.BrowserControlMode
import com.example.nova.browser.BrowserController
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * The deterministic core of Trained Task #1 — Facebook Post Links
 * Collector. ZERO provider/LLM calls: the same core is driven by the
 * Tasks-screen form (Mode 1, via NovaRepository) and by the agent's
 * `collect_facebook_post_links` tool (Mode 2, via ToolExecutor).
 *
 * Behaviour contract (the "training"):
 * - Profiles are visited strictly in list order — the chain NEVER stops
 *   for a per-profile failure; the failure is recorded with its exact
 *   status from the taxonomy and the run moves to the next profile.
 * - The ONLY pause is a login/checkpoint wall (LOGIN_REQUIRED /
 *   Needs you): [Hooks.onLoginWall] decides what happens (Mode 1 waits
 *   for the user to log in and hand control back; Mode 2 skips at once).
 * - Recovery budgets are fixed: ONE open retry for load failures, ONE
 *   reload for an FB error page, ONE WebView recreation for a renderer
 *   crash, ONE login pause per profile. No silent extra retries.
 * - Collection stops at the requested count, at the page bottom, after
 *   3 consecutive scrolls with no new links, or at the scroll cap.
 * - Round A: the requested count is configurable 1–100 and the budgets
 *   scale with it — per-profile time budget = 180s + 6s per requested
 *   link (ceiling 900s), scroll caps grow linearly (see
 *   [FacebookPostLinks.perProfileBudgetMs] / [FacebookPostLinks.maxScrollsFor]).
 * - Round A: a run carries an [FacebookExtractionMode] — HYBRID runs
 *   the full ladder, M1_ONLY uses rendered-DOM methods only (no
 *   mining, no desktop variant), M3_ONLY runs the source-mining loop
 *   alone. Every accepted link remembers which method first found it
 *   ([FacebookPostLinks.LinkProvenanceTracker]).
 */
class FacebookLinksCollector(
    private val browserController: BrowserController
) {
    /** What the login-wall hook decided (Mode 1 waits; Mode 2 skips). */
    enum class LoginWallDecision { RETRY_PROFILE, SKIP_PROFILE, STOP_RUN }

    /**
     * Bridges between the core and its host. Mode 1 (NovaRepository)
     * turns steps into audit records and the waits into task-state
     * changes (WAITING + notification); Mode 2 (ToolExecutor) no-ops the
     * steps and never pauses.
     */
    interface Hooks {
        /** An audit-worthy event (profile start/finish, recovery, wall). */
        suspend fun onStep(message: String)

        /** The run started waiting for the user to hand the browser back. */
        suspend fun onWaitStart(reason: String)

        /** The wait ended; [resumed] = control returned to the agent. */
        suspend fun onWaitEnd(resumed: Boolean)

        /** A login/checkpoint wall was hit on [profileUrl]. */
        suspend fun onLoginWall(profileUrl: String): LoginWallDecision
    }

    /** The full run's outcome: every profile's result, in order. */
    data class CollectRunOutcome(
        val results: List<FacebookProfileResult>,
        /** True when the chain was cut short (a Needs-you wait timed out). */
        val stoppedEarly: Boolean = false,
        val stopReason: String? = null,
        /** True when the early stop was a LOGIN pause that timed out / was stopped — drives the task's final line. */
        val stoppedForLogin: Boolean = false
    )

    /** Internal control-flow signal for "abort the whole chain now". */
    private class AbortRunException(
        val result: FacebookProfileResult?,
        val reason: String,
        /** True when this abort is a login wait that ended without a login. */
        val stoppedForLogin: Boolean = false
    ) : Exception(reason)

    private data class Attempt(
        val pageClass: FacebookPageClass?,
        val openError: String?,
        val rendererGone: Boolean = false
    )

    private data class CollectOutcome(
        val links: List<String>,
        val loginDetected: Boolean = false,
        val rendererDied: Boolean = false,
        val restart: Boolean = false,
        /** Positive posts-area evidence seen during collection (gates NO_POSTS). */
        val postsAreaRendered: Boolean = false,
        /** How many discovery attempts were spent (diagnostics). */
        val attemptsUsed: Int = 0
    )

    companion object {
        const val LOAD_TIMEOUT_MS = 12_000L
        const val MAX_SCROLLS = 15
        const val MAX_DRY_SCROLLS = 3
        const val CONTROL_WAIT_TIMEOUT_MS = 10 * 60_000L
        const val MAX_PROFILE_RESTARTS = 3

        /** Discovery attempts before a zero-link conclusion is allowed. */
        const val MAX_ATTEMPTS = 3

        /** Settle after navigation / reload, before the first extraction of an attempt. */
        const val SETTLE_MS = 1_500L

        /** Settle between discovery attempts (after the coaxing scrolls). */
        const val SETTLE_BETWEEN_ATTEMPTS_MS = 1_200L

        /**
         * Minimum wall-clock effort before ANY zero-link conclusion
         * (NO_POSTS / gated) may be returned for a profile. The attempt
         * delays normally exceed this; the floor makes a ~2-second
         * conclusion impossible by construction.
         */
        const val MIN_ZERO_EFFORT_MS = 8_000L

        // Round A: the per-profile time budget and the scroll caps are
        // count-aware — see FacebookPostLinks.perProfileBudgetMs /
        // maxScrollsFor / fallbackScrollsFor for the formulas. The old
        // flat 180s budget and 15-scroll cap live on as the N→small
        // ends of those formulas.

        /** Longer settle for the desktop variant (M4) — heavier pages. */
        const val DESKTOP_SETTLE_MS = 2_500L
    }

    // ------------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------------

    /** Mode 2 entry: collect from ONE profile. Never pauses in AI mode because the AI hooks answer at once. */
    suspend fun collectOne(
        rawProfile: String,
        count: Int,
        hooks: Hooks,
        mode: FacebookExtractionMode = FacebookExtractionMode.HYBRID
    ): FacebookProfileResult {
        val safeCount = FacebookPostLinks.clampPostsPerProfile(count)
        return try {
            collectProfile(rawProfile, safeCount, hooks, mode)
        } catch (e: AbortRunException) {
            e.result ?: FacebookProfileResult(
                profile = rawProfile,
                normalizedUrl = FacebookPostLinks.normalizeProfileUrl(rawProfile),
                status = FacebookProfileStatus.LOGIN_REQUIRED,
                note = e.reason
            )
        }
    }

    /** Mode 1 entry: the full chain, in order, whatever each profile does. */
    suspend fun collectAll(
        rawProfiles: List<String>,
        count: Int,
        hooks: Hooks,
        mode: FacebookExtractionMode = FacebookExtractionMode.HYBRID,
        onProfileDone: suspend (FacebookProfileResult) -> Unit = {}
    ): CollectRunOutcome {
        val safeCount = FacebookPostLinks.clampPostsPerProfile(count)
        val results = mutableListOf<FacebookProfileResult>()
        try {
            for ((index, raw) in rawProfiles.withIndex()) {
                currentCoroutineContext().ensureActive()
                val normalized = FacebookPostLinks.normalizeProfileUrl(raw)
                hooks.onStep("Profile ${index + 1}/${rawProfiles.size}: ${normalized.ifBlank { raw }}")
                val result = collectProfile(raw, safeCount, hooks, mode)
                results.add(result)
                onProfileDone(result)
                hooks.onStep(
                    "${result.normalizedUrl.ifBlank { result.profile }} — ${result.status.name} " +
                        "(${result.links.size} link${if (result.links.size == 1) "" else "s"})" +
                        (result.note?.let { " — $it" } ?: "")
                )
            }
        } catch (e: AbortRunException) {
            if (e.result != null) {
                results.add(e.result)
                onProfileDone(e.result)
            }
            return CollectRunOutcome(
                results,
                stoppedEarly = true,
                stopReason = e.reason,
                stoppedForLogin = e.stoppedForLogin
            )
        }
        return CollectRunOutcome(results)
    }

    // ------------------------------------------------------------------
    // Per-profile orchestration (retries / recoveries / pause)
    // ------------------------------------------------------------------

    private suspend fun collectProfile(
        rawProfile: String,
        count: Int,
        hooks: Hooks,
        mode: FacebookExtractionMode
    ): FacebookProfileResult {
        val normalized = FacebookPostLinks.normalizeProfileUrl(rawProfile)
        // Per-link provenance for this profile. Links already harvested
        // survive restarts/recoveries — they were really on the page;
        // never lose them, and never lose which method found them.
        val tracker = FacebookPostLinks.LinkProvenanceTracker()
        fun attrFor(links: List<String>): Map<String, FacebookLinkAttribution> {
            val recorded = tracker.attribution()
            return links.mapNotNull { link -> recorded[link]?.let { link to it } }.toMap()
        }
        fun result(
            status: FacebookProfileStatus,
            links: List<String> = emptyList(),
            note: String? = null,
            attribution: Map<String, FacebookLinkAttribution> = emptyMap()
        ) = FacebookProfileResult(rawProfile, normalized, status, links, note, linkAttribution = attribution)

        if (normalized.isBlank() || !FacebookPostLinks.isFacebookUrl(normalized)) {
            return result(
                FacebookProfileStatus.PROFILE_UNAVAILABLE,
                note = "Not a Facebook profile URL — nothing to visit."
            )
        }

        val profileStartMs = System.currentTimeMillis()
        val budgetMs = FacebookPostLinks.perProfileBudgetMs(count)
        val maxScrolls = FacebookPostLinks.maxScrollsFor(count)
        var loginPauseUsed = false
        var openRetries = 0
        var fbRecoveries = 0
        var restarts = 0

        while (true) {
            currentCoroutineContext().ensureActive()

            // The browser must be ours before we touch it. If the user is
            // holding it, wait for the hand-back (Needs you); if nobody
            // is, claim it (the touch shield then guards the run).
            if (!ensureAgentControl(hooks)) {
                throw AbortRunException(null, "Browser control was not handed back in time — run stopped.")
            }

            val attempt = attemptLoad(normalized)

            // --- FB failure: renderer crash → recreate the WebView once.
            if (attempt.rendererGone) {
                if (fbRecoveries < 1) {
                    fbRecoveries++
                    hooks.onStep("Facebook crashed the browser renderer on $normalized — recreating the browser once")
                    browserController.recreateWebView()
                    continue
                }
                return result(
                    FacebookProfileStatus.FB_ERROR,
                    note = "The browser's renderer crashed on this profile."
                )
            }

            // --- Open failure: exactly ONE retry, then OPEN_FAILED.
            if (attempt.openError != null) {
                if (openRetries < 1) {
                    openRetries++
                    hooks.onStep("Open failed for $normalized (${attempt.openError}) — retrying once")
                    delay(800)
                    continue
                }
                return result(
                    FacebookProfileStatus.OPEN_FAILED,
                    note = "The page failed to load: ${attempt.openError}"
                )
            }

            when (attempt.pageClass) {
                FacebookPageClass.FB_ERROR_PAGE -> {
                    if (fbRecoveries < 1) {
                        fbRecoveries++
                        hooks.onStep("Facebook error page on $normalized — reloading once")
                        browserController.reloadAction()
                        browserController.waitForLoad(LOAD_TIMEOUT_MS)
                        continue
                    }
                    return result(
                        FacebookProfileStatus.FB_ERROR,
                        note = "Facebook returned its error page for this profile."
                    )
                }

                FacebookPageClass.PROFILE_LOCKED ->
                    return result(FacebookProfileStatus.PROFILE_LOCKED, note = "This profile is locked / private — posts are not visible.")

                FacebookPageClass.PROFILE_UNAVAILABLE ->
                    return result(FacebookProfileStatus.PROFILE_UNAVAILABLE, note = "Facebook says this content isn't available (banned, deactivated or not found).")

                FacebookPageClass.CONTENT, FacebookPageClass.LOGIN_WALL, null -> {
                    val initialWall = (attempt.pageClass == FacebookPageClass.LOGIN_WALL)
                    val outcome = collectLinks(normalized, count, hooks, tracker, mode, maxScrolls, budgetMs)
                    when {
                        outcome.restart -> {
                            restarts++
                            if (restarts > MAX_PROFILE_RESTARTS) {
                                return result(
                                    FacebookProfileStatus.OPEN_FAILED,
                                    outcome.links,
                                    "Collection kept being interrupted by manual control.",
                                    attrFor(outcome.links)
                                )
                            }
                            continue
                        }
                        outcome.rendererDied -> {
                            if (fbRecoveries < 1) {
                                fbRecoveries++
                                hooks.onStep("Renderer crashed while collecting $normalized — recreating the browser once")
                                browserController.recreateWebView()
                                continue
                            }
                            return result(
                                FacebookProfileStatus.FB_ERROR,
                                outcome.links,
                                "The browser's renderer crashed while collecting.",
                                attrFor(outcome.links)
                            )
                        }
                        outcome.loginDetected && outcome.links.isEmpty() -> {
                            when (handleLoginWall(normalized, hooks, loginPauseUsed)) {
                                LoginFlow.RESUMED -> {
                                    loginPauseUsed = true
                                    continue
                                }
                                LoginFlow.GIVE_UP -> return result(
                                    FacebookProfileStatus.LOGIN_REQUIRED,
                                    outcome.links,
                                    "Facebook asked for a login mid-collection (Needs you)."
                                )
                                LoginFlow.ABORT_RUN -> throw AbortRunException(
                                    result(
                                        FacebookProfileStatus.LOGIN_REQUIRED,
                                        outcome.links,
                                        "Login was not completed — the run stopped here."
                                    ),
                                    "Login was not completed in time — run stopped.",
                                    stoppedForLogin = true
                                )
                            }
                        }
                        mode == FacebookExtractionMode.M3_ONLY -> {
                            // M3-only runs have no ladder: collectLinks
                            // already ran the source-mining loop alone.
                            // Resolve straight from its outcome.
                            val finalLinks = outcome.links.take(count)
                            val methodsSummary = "M3 source mining: ${finalLinks.size} " +
                                "${if (finalLinks.size == 1) "link" else "links"}" +
                                " — final set from M3 source mining"
                            hooks.onStep("Methods for $normalized — $methodsSummary")
                            val status = FacebookPostLinks.finalStatus(
                                FacebookPageClass.CONTENT, finalLinks.size, count,
                                postsAreaRendered = outcome.postsAreaRendered
                            )
                            val note = when (status) {
                                FacebookProfileStatus.PARTIAL ->
                                    "Only ${finalLinks.size} of $count posts were found on this profile (M3 source mining)."
                                FacebookProfileStatus.NO_POSTS ->
                                    "M3 source mining was tried, but no post permalinks were found on this profile."
                                else -> null
                            }
                            return FacebookProfileResult(
                                profile = rawProfile,
                                normalizedUrl = normalized,
                                status = status,
                                links = finalLinks,
                                note = note,
                                methodsSummary = methodsSummary,
                                linkAttribution = attrFor(finalLinks)
                            )
                        }
                        else -> {
                            // ---- Escalation ladder M1→M5 ----
                            // M1 (mobile anchors) is done: [outcome]. While
                            // its harvest is short and the per-profile
                            // budget remains, the ladder escalates: M2 the
                            // profile's own Posts section → M3 source
                            // mining merged WITH M1's set → M4 desktop →
                            // M5 mbasic. The final set comes from the best
                            // candidate; every method's yield is recorded
                            // in the audit trail. Terminal classifications
                            // (locked/unavailable/login) never reach here —
                            // they short-circuit in M1 above.
                            fun linkWord(n: Int) = if (n == 1) "link" else "links"
                            val candidates = mutableListOf<FacebookPostLinks.MethodYield>()
                            val summaryParts = mutableListOf<String>()
                            candidates += FacebookPostLinks.MethodYield("M1 mobile", outcome.links)
                            summaryParts += "M1 mobile: ${outcome.links.size} ${linkWord(outcome.links.size)}"
                            var postsAreaEvidence = outcome.postsAreaRendered
                            var wallSeen = initialWall || outcome.loginDetected

                            fun budgetLeft(): Long =
                                budgetMs - (System.currentTimeMillis() - profileStartMs)
                            fun bestSoFar(): Int = candidates.maxOf { it.links.size }
                            suspend fun manualControlStop(): Boolean {
                                if (browserController.controlMode.value !=
                                    BrowserControlMode.MANUAL_CONTROL
                                ) {
                                    return false
                                }
                                hooks.onStep(
                                    "Ladder stopped for $normalized — the browser is under your control; " +
                                        "concluding with the best set so far"
                                )
                                return true
                            }

                            // M2 — the profile's own Posts-section anchor.
                            if (FacebookPostLinks.shouldEscalate(bestSoFar(), count, budgetLeft()) &&
                                !manualControlStop()
                            ) {
                                browserController.scrollUp(3.0)
                                delay(400)
                                val anchorsNow = browserController.extractAnchorHrefs().orEmpty()
                                val sectionHref =
                                    FacebookPostLinks.findPostsSectionAnchor(anchorsNow, normalized)
                                if (sectionHref == null) {
                                    hooks.onStep("M2 posts section: no Posts tab anchor found in the page — skipped")
                                } else {
                                    val nav = browserController.navigate(sectionHref)
                                    if (!nav.success) {
                                        hooks.onStep("M2 posts section: navigation failed — skipped")
                                    } else {
                                        browserController.waitForLoad(LOAD_TIMEOUT_MS)
                                        val h = harvestCurrentPage(
                                            count,
                                            FacebookPostLinks.fallbackScrollsFor(count),
                                            SETTLE_MS,
                                            domMethod = FacebookPostLinks.METHOD_M2,
                                            allowMining = mode != FacebookExtractionMode.M1_ONLY
                                        )
                                        if (h.loginWall) {
                                            wallSeen = true
                                            hooks.onStep("M2 posts section: hit a login wall — skipped (login pauses come only after the full ladder)")
                                        } else {
                                            tracker.absorb(h.tracker)
                                            candidates += FacebookPostLinks.MethodYield("M2 posts section", h.links)
                                            summaryParts += "M2 posts section: ${h.links.size} ${linkWord(h.links.size)}"
                                            if (h.postsAreaRendered) postsAreaEvidence = true
                                            hooks.onStep("M2 posts section: ${h.links.size} link(s)")
                                            if (h.rendererDied) {
                                                hooks.onStep("The browser renderer stopped during M2 — continuing the ladder")
                                            }
                                        }
                                    }
                                }
                            }

                            // M3 — source mining, merged WITH the M1 set
                            // (combined scroll+mine loop on the profile).
                            // HYBRID only: M1_ONLY forbids mining, and an
                            // M3_ONLY run never reaches the ladder.
                            if (mode == FacebookExtractionMode.HYBRID &&
                                FacebookPostLinks.shouldEscalate(bestSoFar(), count, budgetLeft()) &&
                                !manualControlStop()
                            ) {
                                val navBack = browserController.navigate(normalized)
                                if (!navBack.success) {
                                    hooks.onStep("M3 source mining: could not reopen the profile — skipped")
                                } else {
                                    browserController.waitForLoad(LOAD_TIMEOUT_MS)
                                    delay(SETTLE_MS)
                                    val added = runSourceMiningLoop(
                                        normalized, count, tracker, profileStartMs, budgetMs, maxScrolls
                                    )
                                    val combined = tracker.links()
                                    candidates += FacebookPostLinks.MethodYield(
                                        "M1+M3 combined", combined
                                    )
                                    summaryParts += "M3 source mining: +$added new (${combined.size} total)"
                                    hooks.onStep("M3 source mining: +$added new link(s), ${combined.size} total with M1")
                                }
                            }

                            // M4 — desktop variant (mobile UA ALWAYS restored).
                            // HYBRID only (M1_ONLY has no desktop variant).
                            if (mode == FacebookExtractionMode.HYBRID &&
                                FacebookPostLinks.shouldEscalate(bestSoFar(), count, budgetLeft()) &&
                                !manualControlStop()
                            ) {
                                var m4Links: List<String>? = null
                                var m4Tracker: FacebookPostLinks.LinkProvenanceTracker? = null
                                try {
                                    if (browserController.setDesktopMode(true)) {
                                        val www = FacebookPostLinks.hostVariant(
                                            normalized, "www.facebook.com"
                                        )
                                        val nav = browserController.navigate(www)
                                        if (nav.success) {
                                            browserController.waitForLoad(LOAD_TIMEOUT_MS)
                                            val h = harvestCurrentPage(
                                                count,
                                                FacebookPostLinks.fallbackScrollsFor(count),
                                                DESKTOP_SETTLE_MS,
                                                domMethod = FacebookPostLinks.METHOD_M4,
                                                allowMining = true
                                            )
                                            if (!h.loginWall) {
                                                val mined = FacebookPostLinks.mineLinksFromPageSource(
                                                    browserController.extractPageSource(), normalized
                                                )
                                                h.tracker.addAll(mined, FacebookPostLinks.METHOD_M3)
                                                m4Tracker = h.tracker
                                                m4Links = h.tracker.links()
                                                if (h.postsAreaRendered) postsAreaEvidence = true
                                            } else {
                                                wallSeen = true
                                            }
                                        }
                                    }
                                } finally {
                                    browserController.setDesktopMode(false)
                                }
                                if (m4Tracker != null) tracker.absorb(m4Tracker)
                                if (m4Links != null) {
                                    candidates += FacebookPostLinks.MethodYield("M4 desktop", m4Links)
                                    summaryParts += "M4 desktop: ${m4Links.size} ${linkWord(m4Links.size)}"
                                    hooks.onStep("M4 desktop: ${m4Links.size} link(s)")
                                } else {
                                    hooks.onStep("M4 desktop: skipped (login wall or load failure on the desktop variant)")
                                }
                            }

                            // M5 — mbasic variant + at most 2 "See more" follows.
                            if (FacebookPostLinks.shouldEscalate(bestSoFar(), count, budgetLeft()) &&
                                !manualControlStop()
                            ) {
                                val mbasic = FacebookPostLinks.hostVariant(
                                    normalized, "mbasic.facebook.com"
                                )
                                val nav = browserController.navigate(mbasic)
                                if (!nav.success) {
                                    hooks.onStep("M5 mbasic: navigation failed — skipped")
                                } else {
                                    browserController.waitForLoad(LOAD_TIMEOUT_MS)
                                    delay(SETTLE_MS)
                                    val probe5 = browserController.extractPageProbeJson()?.let {
                                        FacebookPostLinks.signalsFromProbeJson(
                                            it, rendererGone = browserController.rendererCrashed
                                        )
                                    }
                                    val walled5 = browserController.rendererCrashed ||
                                        FacebookPostLinks.isLoginUrl(
                                            browserController.getCurrentUrl().data.orEmpty()
                                        ) ||
                                        (probe5 != null &&
                                            FacebookPostLinks.classifyPage(probe5) ==
                                            FacebookPageClass.LOGIN_WALL)
                                    if (walled5) {
                                        wallSeen = true
                                        hooks.onStep("M5 mbasic: hit a login wall — skipped (login pauses come only after the full ladder)")
                                    } else {
                                        if (probe5?.hasPostsArea == true) postsAreaEvidence = true
                                        val tracker5 = FacebookPostLinks.LinkProvenanceTracker()
                                        var follows = 0
                                        while (true) {
                                            currentCoroutineContext().ensureActive()
                                            val currentM5Url = browserController.getCurrentUrl().data.orEmpty()
                                            val hrefs = browserController.extractAnchorHrefs()
                                            if (hrefs != null) {
                                                tracker5.addAll(
                                                    FacebookPostLinks.extractPostLinks(hrefs, currentM5Url),
                                                    FacebookPostLinks.METHOD_M5
                                                )
                                            }
                                            if (mode != FacebookExtractionMode.M1_ONLY) {
                                                val pageSource = browserController.extractPageSource()
                                                tracker5.addAll(
                                                    FacebookPostLinks.mineLinksFromPageSource(pageSource, currentM5Url),
                                                    FacebookPostLinks.METHOD_M3
                                                )
                                            }
                                            if (tracker5.size >= count || follows >= 2) break
                                            val withText =
                                                 browserController.extractAnchorsWithText() ?: break
                                            val more =
                                                 FacebookPostLinks.findSeeMoreHref(withText) ?: break
                                            if (!browserController.navigate(more).success) break
                                            follows++
                                            browserController.waitForLoad(LOAD_TIMEOUT_MS)
                                            delay(800)
                                        }
                                        tracker.absorb(tracker5)
                                        val links5 = tracker5.links()
                                        candidates += FacebookPostLinks.MethodYield(
                                            "M5 mbasic", links5
                                        )
                                        summaryParts += "M5 mbasic: ${links5.size} ${linkWord(links5.size)}"
                                        hooks.onStep("M5 mbasic: ${links5.size} link(s)")
                                    }
                                }
                            }

                            // ---- Final resolution: the best candidate wins ----
                            val best = FacebookPostLinks.selectBestYield(candidates)!!
                            val finalLinks = best.links.take(count)

                            if (finalLinks.isEmpty() && wallSeen) {
                                when (handleLoginWall(normalized, hooks, loginPauseUsed)) {
                                    LoginFlow.RESUMED -> {
                                        loginPauseUsed = true
                                        continue
                                    }
                                    LoginFlow.GIVE_UP -> return result(
                                        FacebookProfileStatus.LOGIN_REQUIRED,
                                        finalLinks,
                                        note = "A Facebook login is required to see this profile (Needs you)."
                                    )
                                    LoginFlow.ABORT_RUN -> throw AbortRunException(
                                        result(
                                            FacebookProfileStatus.LOGIN_REQUIRED,
                                            finalLinks,
                                            note = "Login was not completed — the run stopped here."
                                        ),
                                        "Login was not completed in time — run stopped.",
                                        stoppedForLogin = true
                                    )
                                }
                            }

                            val methodsSummary = summaryParts.joinToString("; ") +
                                " — final set from ${best.method}"
                            hooks.onStep("Methods for $normalized — $methodsSummary")
                            val status = FacebookPostLinks.finalStatus(
                                FacebookPageClass.CONTENT, finalLinks.size, count,
                                postsAreaRendered = postsAreaEvidence
                            )
                            val note = when (status) {
                                FacebookProfileStatus.PARTIAL ->
                                    "Only ${finalLinks.size} of $count posts were found on this profile (best method: ${best.method})."
                                FacebookProfileStatus.NO_POSTS ->
                                    "Every method was tried, but no post permalinks were found on this profile."
                                FacebookProfileStatus.LOGIN_REQUIRED ->
                                    "Every method was tried — Facebook showed no posts for this profile; it may be asking for a login (Needs you)."
                                else -> null
                            }
                            val finalAttribution = attrFor(finalLinks).toMutableMap()
                            for (link in finalLinks) {
                                if (link !in finalAttribution) {
                                    // Defensive fallback: every final link
                                    // should already carry provenance; if one
                                    // slipped through, credit the winning
                                    // method rather than dropping attribution.
                                    finalAttribution[link] = FacebookLinkAttribution(
                                        FacebookPostLinks.methodCodeForYieldLabel(best.method),
                                        System.currentTimeMillis()
                                    )
                                }
                            }
                            return FacebookProfileResult(
                                profile = rawProfile,
                                normalizedUrl = normalized,
                                status = status,
                                links = finalLinks,
                                note = note,
                                methodsSummary = methodsSummary,
                                linkAttribution = finalAttribution
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // One load attempt: navigate → wait → probe → classify
    // ------------------------------------------------------------------

    private suspend fun attemptLoad(normalized: String): Attempt {
        val nav = browserController.navigate(normalized)
        if (!nav.success) {
            // navigate() refuses under manual control — the caller's
            // control check normally prevents this; report it as an open
            // failure with the honest reason.
            return Attempt(null, nav.message)
        }
        val wait = browserController.waitForLoad(LOAD_TIMEOUT_MS)
        if (browserController.rendererCrashed) {
            return Attempt(null, null, rendererGone = true)
        }
        if (!wait.success) {
            return Attempt(null, wait.message)
        }
        browserController.lastLoadError?.let { err ->
            return Attempt(null, "Load error ${err.errorCode}: ${err.description}")
        }
        val probeJson = browserController.extractPageProbeJson()
            ?: return Attempt(null, "The loaded page could not be read")
        val signals = FacebookPostLinks.signalsFromProbeJson(
            probeJson,
            loadError = browserController.lastLoadError != null,
            rendererGone = browserController.rendererCrashed
        ) ?: return Attempt(null, "The loaded page could not be read")
        return Attempt(FacebookPostLinks.classifyPage(signals), null)
    }

    // ------------------------------------------------------------------
    // The collection loop, in two phases.
    //
    // Phase 1 — DISCOVERY ATTEMPTS (only while zero links are found).
    // A zero-link conclusion may never rest on a single instant
    // extraction: attempt 1 settles after navigation, attempt 2 scrolls
    // to coax lazy rendering, attempt 3 reloads once. Every attempt
    // re-extracts from the FRESH DOM and records a probe step (final
    // URL, logged-in signal, anchors scanned, links found, timing, and
    // — when anchors existed but none matched — sample hrefs), and the
    // MIN_ZERO_EFFORT_MS floor makes a ~2-second conclusion impossible
    // by construction.
    //
    // Phase 2 — TOP-UP (links exist): the original dry-scroll loop —
    // extract → scroll → extract … until N, the bottom, 3 dry scrolls
    // or the shared scroll budget runs out.
    // ------------------------------------------------------------------

    private suspend fun collectLinks(
        normalized: String,
        count: Int,
        hooks: Hooks,
        tracker: FacebookPostLinks.LinkProvenanceTracker,
        mode: FacebookExtractionMode,
        maxScrolls: Int,
        budgetMs: Long
    ): CollectOutcome {
        // M3_ONLY never runs the anchor phases: the mining loop IS the
        // collection. (The ladder in collectProfile is skipped for it.)
        if (mode == FacebookExtractionMode.M3_ONLY) {
            return collectViaMining(normalized, count, hooks, tracker, maxScrolls, budgetMs)
        }
        // M1_ONLY forbids page-source mining everywhere, M1 phase included.
        val allowMining = mode != FacebookExtractionMode.M1_ONLY
        var scrolls = 0
        var postsAreaRendered = false
        var attemptsUsed = 0
        val collectionStart = System.currentTimeMillis()

        // ---------------- Phase 1: discovery attempts ----------------
        while (tracker.isEmpty() && attemptsUsed < MAX_ATTEMPTS) {
            attemptsUsed++
            val attemptStart = System.currentTimeMillis()
            when (attemptsUsed) {
                1 -> delay(SETTLE_MS) // let the feed render after navigation
                2 -> {
                    var n = 0
                    while (n < 2 && scrolls < maxScrolls) {
                        browserController.scrollDown(1.0)
                        scrolls++
                        n++
                        delay(400)
                    }
                    delay(SETTLE_BETWEEN_ATTEMPTS_MS)
                }
                3 -> {
                    hooks.onStep("Still no post links on $normalized — reloading once (attempt $MAX_ATTEMPTS/$MAX_ATTEMPTS)")
                    browserController.reloadAction()
                    browserController.waitForLoad(LOAD_TIMEOUT_MS)
                    delay(SETTLE_MS)
                }
            }
            currentCoroutineContext().ensureActive()

            if (browserController.rendererCrashed) {
                return CollectOutcome(
                    tracker.links(), rendererDied = true,
                    postsAreaRendered = postsAreaRendered, attemptsUsed = attemptsUsed
                )
            }
            // A checkpoint can interrupt at any time: the URL gives it away.
            val currentUrl = browserController.getCurrentUrl().data.orEmpty()
            if (FacebookPostLinks.isLoginUrl(currentUrl)) {
                return CollectOutcome(
                    tracker.links(), loginDetected = true,
                    postsAreaRendered = postsAreaRendered, attemptsUsed = attemptsUsed
                )
            }
            if (browserController.controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
                // The user grabbed the browser mid-collection. Wait for
                // the hand-back, then restart this profile — the page
                // state is whatever the user left behind.
                if (!ensureAgentControl(hooks)) {
                    throw AbortRunException(null, "Browser control was not handed back in time — run stopped.")
                }
                return CollectOutcome(
                    tracker.links(), restart = true,
                    postsAreaRendered = postsAreaRendered, attemptsUsed = attemptsUsed
                )
            }

            // Fresh extraction from the live DOM — never a cached list.
            val scannedHrefs = mutableListOf<String>()
            val hrefs = browserController.extractAnchorHrefs()
            if (hrefs != null) {
                scannedHrefs.addAll(hrefs)
                tracker.addAll(FacebookPostLinks.extractPostLinks(hrefs, currentUrl), FacebookPostLinks.METHOD_M1)
            }
            if (tracker.isEmpty()) {
                // Fallback scan: anchors inside feed/article containers.
                val containerHrefs = browserController.extractContainerAnchorHrefs()
                if (containerHrefs != null) {
                    scannedHrefs.addAll(containerHrefs)
                    tracker.addAll(FacebookPostLinks.extractPostLinks(containerHrefs, currentUrl), FacebookPostLinks.METHOD_M1)
                }
            }
            if (allowMining) {
                val pageSource = browserController.extractPageSource()
                tracker.addAll(
                    FacebookPostLinks.mineLinksFromPageSource(pageSource, currentUrl),
                    FacebookPostLinks.METHOD_M3
                )
            }

            // Probe: posts-area evidence, late-rendered login gates, and
            // the data for this attempt's diagnostic step.
            var loginGateSeen = false
            val probeJson = browserController.extractPageProbeJson()
            val signals = probeJson?.let {
                FacebookPostLinks.signalsFromProbeJson(
                    it, rendererGone = browserController.rendererCrashed
                )
            }
            if (signals != null) {
                if (signals.hasPostsArea) postsAreaRendered = true
                if (FacebookPostLinks.classifyPage(signals) == FacebookPageClass.LOGIN_WALL) {
                    loginGateSeen = true
                }
            }
            recordProbeStep(
                hooks, normalized, attemptsUsed, attemptStart,
                signals, scannedHrefs, tracker.size
            )
            if (loginGateSeen) {
                return CollectOutcome(
                    tracker.links(), loginDetected = true,
                    postsAreaRendered = postsAreaRendered, attemptsUsed = attemptsUsed
                )
            }
        }

        if (tracker.isEmpty()) {
            // Minimum-effort floor for ANY zero-link conclusion: the
            // attempt delays normally exceed it — this is the guarantee.
            val elapsed = System.currentTimeMillis() - collectionStart
            if (elapsed < MIN_ZERO_EFFORT_MS) delay(MIN_ZERO_EFFORT_MS - elapsed)
            return CollectOutcome(
                tracker.links(),
                postsAreaRendered = postsAreaRendered, attemptsUsed = attemptsUsed
            )
        }

        // ---------------- Phase 2: top-up scrolling ----------------
        var dryScrolls = 0
        while (tracker.size < count && scrolls <= maxScrolls) {
            currentCoroutineContext().ensureActive()

            if (browserController.rendererCrashed) {
                return CollectOutcome(
                    tracker.links(), rendererDied = true,
                    postsAreaRendered = postsAreaRendered, attemptsUsed = attemptsUsed
                )
            }
            val currentUrl = browserController.getCurrentUrl().data.orEmpty()
            if (FacebookPostLinks.isLoginUrl(currentUrl)) {
                return CollectOutcome(
                    tracker.links(), loginDetected = true,
                    postsAreaRendered = postsAreaRendered, attemptsUsed = attemptsUsed
                )
            }
            if (browserController.controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
                if (!ensureAgentControl(hooks)) {
                    throw AbortRunException(null, "Browser control was not handed back in time — run stopped.")
                }
                return CollectOutcome(
                    tracker.links(), restart = true,
                    postsAreaRendered = postsAreaRendered, attemptsUsed = attemptsUsed
                )
            }

            val before = tracker.size
            val hrefs = browserController.extractAnchorHrefs()
            if (hrefs != null) {
                tracker.addAll(FacebookPostLinks.extractPostLinks(hrefs, currentUrl), FacebookPostLinks.METHOD_M1)
            }
            val added = tracker.size - before
            if (tracker.size >= count) break
            if (scrolls >= maxScrolls) break

            if (added == 0) {
                dryScrolls++
                if (dryScrolls >= MAX_DRY_SCROLLS) break
                val state = browserController.readScrollState()
                if (state != null && !state.canScrollDown) break // bottom reached
            } else {
                dryScrolls = 0
            }

            browserController.scrollDown(1.0)
            scrolls++
            delay(600)
        }
        return CollectOutcome(
            tracker.links(),
            postsAreaRendered = postsAreaRendered, attemptsUsed = attemptsUsed
        )
    }

    /**
     * The per-attempt probe record (FIX: diagnosability). One step per
     * discovery attempt carrying everything the next failure needs:
     * attempt number + timing, the final URL, whether the page looks
     * logged in, how many anchors were scanned and how many links they
     * yielded — plus, when anchors existed but NONE matched the
     * permalink patterns, up to 10 sample hrefs so a pattern gap vs a
     * render gap is obvious from the trail alone.
     */
    private suspend fun recordProbeStep(
        hooks: Hooks,
        normalized: String,
        attempt: Int,
        attemptStartMs: Long,
        signals: FacebookPageSignals?,
        scannedHrefs: List<String>,
        linksFound: Int
    ) {
        val elapsedSec = (System.currentTimeMillis() - attemptStartMs) / 1000.0
        val loggedIn = when {
            signals == null -> "unknown"
            signals.hasLoginForm || signals.hasLoginGate ||
                FacebookPostLinks.isLoginUrl(signals.finalUrl) -> "no"
            else -> "yes"
        }
        val sb = StringBuilder()
        sb.append("Probe ").append(normalized)
            .append(" — attempt ").append(attempt).append('/').append(MAX_ATTEMPTS)
            .append(" (").append(String.format(java.util.Locale.US, "%.1f", elapsedSec)).append("s)")
            .append(": final URL ").append(signals?.finalUrl?.ifBlank { normalized } ?: normalized)
            .append(", logged-in signal: ").append(loggedIn)
            .append(", anchors scanned ").append(scannedHrefs.size)
            .append(", links found ").append(linksFound)
        if (scannedHrefs.isNotEmpty() && linksFound == 0) {
            val samples = FacebookPostLinks.sampleHrefsForDiagnostics(scannedHrefs)
            if (samples.isNotEmpty()) {
                sb.append(" — sample hrefs: [").append(samples.joinToString(", ")).append(']')
            }
        }
        hooks.onStep(sb.toString())
    }

    /** Outcome of a fallback harvest of the page currently on screen. */
    private data class HarvestOutcome(
        val links: List<String>,
        val postsAreaRendered: Boolean = false,
        val loginWall: Boolean = false,
        val rendererDied: Boolean = false,
        /** This harvest's own first-finder provenance (absorbed by the caller). */
        val tracker: FacebookPostLinks.LinkProvenanceTracker = FacebookPostLinks.LinkProvenanceTracker()
    )

    /**
     * Fallback harvest of the CURRENT page (M2 posts section, M4
     * desktop): settle → probe → FRESH extract (+ container fallback)
     * → scroll-topup within [maxScrolls]. A login wall is only
     * REPORTED, never acted on — the login pause belongs to M1 alone.
     * Rendered-DOM finds are credited to [domMethod]; page-source
     * mining finds (only when [allowMining]) are credited to M3.
     */
    private suspend fun harvestCurrentPage(
        count: Int,
        maxScrolls: Int,
        settleMs: Long,
        domMethod: String,
        allowMining: Boolean
    ): HarvestOutcome {
        delay(settleMs)
        val found = FacebookPostLinks.LinkProvenanceTracker()
        var postsArea = false
        var scrolls = 0
        var dry = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (browserController.rendererCrashed) {
                return HarvestOutcome(found.links(), postsArea, rendererDied = true, tracker = found)
            }
            val url = browserController.getCurrentUrl().data.orEmpty()
            val before = found.size
            val hrefs = browserController.extractAnchorHrefs()
            if (hrefs != null) {
                found.addAll(FacebookPostLinks.extractPostLinks(hrefs, url), domMethod)
            }
            if (found.isEmpty()) {
                val containerHrefs = browserController.extractContainerAnchorHrefs()
                if (containerHrefs != null) {
                    found.addAll(FacebookPostLinks.extractPostLinks(containerHrefs, url), domMethod)
                }
            }
            if (allowMining) {
                val pageSource = browserController.extractPageSource()
                found.addAll(
                    FacebookPostLinks.mineLinksFromPageSource(pageSource, url),
                    FacebookPostLinks.METHOD_M3
                )
            }

            val probeJson = browserController.extractPageProbeJson()
            val signals = probeJson?.let {
                FacebookPostLinks.signalsFromProbeJson(
                    it, rendererGone = browserController.rendererCrashed
                )
            }
            if (signals != null) {
                if (signals.hasPostsArea) postsArea = true
                if (FacebookPostLinks.classifyPage(signals) == FacebookPageClass.LOGIN_WALL) {
                    return HarvestOutcome(found.links(), postsArea, loginWall = true, tracker = found)
                }
            }
            if (FacebookPostLinks.isLoginUrl(url)) {
                return HarvestOutcome(found.links(), postsArea, loginWall = true, tracker = found)
            }
            if (found.size >= count) break
            if (found.size == before) {
                dry++
                if (dry >= 2) break
                val state = browserController.readScrollState()
                if (state != null && !state.canScrollDown) break
            } else {
                dry = 0
            }
            if (scrolls >= maxScrolls) break
            browserController.scrollDown(1.0)
            scrolls++
            delay(600)
        }
        return HarvestOutcome(found.links(), postsArea, tracker = found)
    }

    /**
     * M3's combined scroll+mine loop — the ladder's deepest pass. Each
     * iteration re-reads the FRESH DOM anchors AND re-mines the FULL
     * CURRENT page source: scrolling makes Facebook append more
     * payload data, so a later pass sees strictly more than the first.
     * New finds merge into [tracker] in first-appearance order (the
     * caller's existing links — M1's visible anchors — keep their
     * lead; mined-only links follow in payload discovery order) and
     * are all credited to M3 — anchors read inside a mining pass are
     * part of that pass. Returns how many NEW links this loop added.
     * Bounded by the count target, the dry-scroll and scroll caps,
     * and the overall per-profile time budget ([budgetMs], measured
     * from [profileStartMs]).
     */
    private suspend fun runSourceMiningLoop(
        normalized: String,
        count: Int,
        tracker: FacebookPostLinks.LinkProvenanceTracker,
        profileStartMs: Long,
        budgetMs: Long,
        maxScrolls: Int
    ): Int {
        val startSize = tracker.size
        var scrolls = 0
        var dryPasses = 0
        while (tracker.size < count) {
            currentCoroutineContext().ensureActive()
            if (browserController.rendererCrashed) break
            if (System.currentTimeMillis() - profileStartMs > budgetMs) break
            val url = browserController.getCurrentUrl().data.orEmpty()
            if (FacebookPostLinks.isLoginUrl(url)) break
            val before = tracker.size
            val hrefs = browserController.extractAnchorHrefs()
            if (hrefs != null) {
                tracker.addAll(FacebookPostLinks.extractPostLinks(hrefs), FacebookPostLinks.METHOD_M3)
            }
            val source = browserController.extractPageSource()
            tracker.addAll(
                FacebookPostLinks.mineLinksFromPageSource(source, normalized),
                FacebookPostLinks.METHOD_M3
            )
            if (tracker.size >= count) break
            if (tracker.size == before) {
                dryPasses++
                if (dryPasses >= MAX_DRY_SCROLLS) break
                val state = browserController.readScrollState()
                if (state != null && !state.canScrollDown) break
            } else {
                dryPasses = 0
            }
            if (scrolls >= maxScrolls) break
            browserController.scrollDown(1.0)
            scrolls++
            delay(700)
        }
        return tracker.size - startSize
    }

    /**
     * M3_ONLY collection: the mining loop IS the whole collection —
     * settle → mine the full page source → scroll → mine again, until
     * the count target, the dry-pass stop, the scroll cap or the
     * per-profile budget ends it. Anchors are read only as part of a
     * mining pass (and credited to M3). The probe still runs per pass:
     * posts-area evidence accumulates and a positive login wall ends
     * the collection as [CollectOutcome.loginDetected], exactly like
     * the anchor phases. A zero-link conclusion honours the same
     * MIN_ZERO_EFFORT_MS floor as every other path.
     */
    private suspend fun collectViaMining(
        normalized: String,
        count: Int,
        hooks: Hooks,
        tracker: FacebookPostLinks.LinkProvenanceTracker,
        maxScrolls: Int,
        budgetMs: Long
    ): CollectOutcome {
        val collectionStart = System.currentTimeMillis()
        var postsAreaRendered = false
        delay(SETTLE_MS)
        var scrolls = 0
        var dryPasses = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (browserController.rendererCrashed) {
                return CollectOutcome(
                    tracker.links(), rendererDied = true,
                    postsAreaRendered = postsAreaRendered, attemptsUsed = 1
                )
            }
            val url = browserController.getCurrentUrl().data.orEmpty()
            if (FacebookPostLinks.isLoginUrl(url)) {
                return CollectOutcome(
                    tracker.links(), loginDetected = true,
                    postsAreaRendered = postsAreaRendered, attemptsUsed = 1
                )
            }
            if (browserController.controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
                if (!ensureAgentControl(hooks)) {
                    throw AbortRunException(null, "Browser control was not handed back in time — run stopped.")
                }
                return CollectOutcome(
                    tracker.links(), restart = true,
                    postsAreaRendered = postsAreaRendered, attemptsUsed = 1
                )
            }
            val before = tracker.size
            val hrefs = browserController.extractAnchorHrefs()
            if (hrefs != null) {
                tracker.addAll(FacebookPostLinks.extractPostLinks(hrefs, url), FacebookPostLinks.METHOD_M3)
            }
            val source = browserController.extractPageSource()
            tracker.addAll(
                FacebookPostLinks.mineLinksFromPageSource(source, normalized),
                FacebookPostLinks.METHOD_M3
            )
            val probeJson = browserController.extractPageProbeJson()
            val signals = probeJson?.let {
                FacebookPostLinks.signalsFromProbeJson(
                    it, rendererGone = browserController.rendererCrashed
                )
            }
            if (signals != null) {
                if (signals.hasPostsArea) postsAreaRendered = true
                if (FacebookPostLinks.classifyPage(signals) == FacebookPageClass.LOGIN_WALL) {
                    return CollectOutcome(
                        tracker.links(), loginDetected = true,
                        postsAreaRendered = postsAreaRendered, attemptsUsed = 1
                    )
                }
            }
            if (tracker.size >= count) break
            if (System.currentTimeMillis() - collectionStart > budgetMs) break
            if (tracker.size == before) {
                dryPasses++
                if (dryPasses >= MAX_DRY_SCROLLS) break
                val state = browserController.readScrollState()
                if (state != null && !state.canScrollDown) break
            } else {
                dryPasses = 0
            }
            if (scrolls >= maxScrolls) break
            browserController.scrollDown(1.0)
            scrolls++
            delay(700)
        }
        if (tracker.isEmpty()) {
            val elapsed = System.currentTimeMillis() - collectionStart
            if (elapsed < MIN_ZERO_EFFORT_MS) delay(MIN_ZERO_EFFORT_MS - elapsed)
        }
        hooks.onStep("M3 source mining (M3-only mode) on $normalized: ${tracker.size} link(s)")
        return CollectOutcome(
            tracker.links(),
            postsAreaRendered = postsAreaRendered, attemptsUsed = 1
        )
    }

    // ------------------------------------------------------------------
    // Control + login plumbing
    // ------------------------------------------------------------------

    private enum class LoginFlow { RESUMED, GIVE_UP, ABORT_RUN }

    private suspend fun handleLoginWall(
        normalized: String,
        hooks: Hooks,
        pauseAlreadyUsed: Boolean
    ): LoginFlow {
        if (pauseAlreadyUsed) return LoginFlow.GIVE_UP
        hooks.onStep("Login wall at $normalized — Needs you: log in to Facebook in the Browser tab, then hand control back")
        return when (hooks.onLoginWall(normalized)) {
            LoginWallDecision.RETRY_PROFILE -> LoginFlow.RESUMED
            LoginWallDecision.SKIP_PROFILE -> LoginFlow.GIVE_UP
            LoginWallDecision.STOP_RUN -> LoginFlow.ABORT_RUN
        }
    }

    /**
     * Makes sure the agent holds the browser before touching it. Claims
     * it from STANDBY; waits (Needs you, via the hooks) while the user
     * holds it. Returns false when the wait timed out or the user
     * emergency-stopped the browser.
     */
    private suspend fun ensureAgentControl(hooks: Hooks): Boolean {
        when (browserController.controlMode.value) {
            BrowserControlMode.AGENT_ACTIVE -> return true
            BrowserControlMode.STANDBY -> {
                browserController.updateControlMode(BrowserControlMode.AGENT_ACTIVE)
                return true
            }
            else -> Unit // MANUAL_CONTROL / WAITING_APPROVAL / PAUSED: wait below
        }
        hooks.onWaitStart("Needs you: the browser is under your control — hand it back to the agent to continue collecting")
        val deadline = System.currentTimeMillis() + CONTROL_WAIT_TIMEOUT_MS
        var resumed = false
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            when (browserController.controlMode.value) {
                BrowserControlMode.AGENT_ACTIVE -> {
                    resumed = true
                    break
                }
                BrowserControlMode.STANDBY -> {
                    resumed = false
                    break
                }
                else -> delay(500)
            }
        }
        hooks.onWaitEnd(resumed)
        return resumed
    }
}
