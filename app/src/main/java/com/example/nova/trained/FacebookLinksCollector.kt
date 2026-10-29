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
 *   3 consecutive scrolls with no new links, or after 15 scrolls.
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
        val stopReason: String? = null
    )

    /** Internal control-flow signal for "abort the whole chain now". */
    private class AbortRunException(
        val result: FacebookProfileResult?,
        val reason: String
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
        val restart: Boolean = false
    )

    companion object {
        const val LOAD_TIMEOUT_MS = 12_000L
        const val MAX_SCROLLS = 15
        const val MAX_DRY_SCROLLS = 3
        const val CONTROL_WAIT_TIMEOUT_MS = 10 * 60_000L
        const val MAX_PROFILE_RESTARTS = 3
    }

    // ------------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------------

    /** Mode 2 entry: collect from ONE profile. Never pauses in AI mode because the AI hooks answer at once. */
    suspend fun collectOne(
        rawProfile: String,
        count: Int,
        hooks: Hooks
    ): FacebookProfileResult {
        val safeCount = count.coerceIn(
            FacebookPostLinks.MIN_POSTS_PER_PROFILE,
            FacebookPostLinks.MAX_POSTS_PER_PROFILE
        )
        return try {
            collectProfile(rawProfile, safeCount, hooks)
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
        onProfileDone: suspend (FacebookProfileResult) -> Unit = {}
    ): CollectRunOutcome {
        val safeCount = count.coerceIn(
            FacebookPostLinks.MIN_POSTS_PER_PROFILE,
            FacebookPostLinks.MAX_POSTS_PER_PROFILE
        )
        val results = mutableListOf<FacebookProfileResult>()
        try {
            for ((index, raw) in rawProfiles.withIndex()) {
                currentCoroutineContext().ensureActive()
                val normalized = FacebookPostLinks.normalizeProfileUrl(raw)
                hooks.onStep("Profile ${index + 1}/${rawProfiles.size}: ${normalized.ifBlank { raw }}")
                val result = collectProfile(raw, safeCount, hooks)
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
            return CollectRunOutcome(results, stoppedEarly = true, stopReason = e.reason)
        }
        return CollectRunOutcome(results)
    }

    // ------------------------------------------------------------------
    // Per-profile orchestration (retries / recoveries / pause)
    // ------------------------------------------------------------------

    private suspend fun collectProfile(
        rawProfile: String,
        count: Int,
        hooks: Hooks
    ): FacebookProfileResult {
        val normalized = FacebookPostLinks.normalizeProfileUrl(rawProfile)
        fun result(status: FacebookProfileStatus, links: List<String> = emptyList(), note: String? = null) =
            FacebookProfileResult(rawProfile, normalized, status, links, note)

        if (normalized.isBlank() || !FacebookPostLinks.isFacebookUrl(normalized)) {
            return result(
                FacebookProfileStatus.PROFILE_UNAVAILABLE,
                note = "Not a Facebook profile URL — nothing to visit."
            )
        }

        var loginPauseUsed = false
        var openRetries = 0
        var fbRecoveries = 0
        var restarts = 0
        // Links already harvested survive restarts/recoveries of this
        // profile — they were really on the page; never lose them.
        val carriedLinks = LinkedHashSet<String>()

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
                FacebookPageClass.LOGIN_WALL -> {
                    when (handleLoginWall(normalized, hooks, loginPauseUsed)) {
                        LoginFlow.RESUMED -> {
                            loginPauseUsed = true
                            continue
                        }
                        LoginFlow.GIVE_UP -> return result(
                            FacebookProfileStatus.LOGIN_REQUIRED,
                            note = "A Facebook login is required to see this profile (Needs you)."
                        )
                        LoginFlow.ABORT_RUN -> throw AbortRunException(
                            result(
                                FacebookProfileStatus.LOGIN_REQUIRED,
                                note = "Login was not completed — the run stopped here."
                            ),
                            "Login was not completed in time — run stopped."
                        )
                    }
                }

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

                FacebookPageClass.CONTENT, null -> {
                    val outcome = collectLinks(normalized, count, hooks, carriedLinks)
                    carriedLinks.addAll(outcome.links)
                    when {
                        outcome.restart -> {
                            restarts++
                            if (restarts > MAX_PROFILE_RESTARTS) {
                                return result(
                                    FacebookProfileStatus.OPEN_FAILED,
                                    outcome.links,
                                    "Collection kept being interrupted by manual control."
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
                                "The browser's renderer crashed while collecting."
                            )
                        }
                        outcome.loginDetected -> {
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
                                    "Login was not completed in time — run stopped."
                                )
                            }
                        }
                        else -> {
                            val status = FacebookPostLinks.finalStatus(
                                FacebookPageClass.CONTENT, outcome.links.size, count
                            )
                            val note = when (status) {
                                FacebookProfileStatus.PARTIAL ->
                                    "Only ${outcome.links.size} of $count posts were found on this profile."
                                FacebookProfileStatus.NO_POSTS ->
                                    "The profile loaded but no post permalinks were found."
                                else -> null
                            }
                            return result(status, outcome.links, note)
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
    // The collection loop: extract → scroll → extract … until N or stop
    // ------------------------------------------------------------------

    private suspend fun collectLinks(
        normalized: String,
        count: Int,
        hooks: Hooks,
        initial: Set<String> = emptySet()
    ): CollectOutcome {
        val found = LinkedHashSet(initial)
        var scrolls = 0
        var dryScrolls = 0

        while (found.size < count && scrolls <= MAX_SCROLLS) {
            currentCoroutineContext().ensureActive()

            if (browserController.rendererCrashed) {
                return CollectOutcome(found.toList(), rendererDied = true)
            }
            // A checkpoint can interrupt mid-scroll: the URL gives it away.
            val currentUrl = browserController.getCurrentUrl().data.orEmpty()
            if (FacebookPostLinks.isLoginUrl(currentUrl)) {
                return CollectOutcome(found.toList(), loginDetected = true)
            }
            if (browserController.controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
                // The user grabbed the browser mid-collection. Wait for
                // the hand-back, then restart this profile — the page
                // state is whatever the user left behind.
                if (!ensureAgentControl(hooks)) {
                    throw AbortRunException(null, "Browser control was not handed back in time — run stopped.")
                }
                return CollectOutcome(found.toList(), restart = true)
            }

            val before = found.size
            val hrefs = browserController.extractAnchorHrefs()
            if (hrefs != null) {
                found.addAll(FacebookPostLinks.extractPostLinks(hrefs))
            }
            val added = found.size - before
            if (found.size >= count) break
            if (scrolls >= MAX_SCROLLS) break

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
        return CollectOutcome(found.toList())
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
