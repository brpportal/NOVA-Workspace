package com.example.nova.trained

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Round B — the PDF report MODEL for both collectors (Links Collection
 * and Screenshots Collection). Pure data + pure builders: every string
 * and count that will appear in report.pdf is assembled HERE, so the
 * whole report is unit-testable; [PdfReportWriter] only draws it.
 *
 * HONESTY RULES (master prompt §13, carried over from Round A):
 * - "Verified accessible" is NEVER a number we did not measure. Link
 *   validation in this app is URL-syntax only, so the report says
 *   "Not measured" and states plainly that live accessibility was not
 *   checked.
 * - "Invalid candidates" keeps the Round A scope: invalid SOURCE
 *   lines rejected before the run — the only invalid-input number a
 *   run actually measures. The scope is printed next to the count.
 * - Counts come from the run's real results; a partial or stopped run
 *   reports what it actually collected.
 */

/** One per-source row of the report's source table. */
data class ReportSourceRow(
    /** "Link 1".."Link N", aligned with the parsed input. */
    val label: String,
    val url: String,
    /** Shared status taxonomy name (OK, PARTIAL, NO_POSTS, …). */
    val status: String,
    /** Short result measure: "5 links" / "3 of 4 screenshots". */
    val detail: String,
    /** Human detail for non-OK outcomes, when the run recorded one. */
    val note: String? = null
)

/** Everything report.pdf will contain, as plain values. */
data class CollectorReportModel(
    val reportId: String,
    val taskId: String,
    /** "Links Collection" | "Screenshots Collection". */
    val kindLabel: String,
    val title: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    /** The task's final status line (COMPLETED / FAILED / stopped note…). */
    val finalStatus: String,
    /** Label → value lines under the title (Report ID, Task ID, times…). */
    val meta: List<Pair<String, String>>,
    /** Label → value headline counts block. */
    val counts: List<Pair<String, String>>,
    val sourceRows: List<ReportSourceRow>,
    /** "Link 2 — PROFILE_LOCKED: <reason>" lines; empty when none failed. */
    val failures: List<String>,
    val warnings: List<String>,
    /** Artifact file names produced by the same run (report.pdf included). */
    val artifactNames: List<String>
)

object CollectorReport {

    const val REPORT_TITLE = "Spark Automation — Extraction Report"

    /** The exact wording used wherever live accessibility would go. */
    const val NOT_MEASURED_LIVE_CHECK =
        "Not measured — URLs were validated for syntax only; live accessibility was not checked"

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    /** Deterministic report id: RPT-<yyyyMMdd-HHmmss>-<task suffix>. */
    fun reportId(taskId: String, finishedAtMs: Long): String {
        val part = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(finishedAtMs))
        val suffix = taskId.filter { it.isLetterOrDigit() }.takeLast(6).uppercase(Locale.US)
        return "RPT-$part-$suffix"
    }

    private fun baseMeta(
        taskId: String,
        kindLabel: String,
        startedAtMs: Long,
        finishedAtMs: Long,
        finalStatus: String,
        reportId: String
    ): List<Pair<String, String>> = listOf(
        "Report ID" to reportId,
        "Task ID" to taskId,
        "Report type" to kindLabel,
        "Started" to stamp(startedAtMs),
        "Finished" to stamp(finishedAtMs),
        "Final task status" to finalStatus
    )

    /** Statuses that count as a failed source in the report. */
    private fun isFailure(status: FacebookProfileStatus): Boolean =
        status != FacebookProfileStatus.OK && status != FacebookProfileStatus.PARTIAL

    // ------------------------------------------------------------------
    // Links Collection
    // ------------------------------------------------------------------

    fun buildLinksReport(
        results: List<FacebookProfileResult>,
        config: FacebookRunArtifacts.RunConfig,
        taskId: String,
        finalStatus: String,
        artifactNames: List<String>,
        stoppedEarlyNote: String? = null
    ): CollectorReportModel {
        val discovered = results.sumOf { it.links.size }
        val unique = FacebookRunArtifacts.uniqueLinksRunWide(results).size
        val reportId = reportId(taskId, config.finishedAtMs)

        val counts = listOf(
            "Sources" to results.size.toString(),
            "Extraction method" to config.mode.displayName(),
            "Per-source limit" to config.perSourceLimit.toString(),
            "Discovered candidates" to discovered.toString(),
            "Unique results" to unique.toString(),
            "Duplicates removed" to (discovered - unique).toString(),
            "Invalid candidates" to
                "${config.invalidSourceLines} (invalid source lines rejected before the run)",
            "Duplicate source lines removed" to config.duplicateSourceLines.toString(),
            "Verified accessible" to NOT_MEASURED_LIVE_CHECK
        )

        val rows = results.mapIndexed { index, result ->
            ReportSourceRow(
                label = "Link ${index + 1}",
                url = result.normalizedUrl.ifBlank { result.profile },
                status = result.status.name,
                detail = "${result.links.size} link${if (result.links.size == 1) "" else "s"}",
                note = result.note
            )
        }

        val failures = results.mapIndexedNotNull { index, result ->
            if (!isFailure(result.status)) null
            else "Link ${index + 1} — ${result.status.name}: " +
                (result.note ?: "No post permalinks were collected for this source.")
        }

        val warnings = mutableListOf(
            "Reels are excluded from Links Collection by design.",
            "Link validation is syntactic only — whether a collected link still opens was not verified."
        )
        if (!stoppedEarlyNote.isNullOrBlank()) {
            warnings += "Run stopped early: $stoppedEarlyNote"
        }

        return CollectorReportModel(
            reportId = reportId,
            taskId = taskId,
            kindLabel = "Links Collection",
            title = REPORT_TITLE,
            startedAtMs = config.startedAtMs,
            finishedAtMs = config.finishedAtMs,
            finalStatus = finalStatus,
            meta = baseMeta(taskId, "Links Collection", config.startedAtMs, config.finishedAtMs, finalStatus, reportId),
            counts = counts,
            sourceRows = rows,
            failures = failures,
            warnings = warnings,
            artifactNames = artifactNames
        )
    }

    // ------------------------------------------------------------------
    // Screenshots Collection
    // ------------------------------------------------------------------

    fun buildScreenshotsReport(
        results: List<ScreenshotSourceResult>,
        config: ScreenshotsCollection.RunConfig,
        taskId: String,
        finalStatus: String,
        artifactNames: List<String>,
        stoppedEarlyNote: String? = null
    ): CollectorReportModel {
        val taken = ScreenshotsCollection.totalShotsTaken(results)
        val missed = ScreenshotsCollection.totalShotsMissed(results)
        val requested = results.sumOf { it.expectedShots }
        val reportId = reportId(taskId, config.finishedAtMs)

        val counts = listOf(
            "Sources" to results.size.toString(),
            "Discovery method" to config.mode.displayName(),
            "Post screenshots per profile" to config.postsPerProfile.toString(),
            "Screenshots requested" to requested.toString(),
            "Screenshots taken" to taken.toString(),
            "Screenshots missed" to missed.toString(),
            "Invalid candidates" to
                "${config.invalidSourceLines} (invalid source lines rejected before the run)",
            "Duplicate source lines removed" to config.duplicateSourceLines.toString()
        )

        val rows = results.map { result ->
            ReportSourceRow(
                label = result.label,
                url = result.normalizedUrl.ifBlank { result.source },
                status = result.status.name,
                detail = "${result.shots.size} of ${result.expectedShots} screenshot" +
                    (if (result.expectedShots == 1) "" else "s"),
                note = result.note
            )
        }

        val failures = results.mapNotNull { result ->
            if (!isFailure(result.status)) null
            else "${result.label} — ${result.status.name}: " +
                (result.note ?: "Fewer screenshots were captured than requested.")
        }

        val warnings = mutableListOf(
            "Screenshots capture the visible browser viewport at capture time; page content below the fold is not included.",
            "Post discovery reuses the Links Collection engine — a profile whose posts could not be discovered yields only its profile screenshot."
        )
        if (!stoppedEarlyNote.isNullOrBlank()) {
            warnings += "Run stopped early: $stoppedEarlyNote"
        }

        return CollectorReportModel(
            reportId = reportId,
            taskId = taskId,
            kindLabel = "Screenshots Collection",
            title = REPORT_TITLE,
            startedAtMs = config.startedAtMs,
            finishedAtMs = config.finishedAtMs,
            finalStatus = finalStatus,
            meta = baseMeta(taskId, "Screenshots Collection", config.startedAtMs, config.finishedAtMs, finalStatus, reportId),
            counts = counts,
            sourceRows = rows,
            failures = failures,
            warnings = warnings,
            artifactNames = artifactNames
        )
    }
}
