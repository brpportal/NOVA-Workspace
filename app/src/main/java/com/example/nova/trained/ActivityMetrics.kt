package com.example.nova.trained

import org.json.JSONObject

/**
 * Round B — the Tasks screen "Activity — all time" strip. The
 * aggregation is a pure function over fetched inputs so it is fully
 * testable; the UI only fetches:
 * - task statuses from the DB (agent_tasks),
 * - the links collectors' summary.json contents (AI-Tasks),
 * - the screenshots collectors' summary.json contents (AI-Tasks),
 * - registered artifact paths from the DB (agent_created_files).
 *
 * Every number is computed from those real records. A summary file
 * that fails to parse is skipped (never estimated); a metric with no
 * underlying records reads 0 because zero records exist — not because
 * anything was guessed.
 */
data class ActivityMetrics(
    val completedTasks: Int,
    /** FAILED tasks — the interrupted sweep also lands tasks here. */
    val failedTasks: Int,
    /** Links + screenshots runs that produced a summary.json. */
    val collectorRuns: Int,
    /** Sum of uniqueResults over all links-collector summaries. */
    val uniquePostLinks: Int,
    /** Sum of shotsTaken over all screenshots-collector summaries. */
    val screenshotsTaken: Int,
    /** Registered artifacts whose file name is a collector report.pdf. */
    val pdfReports: Int
)

object ActivityMetricsAggregator {

    /** uniqueResults from one links-collector summary.json; null when unparseable. */
    fun parseLinksSummaryUniqueResults(summaryJson: String): Int? = try {
        JSONObject(summaryJson).optInt("uniqueResults", -1).takeIf { it >= 0 }
    } catch (e: Exception) {
        null
    }

    /** summary.shotsTaken from one screenshots-collector summary.json; null when unparseable. */
    fun parseScreenshotsSummaryShotsTaken(summaryJson: String): Int? = try {
        JSONObject(summaryJson).optJSONObject("summary")
            ?.optInt("shotsTaken", -1)?.takeIf { it >= 0 }
    } catch (e: Exception) {
        null
    }

    fun aggregate(
        taskStatuses: List<String>,
        linksSummaryJsons: List<String>,
        screenshotsSummaryJsons: List<String>,
        createdFilePaths: List<String>
    ): ActivityMetrics {
        val linksUniques = linksSummaryJsons.mapNotNull(::parseLinksSummaryUniqueResults)
        val shotsTakens = screenshotsSummaryJsons.mapNotNull(::parseScreenshotsSummaryShotsTaken)
        return ActivityMetrics(
            completedTasks = taskStatuses.count { it == "COMPLETED" },
            failedTasks = taskStatuses.count { it == "FAILED" },
            collectorRuns = linksUniques.size + shotsTakens.size,
            uniquePostLinks = linksUniques.sum(),
            screenshotsTaken = shotsTakens.sum(),
            pdfReports = createdFilePaths.count { path ->
                val name = path.substringAfterLast('/')
                name == "report.pdf" || name.endsWith("-report.pdf")
            }
        )
    }
}
