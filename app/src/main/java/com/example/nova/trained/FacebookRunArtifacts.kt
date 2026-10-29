package com.example.nova.trained

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Round A — per-run artifact builders for the Facebook Post Links
 * Collector. Pure functions: run results in → file contents out.
 *
 * The four artifacts sit next to the existing results .md in AI-Tasks/:
 * - results.txt  — one unique post URL per line (run-wide dedupe).
 * - results.csv  — one row per (source, link) with provenance.
 * - results.json — full structured record of the run.
 * - summary.json — headline statistics only.
 *
 * HONESTY RULES baked into the formats:
 * - "Validation" is SYNTAX ONLY (the URL parses as a Facebook post
 *   permalink). Nothing here performs live HTTP checks, and no field
 *   ever claims a destination was verified accessible.
 * - Counts come from the actual results: "discovered candidates" is
 *   the number of validated permalinks found across all sources
 *   BEFORE run-wide dedupe; "invalid candidates" counts rejected
 *   SOURCE lines (invalid input), which is the only invalid-input
 *   number the run actually measures.
 */
object FacebookRunArtifacts {

    /** Run configuration recorded in results.json / echoed in summary.json. */
    data class RunConfig(
        val sources: List<String>,
        val perSourceLimit: Int,
        val mode: FacebookExtractionMode,
        val startedAtMs: Long,
        val finishedAtMs: Long,
        /** Measured invalid input lines (Mode 1 input parse; 0/1 for Mode 2). */
        val invalidSourceLines: Int = 0,
        /** Duplicate source lines removed before the run (Mode 1 input parse). */
        val duplicateSourceLines: Int = 0
    )

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    // ------------------------------------------------------------------
    // results.txt
    // ------------------------------------------------------------------

    /** One unique post URL per line, run-wide dedupe, first-appearance order. */
    fun buildResultsTxt(results: List<FacebookProfileResult>): String {
        val seen = LinkedHashSet<String>()
        for (result in results) seen.addAll(result.links)
        return seen.joinToString("\n", postfix = if (seen.isEmpty()) "" else "\n")
    }

    /** Run-wide unique links in first-appearance order (shared by all builders). */
    fun uniqueLinksRunWide(results: List<FacebookProfileResult>): List<String> {
        val seen = LinkedHashSet<String>()
        for (result in results) seen.addAll(result.links)
        return seen.toList()
    }

    // ------------------------------------------------------------------
    // results.csv
    // ------------------------------------------------------------------

    /** RFC 4180 field escaping: quote when the field holds , " CR or LF; double inner quotes. */
    fun escapeCsvField(field: String): String {
        return if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
    }

    fun csvRow(fields: List<String>): String = fields.joinToString(",") { escapeCsvField(it) }

    val CSV_HEADER = listOf(
        "Source #", "Source URL", "Post URL", "Object Type", "Method",
        "Discovery Timestamp", "Validation", "Note"
    )

    /**
     * One row per (source, link). Method/type come from the link's
     * provenance + URL shape; the timestamp is the link's recorded
     * discovery time (falling back to the run's finish time when a
     * result carries no attribution). Validation wording is exact:
     * syntax was checked; live accessibility was NOT.
     */
    fun buildResultsCsv(results: List<FacebookProfileResult>, config: RunConfig): String {
        val sb = StringBuilder()
        sb.append(csvRow(CSV_HEADER)).append("\r\n")
        results.forEachIndexed { index, result ->
            val note = result.note ?: result.status.name
            for (link in result.links) {
                val attribution = result.linkAttribution[link]
                sb.append(
                    csvRow(
                        listOf(
                            (index + 1).toString(),
                            result.normalizedUrl.ifBlank { result.profile },
                            link,
                            FacebookPostLinks.postObjectType(link),
                            attribution?.method ?: "",
                            stamp(attribution?.discoveredAtMs ?: config.finishedAtMs),
                            if (FacebookPostLinks.isPostPermalink(link)) {
                                "syntactically valid (live accessibility not checked)"
                            } else {
                                "unverified"
                            },
                            note
                        )
                    )
                ).append("\r\n")
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // results.json
    // ------------------------------------------------------------------

    fun buildResultsJson(results: List<FacebookProfileResult>, config: RunConfig): String {
        val root = JSONObject()
        root.put("task", "facebook_post_links_collector")
        root.put(
            "config", JSONObject()
                .put("sources", JSONArray(config.sources))
                .put("perSourceLimit", config.perSourceLimit)
                .put("method", config.mode.toolArg())
                .put("methodLabel", config.mode.displayName())
                .put("startedAt", stamp(config.startedAtMs))
                .put("finishedAt", stamp(config.finishedAtMs))
                .put("startedAtMs", config.startedAtMs)
                .put("finishedAtMs", config.finishedAtMs)
        )
        val sourcesArr = JSONArray()
        results.forEachIndexed { index, result ->
            val block = JSONObject()
            block.put("sourceNumber", index + 1)
            block.put("label", "Link ${index + 1}")
            block.put("sourceUrl", result.profile)
            block.put("normalizedUrl", result.normalizedUrl)
            block.put("status", result.status.name)
            result.note?.let { block.put("note", it) }
            result.methodsSummary?.let { block.put("methods", it) }
            val linksArr = JSONArray()
            for (link in result.links) {
                val attribution = result.linkAttribution[link]
                linksArr.put(
                    JSONObject()
                        .put("url", link)
                        .put("type", FacebookPostLinks.postObjectType(link))
                        .put("method", attribution?.method ?: JSONObject.NULL)
                        .put(
                            "discoveredAt",
                            stamp(attribution?.discoveredAtMs ?: config.finishedAtMs)
                        )
                        .put(
                            "validation",
                            if (FacebookPostLinks.isPostPermalink(link)) {
                                "syntactically valid"
                            } else {
                                "unverified"
                            }
                        )
                )
            }
            block.put("links", linksArr)
            sourcesArr.put(block)
        }
        root.put("sources", sourcesArr)
        root.put("summary", summaryStats(results, config))
        return root.toString(2)
    }

    // ------------------------------------------------------------------
    // summary.json
    // ------------------------------------------------------------------

    /**
     * The run's headline statistics. Shared by summary.json itself and
     * the "summary" block of results.json so the two can never disagree.
     */
    fun summaryStats(results: List<FacebookProfileResult>, config: RunConfig): JSONObject {
        val discovered = results.sumOf { it.links.size }
        val unique = uniqueLinksRunWide(results).size
        val statusCounts = JSONObject()
        for (status in FacebookProfileStatus.values()) {
            val n = results.count { it.status == status }
            if (n > 0) statusCounts.put(status.name, n)
        }
        // Per-method yields over the FINAL (run-wide unique) link set:
        // each surviving link credited to the method that first found it.
        val methodYields = LinkedHashMap<String, Int>()
        val seen = HashSet<String>()
        for (result in results) {
            for (link in result.links) {
                if (!seen.add(link)) continue
                val method = result.linkAttribution[link]?.method ?: continue
                methodYields[method] = (methodYields[method] ?: 0) + 1
            }
        }
        val yieldsJson = JSONObject()
        for ((method, n) in methodYields) yieldsJson.put(method, n)
        return JSONObject()
            .put("sources", results.size)
            .put("perSourceLimit", config.perSourceLimit)
            .put("method", config.mode.toolArg())
            .put("discoveredCandidates", discovered)
            .put("uniqueResults", unique)
            .put("duplicatesRemoved", discovered - unique)
            .put("invalidCandidates", config.invalidSourceLines)
            .put("invalidCandidatesScope", "invalid source lines rejected before the run")
            .put("duplicateSourceLinesRemoved", config.duplicateSourceLines)
            .put("statusCounts", statusCounts)
            .put("methodYields", yieldsJson)
            .put("validationScope", "URL syntax only — live accessibility was not checked")
    }

    fun buildSummaryJson(results: List<FacebookProfileResult>, config: RunConfig): String {
        val root = summaryStats(results, config)
        root.put("task", "facebook_post_links_collector")
        root.put("startedAt", stamp(config.startedAtMs))
        root.put("finishedAt", stamp(config.finishedAtMs))
        root.put("statusSummary", FacebookPostLinks.statusSummaryLine(results))
        return root.toString(2)
    }
}
