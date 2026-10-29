package com.example.nova.trained

import com.example.nova.browser.BrowserControlMode
import com.example.nova.browser.BrowserController
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * The deterministic core of Trained Task #2 — Screenshots Collection,
 * Spark's second core feature. ZERO provider/LLM calls: the same core
 * is driven by the Tasks-screen form (Mode 1, via NovaRepository) and
 * by the agent's `collect_screenshots` tool (Mode 2, via ToolExecutor).
 *
 * The sibling relationship with Trained Task #1 is literal, not
 * cosmetic: post discovery on a profile runs through
 * [FacebookLinksCollector] itself (the ladder, its statuses, its
 * login-pause discipline), the page classifier and status taxonomy are
 * [FacebookPostLinks] / [FacebookProfileStatus], and the hooks
 * interface is Task #1's — so a login wall pauses a screenshots run
 * exactly the way it pauses a links run (Needs you → log in → hand
 * control back → resume the same source).
 *
 * Behaviour contract:
 * - Sources are visited strictly in list order — the chain NEVER stops
 *   for a per-source failure; the failure is recorded with its exact
 *   status and the run moves to the next source.
 * - The ONLY pause is a login/checkpoint wall (LOGIN_REQUIRED).
 * - Recovery budgets mirror Task #1: ONE open retry, ONE WebView
 *   recreation for a renderer crash, ONE login pause per stage.
 * - Captures are real WebView viewport draws
 *   ([BrowserController.captureViewportToFile]) taken after the page
 *   settles, scrolled to the top so the shot shows the page head.
 */
class ScreenshotsCollector(
    private val browserController: BrowserController,
    private val linksCollector: FacebookLinksCollector,
    /** The run folder on disk: AI-Tasks/screenshots-<stamp>/ inside the workspace. */
    private val runDir: File,
    /** Workspace-relative prefix matching [runDir], for artifact paths: "AI-Tasks/screenshots-<stamp>". */
    private val runRelPrefix: String
) {

    /** The full run's outcome: every source's result, in order. */
    data class ScreenshotsRunOutcome(
        val results: List<ScreenshotSourceResult>,
        /** True when the chain was cut short (a Needs-you wait timed out / control never returned). */
        val stoppedEarly: Boolean = false,
        val stopReason: String? = null,
        /** True when the early stop was a LOGIN pause that ended without a login. */
        val stoppedForLogin: Boolean = false
    )

    /** Internal control-flow signal for "abort the whole chain now". */
    private class AbortShotsException(
        val result: ScreenshotSourceResult?,
        val reason: String,
        val stoppedForLogin: Boolean = false
    ) : Exception(reason)

    private enum class LoginFlow { RESUMED, GIVE_UP, ABORT_RUN }

    private data class NavProbe(
        val pageClass: FacebookPageClass?,
        val openError: String?,
        val rendererGone: Boolean = false,
        val finalUrl: String = ""
    )

    companion object {
        /** Settle after a load before a capture (the collector's settle discipline). */
        const val CAPTURE_SETTLE_MS = 1_500L
    }

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    /** The full chain, in order, whatever each source does. */
    suspend fun collectAll(
        sources: List<String>,
        labels: List<String>,
        postsPerProfile: Int,
        hooks: FacebookLinksCollector.Hooks,
        mode: FacebookExtractionMode = FacebookExtractionMode.HYBRID,
        onSourceDone: suspend (ScreenshotSourceResult) -> Unit = {}
    ): ScreenshotsRunOutcome {
        val safePosts = ScreenshotsCollection.clampPostsPerProfile(postsPerProfile)
        val results = mutableListOf<ScreenshotSourceResult>()
        try {
            for ((index, raw) in sources.withIndex()) {
                currentCoroutineContext().ensureActive()
                val label = labels.getOrNull(index) ?: "Link ${index + 1}"
                hooks.onStep("Source ${index + 1}/${sources.size}: $raw")
                val result = try {
                    when (ScreenshotsCollection.classifyLine(raw)) {
                        ScreenshotLineKind.FACEBOOK_PROFILE ->
                            collectProfileSource(index, raw, label, safePosts, hooks, mode)
                        ScreenshotLineKind.DIRECT_URL ->
                            collectDirectSource(index, raw, label, hooks)
                    }
                } catch (e: AbortShotsException) {
                    throw e
                } catch (e: Exception) {
                    // One bad source never kills the chain.
                    ScreenshotSourceResult(
                        source = raw,
                        label = label,
                        kind = ScreenshotsCollection.classifyLine(raw),
                        normalizedUrl = raw,
                        status = FacebookProfileStatus.OPEN_FAILED,
                        note = "Something went wrong while capturing this source."
                    )
                }
                results.add(result)
                onSourceDone(result)
                hooks.onStep(
                    "${result.normalizedUrl.ifBlank { result.source }} — ${result.status.name} " +
                        "(${result.shots.size} screenshot${if (result.shots.size == 1) "" else "s"})" +
                        (result.note?.let { " — $it" } ?: "")
                )
            }
        } catch (e: AbortShotsException) {
            if (e.result != null) {
                results.add(e.result)
                onSourceDone(e.result)
            }
            return ScreenshotsRunOutcome(
                results,
                stoppedEarly = true,
                stopReason = e.reason,
                stoppedForLogin = e.stoppedForLogin
            )
        }
        return ScreenshotsRunOutcome(results)
    }

    // ------------------------------------------------------------------
    // Profile mode: profile shot → discovery (Task #1) → post shots
    // ------------------------------------------------------------------

    private suspend fun collectProfileSource(
        index: Int,
        raw: String,
        label: String,
        postsPerProfile: Int,
        hooks: FacebookLinksCollector.Hooks,
        mode: FacebookExtractionMode
    ): ScreenshotSourceResult {
        val normalized = FacebookPostLinks.normalizeProfileUrl(raw)
        val folder = ScreenshotsCollection.sourceFolderName(index, normalized.ifBlank { raw })
        val folderFile = File(runDir, folder)

        fun result(
            status: FacebookProfileStatus,
            shots: List<ScreenshotShot> = emptyList(),
            postLinks: List<String> = emptyList(),
            expectedShots: Int = 1,
            note: String? = null
        ) = ScreenshotSourceResult(
            source = raw,
            label = label,
            kind = ScreenshotLineKind.FACEBOOK_PROFILE,
            normalizedUrl = normalized,
            status = status,
            shots = shots,
            expectedShots = expectedShots,
            postLinks = postLinks,
            note = note,
            folder = folder
        )

        if (normalized.isBlank() || !FacebookPostLinks.isFacebookUrl(normalized)) {
            return result(
                FacebookProfileStatus.PROFILE_UNAVAILABLE,
                expectedShots = 0,
                note = "Not a Facebook profile URL — nothing to visit."
            )
        }

        // ---- Stage 1: open the profile and capture the profile shot.
        var loginPauseUsed = false
        var openRetries = 0
        var fbRecoveries = 0
        var profileShot: ScreenshotShot? = null

        openLoop@ while (true) {
            currentCoroutineContext().ensureActive()
            if (!ensureAgentControl(hooks)) {
                throw AbortShotsException(null, "Browser control was not handed back in time — run stopped.")
            }
            val probe = navigateAndProbe(normalized)
            when {
                probe.rendererGone -> {
                    if (fbRecoveries < 1) {
                        fbRecoveries++
                        hooks.onStep("Browser renderer crashed on $normalized — recreating the browser once")
                        browserController.recreateWebView()
                        continue@openLoop
                    }
                    return result(
                        FacebookProfileStatus.FB_ERROR,
                        note = "The browser's renderer crashed on this profile."
                    )
                }
                probe.openError != null -> {
                    if (openRetries < 1) {
                        openRetries++
                        hooks.onStep("Open failed for $normalized (${probe.openError}) — retrying once")
                        delay(800)
                        continue@openLoop
                    }
                    return result(
                        FacebookProfileStatus.OPEN_FAILED,
                        note = "The page failed to load: ${probe.openError}"
                    )
                }
                probe.pageClass == FacebookPageClass.LOGIN_WALL -> {
                    when (handleLoginWall(normalized, hooks, loginPauseUsed)) {
                        LoginFlow.RESUMED -> {
                            loginPauseUsed = true
                            continue@openLoop
                        }
                        LoginFlow.GIVE_UP -> return result(
                            FacebookProfileStatus.LOGIN_REQUIRED,
                            note = "Facebook asked for a login before showing this profile (Needs you)."
                        )
                        LoginFlow.ABORT_RUN -> throw AbortShotsException(
                            result(
                                FacebookProfileStatus.LOGIN_REQUIRED,
                                note = "Login was not completed — the run stopped here."
                            ),
                            "Login was not completed in time — run stopped.",
                            stoppedForLogin = true
                        )
                    }
                }
                probe.pageClass == FacebookPageClass.FB_ERROR_PAGE -> {
                    if (fbRecoveries < 1) {
                        fbRecoveries++
                        hooks.onStep("Facebook error page on $normalized — reloading once")
                        browserController.reloadAction()
                        browserController.waitForLoad(FacebookLinksCollector.LOAD_TIMEOUT_MS)
                        continue@openLoop
                    }
                    // The error page itself is the evidence; capture it, then report.
                    delay(CAPTURE_SETTLE_MS)
                    browserController.scrollToTop()
                    profileShot = captureShot(folderFile, ScreenshotsCollection.PROFILE_FILE_NAME,
                        "$runRelPrefix/$folder/${ScreenshotsCollection.PROFILE_FILE_NAME}", "profile", null)
                    return result(
                        FacebookProfileStatus.FB_ERROR,
                        shots = listOfNotNull(profileShot),
                        note = "Facebook returned its error page for this profile."
                    )
                }
                probe.pageClass == FacebookPageClass.PROFILE_LOCKED ||
                    probe.pageClass == FacebookPageClass.PROFILE_UNAVAILABLE -> {
                    // The notice page is real evidence — capture it as the profile shot.
                    delay(CAPTURE_SETTLE_MS)
                    browserController.scrollToTop()
                    profileShot = captureShot(folderFile, ScreenshotsCollection.PROFILE_FILE_NAME,
                        "$runRelPrefix/$folder/${ScreenshotsCollection.PROFILE_FILE_NAME}", "profile", null)
                    val locked = probe.pageClass == FacebookPageClass.PROFILE_LOCKED
                    return result(
                        if (locked) FacebookProfileStatus.PROFILE_LOCKED
                        else FacebookProfileStatus.PROFILE_UNAVAILABLE,
                        shots = listOfNotNull(profileShot),
                        note = if (locked) {
                            "This profile is locked / private — posts are not visible."
                        } else {
                            "Facebook says this content isn't available (banned, deactivated or not found)."
                        }
                    )
                }
                else -> {
                    // CONTENT (or an unreadable probe on a loaded page):
                    // settle, back to the top, capture the profile page.
                    delay(CAPTURE_SETTLE_MS)
                    browserController.scrollToTop()
                    profileShot = captureShot(folderFile, ScreenshotsCollection.PROFILE_FILE_NAME,
                        "$runRelPrefix/$folder/${ScreenshotsCollection.PROFILE_FILE_NAME}", "profile", null)
                    break@openLoop
                }
            }
        }

        // ---- Stage 2: discover the latest posts with Task #1's core.
        val discovery = linksCollector.collectOne(raw, postsPerProfile, hooks, mode)
        when (discovery.status) {
            FacebookProfileStatus.LOGIN_REQUIRED -> return result(
                FacebookProfileStatus.LOGIN_REQUIRED,
                shots = listOfNotNull(profileShot),
                postLinks = discovery.links,
                note = discovery.note ?: "Facebook asked for a login while finding posts (Needs you)."
            )
            FacebookProfileStatus.PROFILE_LOCKED,
            FacebookProfileStatus.PROFILE_UNAVAILABLE,
            FacebookProfileStatus.OPEN_FAILED,
            FacebookProfileStatus.FB_ERROR -> return result(
                discovery.status,
                shots = listOfNotNull(profileShot),
                note = discovery.note
            )
            FacebookProfileStatus.NO_POSTS -> return result(
                FacebookProfileStatus.NO_POSTS,
                shots = listOfNotNull(profileShot),
                note = discovery.note
                    ?: "The profile loaded but no post permalinks were found — profile screenshot only."
            )
            else -> Unit // OK / PARTIAL: capture the discovered posts below.
        }

        // ---- Stage 3: one screenshot per discovered post.
        val postLinks = discovery.links.take(postsPerProfile)
        val shots = mutableListOf<ScreenshotShot>()
        profileShot?.let { shots.add(it) }
        var postShotsTaken = 0
        var loginGivenUp = false
        var postsLoginPauseUsed = false

        for ((postIndex, link) in postLinks.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!ensureAgentControl(hooks)) {
                throw AbortShotsException(
                    result(
                        ScreenshotsCollection.resolveProfileStatus(
                            discovery.status, profileShot != null, postShotsTaken,
                            postLinks.size, postsPerProfile
                        ),
                        shots = shots.toList(),
                        postLinks = postLinks,
                        expectedShots = ScreenshotsCollection.expectedShotsForProfile(
                            discovery.status, postLinks.size, postsPerProfile
                        ),
                        note = "Browser control was not handed back — remaining posts were not captured."
                    ),
                    "Browser control was not handed back in time — run stopped."
                )
            }

            var captured: ScreenshotShot? = null
            var attempt = 0
            postLoop@ while (attempt < 2 && captured == null) {
                attempt++
                val nav = navigateAndSettle(link)
                when {
                    nav.rendererGone -> {
                        if (fbRecoveries < 1) {
                            fbRecoveries++
                            hooks.onStep("Browser renderer crashed opening a post of $normalized — recreating the browser once")
                            browserController.recreateWebView()
                            continue@postLoop
                        }
                        break@postLoop // this post is missed; the chain continues
                    }
                    nav.openError != null -> break@postLoop // missed post; continue with the next
                    FacebookPostLinks.isLoginUrl(nav.finalUrl) -> {
                        when (handleLoginWall(link, hooks, postsLoginPauseUsed)) {
                            LoginFlow.RESUMED -> {
                                postsLoginPauseUsed = true
                                continue@postLoop // one re-open of this post after the login
                            }
                            LoginFlow.GIVE_UP -> {
                                loginGivenUp = true
                                break@postLoop
                            }
                            LoginFlow.ABORT_RUN -> throw AbortShotsException(
                                result(
                                    FacebookProfileStatus.LOGIN_REQUIRED,
                                    shots = shots.toList(),
                                    postLinks = postLinks,
                                    expectedShots = ScreenshotsCollection.expectedShotsForProfile(
                                        discovery.status, postLinks.size, postsPerProfile
                                    ),
                                    note = "Login was not completed — the run stopped here."
                                ),
                                "Login was not completed in time — run stopped.",
                                stoppedForLogin = true
                            )
                        }
                    }
                    else -> {
                        browserController.scrollToTop()
                        val fileName = ScreenshotsCollection.postFileName(postIndex)
                        captured = captureShot(
                            folderFile, fileName,
                            "$runRelPrefix/$folder/$fileName", "post", link
                        )
                    }
                }
            }
            if (loginGivenUp) break
            if (captured != null) {
                shots.add(captured)
                postShotsTaken++
            } else {
                hooks.onStep("Post ${postIndex + 1} of $normalized could not be captured — continuing")
            }
        }

        val expected = ScreenshotsCollection.expectedShotsForProfile(
            discovery.status, postLinks.size, postsPerProfile
        )
        if (loginGivenUp) {
            return result(
                FacebookProfileStatus.LOGIN_REQUIRED,
                shots = shots,
                postLinks = postLinks,
                expectedShots = expected,
                note = "Facebook asked for a login while opening posts (Needs you)."
            )
        }
        val status = ScreenshotsCollection.resolveProfileStatus(
            discovery.status, profileShot != null, postShotsTaken, postLinks.size, postsPerProfile
        )
        val note = when {
            status == FacebookProfileStatus.OK -> null
            profileShot == null && postShotsTaken == 0 ->
                "The pages loaded but no screenshot could be captured for this profile."
            profileShot == null ->
                "The profile screenshot could not be captured; post screenshots were still taken."
            postShotsTaken < postLinks.size ->
                "Captured $postShotsTaken of ${postLinks.size} post screenshots."
            else -> discovery.note
        }
        return result(status, shots = shots, postLinks = postLinks, expectedShots = expected, note = note)
    }

    // ------------------------------------------------------------------
    // Direct mode: open the link, settle, one screenshot
    // ------------------------------------------------------------------

    private suspend fun collectDirectSource(
        index: Int,
        raw: String,
        label: String,
        hooks: FacebookLinksCollector.Hooks
    ): ScreenshotSourceResult {
        val isFacebook = FacebookPostLinks.isFacebookUrl(raw)
        // A direct Facebook permalink is visited in its canonical
        // m.facebook.com form (the same normalization Task #1 applies).
        val navTarget = if (isFacebook) {
            FacebookPostLinks.normalizePostUrl(raw) ?: raw
        } else {
            raw
        }
        val fileName = ScreenshotsCollection.directFileName(index, navTarget)
        val relPath = "$runRelPrefix/$fileName"

        fun result(
            status: FacebookProfileStatus,
            shots: List<ScreenshotShot> = emptyList(),
            note: String? = null
        ) = ScreenshotSourceResult(
            source = raw,
            label = label,
            kind = ScreenshotLineKind.DIRECT_URL,
            normalizedUrl = navTarget,
            status = status,
            shots = shots,
            expectedShots = 1,
            note = note,
            folder = ""
        )

        var loginPauseUsed = false
        var openRetries = 0
        var fbRecoveries = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            if (!ensureAgentControl(hooks)) {
                throw AbortShotsException(null, "Browser control was not handed back in time — run stopped.")
            }
            // Only Facebook pages are classified — the probe's login
            // signals are Facebook-shaped and would false-positive on
            // arbitrary sites with a sign-in form.
            val probe = if (isFacebook) navigateAndProbe(navTarget) else navigateOnly(navTarget)
            when {
                probe.rendererGone -> {
                    if (fbRecoveries < 1) {
                        fbRecoveries++
                        hooks.onStep("Browser renderer crashed on $navTarget — recreating the browser once")
                        browserController.recreateWebView()
                        continue
                    }
                    return result(
                        if (isFacebook) FacebookProfileStatus.FB_ERROR else FacebookProfileStatus.OPEN_FAILED,
                        note = "The browser's renderer crashed on this page."
                    )
                }
                probe.openError != null -> {
                    if (openRetries < 1) {
                        openRetries++
                        hooks.onStep("Open failed for $navTarget (${probe.openError}) — retrying once")
                        delay(800)
                        continue
                    }
                    return result(
                        FacebookProfileStatus.OPEN_FAILED,
                        note = "The page failed to load: ${probe.openError}"
                    )
                }
                probe.pageClass == FacebookPageClass.LOGIN_WALL -> {
                    when (handleLoginWall(navTarget, hooks, loginPauseUsed)) {
                        LoginFlow.RESUMED -> {
                            loginPauseUsed = true
                            continue
                        }
                        LoginFlow.GIVE_UP -> return result(
                            FacebookProfileStatus.LOGIN_REQUIRED,
                            note = "Facebook asked for a login before showing this page (Needs you)."
                        )
                        LoginFlow.ABORT_RUN -> throw AbortShotsException(
                            result(
                                FacebookProfileStatus.LOGIN_REQUIRED,
                                note = "Login was not completed — the run stopped here."
                            ),
                            "Login was not completed in time — run stopped.",
                            stoppedForLogin = true
                        )
                    }
                }
                else -> {
                    delay(CAPTURE_SETTLE_MS)
                    browserController.scrollToTop()
                    val shot = captureShot(runDir, fileName, relPath, "direct", navTarget)
                    val status = ScreenshotsCollection.resolveDirectStatus(
                        probe.pageClass, openFailed = false, captured = shot != null
                    )
                    val note = when {
                        shot == null -> "The page loaded but the screenshot could not be captured."
                        status == FacebookProfileStatus.PROFILE_LOCKED ->
                            "This content is locked / private."
                        status == FacebookProfileStatus.PROFILE_UNAVAILABLE ->
                            "Facebook says this content isn't available."
                        status == FacebookProfileStatus.FB_ERROR ->
                            "Facebook returned its error page for this link."
                        else -> null
                    }
                    return result(status, shots = listOfNotNull(shot), note = note)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Navigation helpers (mirroring Task #1's attempt plumbing)
    // ------------------------------------------------------------------

    /** Navigate → wait → probe → classify (Facebook pages). */
    private suspend fun navigateAndProbe(url: String): NavProbe {
        val nav = browserController.navigate(url)
        if (!nav.success) return NavProbe(null, nav.message)
        val wait = browserController.waitForLoad(FacebookLinksCollector.LOAD_TIMEOUT_MS)
        if (browserController.rendererCrashed) return NavProbe(null, null, rendererGone = true)
        if (!wait.success) return NavProbe(null, wait.message)
        browserController.lastLoadError?.let { err ->
            return NavProbe(null, "Load error ${err.errorCode}: ${err.description}")
        }
        val probeJson = browserController.extractPageProbeJson()
            ?: return NavProbe(null, "The loaded page could not be read")
        val signals = FacebookPostLinks.signalsFromProbeJson(
            probeJson,
            loadError = browserController.lastLoadError != null,
            rendererGone = browserController.rendererCrashed
        ) ?: return NavProbe(null, "The loaded page could not be read")
        return NavProbe(FacebookPostLinks.classifyPage(signals), null, finalUrl = signals.finalUrl)
    }

    /** Navigate → wait → settle, reporting only load outcomes + the final URL (post captures). */
    private suspend fun navigateAndSettle(url: String): NavProbe {
        val nav = browserController.navigate(url)
        if (!nav.success) return NavProbe(null, nav.message)
        val wait = browserController.waitForLoad(FacebookLinksCollector.LOAD_TIMEOUT_MS)
        if (browserController.rendererCrashed) return NavProbe(null, null, rendererGone = true)
        if (!wait.success) return NavProbe(null, wait.message)
        browserController.lastLoadError?.let { err ->
            return NavProbe(null, "Load error ${err.errorCode}: ${err.description}")
        }
        delay(CAPTURE_SETTLE_MS)
        val finalUrl = browserController.getCurrentUrl().data.orEmpty()
        return NavProbe(null, null, finalUrl = finalUrl)
    }

    /** Navigate → wait (no probe, no classification) for non-Facebook direct links. */
    private suspend fun navigateOnly(url: String): NavProbe {
        val nav = browserController.navigate(url)
        if (!nav.success) return NavProbe(null, nav.message)
        val wait = browserController.waitForLoad(FacebookLinksCollector.LOAD_TIMEOUT_MS)
        if (browserController.rendererCrashed) return NavProbe(null, null, rendererGone = true)
        if (!wait.success) return NavProbe(null, wait.message)
        browserController.lastLoadError?.let { err ->
            return NavProbe(null, "Load error ${err.errorCode}: ${err.description}")
        }
        return NavProbe(null, null, finalUrl = browserController.getCurrentUrl().data.orEmpty())
    }

    /** One real viewport capture into [folderFile]/[fileName]; null when the capture failed. */
    private suspend fun captureShot(
        folderFile: File,
        fileName: String,
        relPath: String,
        kind: String,
        postUrl: String?
    ): ScreenshotShot? {
        val target = File(folderFile, fileName)
        val capture = browserController.captureViewportToFile(
            target, ScreenshotsCollection.JPEG_FALLBACK_THRESHOLD_BYTES
        )
        if (!capture.success || capture.pngFile == null) return null
        return ScreenshotShot(
            kind = kind,
            fileName = fileName,
            relativePath = relPath,
            postUrl = postUrl,
            jpegRelativePath = capture.jpegFile?.let { relPath.removeSuffix(".png") + ".jpg" }
        )
    }

    // ------------------------------------------------------------------
    // Control + login plumbing (mirrors FacebookLinksCollector)
    // ------------------------------------------------------------------

    private suspend fun handleLoginWall(
        url: String,
        hooks: FacebookLinksCollector.Hooks,
        pauseAlreadyUsed: Boolean
    ): LoginFlow {
        if (pauseAlreadyUsed) return LoginFlow.GIVE_UP
        hooks.onStep("Login wall at $url — Needs you: log in to Facebook in the Browser tab, then hand control back")
        return when (hooks.onLoginWall(url)) {
            FacebookLinksCollector.LoginWallDecision.RETRY_PROFILE -> LoginFlow.RESUMED
            FacebookLinksCollector.LoginWallDecision.SKIP_PROFILE -> LoginFlow.GIVE_UP
            FacebookLinksCollector.LoginWallDecision.STOP_RUN -> LoginFlow.ABORT_RUN
        }
    }

    /**
     * Makes sure the agent holds the browser before touching it. Claims
     * it from STANDBY; waits (Needs you, via the hooks) while the user
     * holds it. Returns false when the wait timed out or the user
     * emergency-stopped the browser.
     */
    private suspend fun ensureAgentControl(hooks: FacebookLinksCollector.Hooks): Boolean {
        when (browserController.controlMode.value) {
            BrowserControlMode.AGENT_ACTIVE -> return true
            BrowserControlMode.STANDBY -> {
                browserController.updateControlMode(BrowserControlMode.AGENT_ACTIVE)
                return true
            }
            else -> Unit // MANUAL_CONTROL / WAITING_APPROVAL / PAUSED: wait below
        }
        hooks.onWaitStart("Needs you: the browser is under your control — hand it back to the agent to continue capturing")
        val deadline = System.currentTimeMillis() + FacebookLinksCollector.CONTROL_WAIT_TIMEOUT_MS
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
