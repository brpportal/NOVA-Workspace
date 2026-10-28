package com.example.nova.agent

import com.example.nova.ai.ProviderErrors
import com.example.nova.workspace.WorkspaceManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The agent's persistent failure memory: [LESSONS_PATH] in the workspace
 * root (user-visible in the Files tab, user-editable like every workspace
 * file).
 *
 * When a task fails — or one tool errors repeatedly inside a task — a
 * structured lesson is recorded: date, the task goal, the failed step, the
 * human-readable error, and a corrective rule ("Next time: …") generated
 * from the failure's class by [classify]. Lessons are deduplicated by
 * (tool + error class): a repeat increments the lesson's "Seen: N times"
 * counter instead of appending a copy. The most recent lessons are injected
 * into every new task's context (see ContextBuilder), which is what makes
 * the mechanism change behaviour rather than just keep a log.
 */
class LessonsStore(private val workspaceManager: WorkspaceManager) {

    companion object {
        const val LESSONS_PATH = "Lessons.md"
        const val MAX_LESSONS = 50
        const val HEADER = "# Spark Lessons — learned from failures\n\n" +
            "Recorded automatically when tasks fail. Each lesson states what went wrong and the rule to apply next time.\n"

        private const val BLOCK_PREFIX = "## Lesson [key: "
    }

    private data class LessonBlock(
        val key: String,
        val body: String // the full block text, starting at BLOCK_PREFIX
    )

    /** Classifies a failure into (error class, corrective rule). */
    fun classify(toolName: String?, errorText: String): Pair<String, String> {
        val text = errorText.lowercase()
        return when {
            text.contains("invalid tool message") || text.contains("tool call id") ->
                "TOOL_HISTORY" to "Keep tool history paired: every tool result must answer a tool call the assistant actually made. If a provider rejects the history, start a fresh task so the history is rebuilt cleanly instead of retrying the same request."
            text.contains("duplicate tool loop") || text.contains("duplicate") && text.contains("loop") ->
                "DUPLICATE_LOOP" to "Never repeat the identical action after it produced the same result. Change approach: re-observe the page, scroll, pick a different element or tool — a third identical attempt is forbidden."
            text.contains("429") || text.contains("quota") || text.contains("rate limit") || text.contains("provider limit") ->
                "PROVIDER_LIMIT" to "The provider's limit was hit. Do not retry in a tight loop: wait, or switch to another model/provider with available quota in Settings → AI Engine."
            text.contains("not found") && (text.contains("element") || text.contains("index")) ->
                "ELEMENT_NOT_FOUND" to "Call observe_page to get fresh element indices before clicking or typing. Indices from an older page state are invalid — never reuse them after the page changed."
            text.contains("permission denied") || text.contains("permission") && text.contains("blocks") ->
                "PERMISSION_DENIED" to "This action is blocked by security policy. Do not retry it: choose an allowed alternative, or ask the user to approve / take control."
            text.contains("missing required parameter") || text.contains("malformed json") || text.contains("invalid tool call") ->
                "INVALID_ARGUMENTS" to "Check the tool's parameter schema before calling: supply every required parameter with the right type, and valid JSON arguments."
            text.contains("timed out") || text.contains("timeout") ->
                "TIMEOUT" to "Wait for the page or operation to finish (wait_for_load after navigation) and split long work into smaller steps instead of retrying the whole action at once."
            text.contains("manual control") ->
                "USER_CONTROL" to "The user held the browser. Do not fight manual control: pause, and resume only after the user hands control back."
            text.contains("http 5") || text.contains("provider error (5") ->
                "PROVIDER_SERVER" to "The provider had a server-side error. Retry once after a pause; if it persists, report it as a provider outage rather than a task mistake."
            text.contains("step limit") || text.contains("maximum tool") ->
                "STEP_LIMIT" to "Break the goal into smaller sub-tasks and verify progress after each one. If a step repeats without progress, stop early and summarize what is done instead of consuming the whole step budget."
            else ->
                "GENERAL" to "Re-observe the current state before retrying '${toolName ?: "the failed step"}'. If it fails twice the same way, change approach or stop and report exactly what happened."
        }
    }

    /**
     * Records a lesson for a failure, or bumps the "Seen" counter of the
     * existing lesson for the same (tool + error class). Best-effort: a
     * lessons-write failure must never fail the task itself.
     */
    @Synchronized
    fun recordLesson(goal: String, failedStep: String, toolName: String?, rawError: String) {
        try {
            val (errorClass, rule) = classify(toolName, rawError)
            val key = "${toolName ?: "task"}|$errorClass"
            val humanError = ProviderErrors.summarizeErrorText(rawError).take(300)
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val today = now.substring(0, 10)

            val blocks = readBlocks().toMutableList()
            val existingIdx = blocks.indexOfFirst { it.key == key }
            if (existingIdx >= 0) {
                val old = blocks[existingIdx]
                val seen = Regex("""- Seen: (\d+) times""").find(old.body)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                var body = old.body
                body = if (body.contains("- Seen:")) {
                    body.replace(Regex("""- Seen: \d+ times \(last: [^)]*\)"""), "- Seen: ${seen + 1} times (last: $today)")
                } else {
                    body.trimEnd() + "\n- Seen: ${seen + 1} times (last: $today)\n"
                }
                blocks[existingIdx] = old.copy(body = body)
            } else {
                val body = buildString {
                    append(BLOCK_PREFIX).append(key).append("]\n")
                    append("- Date: ").append(now).append('\n')
                    append("- Task goal: ").append(goal.replace('\n', ' ').take(120)).append('\n')
                    append("- Failed step: ").append(failedStep.replace('\n', ' ').take(120)).append('\n')
                    append("- Error: ").append(humanError).append('\n')
                    append("- Next time: ").append(rule).append('\n')
                    append("- Seen: 1 times (last: ").append(today).append(")\n")
                }
                blocks.add(LessonBlock(key, body))
            }
            while (blocks.size > MAX_LESSONS) blocks.removeAt(0)
            writeBlocks(blocks)
        } catch (ignored: Exception) {
            // Lessons are an aid, never a failure source.
        }
    }

    /**
     * The most recent lessons, oldest-to-newest, for injection into a new
     * task's context. Bounded by count and characters.
     */
    fun recentLessonsText(maxCount: Int = 10, maxChars: Int = 3200): String {
        return try {
            val blocks = readBlocks()
            if (blocks.isEmpty()) return ""
            val recent = blocks.takeLast(maxCount).joinToString("\n") { it.body.trim() }
            if (recent.length <= maxChars) recent else recent.takeLast(maxChars)
        } catch (e: Exception) {
            ""
        }
    }

    private fun lessonsFile(): File = File(workspaceManager.rootDir, LESSONS_PATH)

    private fun readBlocks(): List<LessonBlock> {
        val file = lessonsFile()
        if (!file.exists()) return emptyList()
        val text = file.readText(Charsets.UTF_8)
        val blocks = mutableListOf<LessonBlock>()
        var currentKey: String? = null
        var current = StringBuilder()
        fun flush() {
            val k = currentKey
            if (k != null) blocks.add(LessonBlock(k, current.toString()))
            current = StringBuilder()
        }
        for (line in text.lineSequence()) {
            if (line.startsWith(BLOCK_PREFIX)) {
                flush()
                currentKey = line.removePrefix(BLOCK_PREFIX).substringBefore(']')
                current.append(line).append('\n')
            } else if (currentKey != null) {
                current.append(line).append('\n')
            }
        }
        flush()
        return blocks
    }

    private fun writeBlocks(blocks: List<LessonBlock>) {
        val file = lessonsFile()
        file.parentFile?.mkdirs()
        val sb = StringBuilder(HEADER)
        sb.append('\n')
        for (b in blocks) {
            sb.append(b.body.trimEnd()).append("\n\n")
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
    }
}
