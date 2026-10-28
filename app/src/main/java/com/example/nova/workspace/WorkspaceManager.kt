package com.example.nova.workspace

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Outcome of an Environment pack install (see [WorkspaceManager.installEnvironment]). */
data class EnvironmentInstallResult(
    val installedFiles: List<String>,
    val templateCopies: List<String>,
    val markerWritten: Boolean
)

class WorkspaceManager(
    val rootDir: File
) {
    companion object {
        val STANDARD_FOLDERS = listOf(
            "Projects",
            "Research",
            "Documents",
            "Images",
            "Code",
            "Downloads",
            "Memory",
            "Tasks",
            "Exports",
            "AI-Tasks"
        )

        /** Marker file written into the workspace root once the Environment pack is installed. */
        const val ENVIRONMENT_MARKER = ".environment-installed"

        /** Files of the bundled Environment pack (assets/environment/). */
        val ENVIRONMENT_FILES = listOf(
            "AGENT.md",
            "BROWSER.md",
            "SAFETY.md",
            "TASK_TEMPLATE.md",
            "Memory.md",
            "Training.md"
        )

        /**
         * Measured total size of the bundled Environment pack in bytes.
         * Measured 2026-10-28 from app/src/main/assets/environment/
         * (AGENT 1698 + BROWSER 1851 + SAFETY 1548 + TASK_TEMPLATE 687 +
         * Memory 887 + Training 1268 = 7,939 bytes). If the pack changes,
         * re-measure and update this number — the Files UI displays it.
         */
        const val ENVIRONMENT_PACK_BYTES: Long = 7939L

        fun environmentPackSizeLabel(): String =
            String.format(Locale.US, "%.3f MB", ENVIRONMENT_PACK_BYTES / 1_000_000.0)

        const val WORKSPACE_README = """# NOVA Workspace

This is your on-device workspace. Everything here lives only on your phone,
inside the app's private storage.

## Layout

- **Projects/** — project work and artifacts
- **Research/** — notes and extracted research findings
- **Documents/** — your notes, reports and markdown files (see Welcome.md)
- **Images/** — screenshots and visual media
- **Code/** — scripts and technical files
- **Downloads/** — files downloaded through the embedded browser
- **Memory/** — Memory.md (facts about you) and Training.md (agent procedures);
  the agent reads both before every task
- **Tasks/** — task scratch space
- **AI-Tasks/** — one folder per agent task run (task.md, log.txt,
  screenshots) plus the master index.md
- **Exports/** — generated CSVs, summaries and backups
- **Environment/** — the bundled agent Environment pack (AGENT.md,
  BROWSER.md, SAFETY.md, TASK_TEMPLATE.md and the Memory/Training starter
  templates), installed from the Files tab via "Install Environment"

## Rules

- Files are never overwritten by seeding or by the Environment install:
  if you edited a file, an incoming template is saved next to it as
  `<name>.template.md` instead.
- A hidden marker file `.environment-installed` records that the
  Environment pack has been installed.
"""
    }

        fun createDefault(context: Context): WorkspaceManager {
            val base = File(context.filesDir, "workspace")
            val manager = WorkspaceManager(base)
            manager.initializeDefaultStructure()
            return manager
        }
    }

    init {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
    }

    fun initializeDefaultStructure() {
        if (!rootDir.exists()) rootDir.mkdirs()
        for (folder in STANDARD_FOLDERS) {
            val dir = File(rootDir, folder)
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
        // Workspace root README explaining the layout (never overwrites edits)
        val rootReadme = File(rootDir, "README.md")
        if (!rootReadme.exists()) {
            rootReadme.writeText(WORKSPACE_README)
        }
        // Write initial welcome README if empty
        val readme = File(rootDir, "Documents/Welcome.md")
        if (!readme.exists()) {
            readme.writeText(
                """# Welcome to NOVA Workspace
                |
                |Your standalone native AI workspace is ready.
                |All files in this workspace are stored locally on your device.
                |
                |### Folders:
                |- **Projects/**: Workspace projects and artifacts
                |- **Research/**: Notes and extracted research findings
                |- **Documents/**: User notes, reports, and markdown files
                |- **Images/**: Screenshots and visual media
                |- **Code/**: Scripts and technical files
                |- **Downloads/**: Files downloaded via embedded browser
                |- **Memory/**: Persistent facts (Memory.md) & AI procedures (Training.md)
                |- **AI-Tasks/**: Autonomous task executions, artifacts, and replay logs
                |- **Exports/**: Generated CSV, summaries, and backups
                |
                |Explore, edit, and let the AI agent work securely within this directory!
                """.trimMargin()
            )
        }

        // Initialize Memory.md if not present
        val memoryFile = File(rootDir, "Memory/Memory.md")
        if (!memoryFile.exists()) {
            memoryFile.writeText(
                """# Personal AI Memory
                |
                |*Persistent facts, user preferences, and important context for NOVA Agent.*
                |
                |## User Profile
                |- Primary focus: High-productivity native Android AI automation
                |- Preferred communication style: Concise, direct, actionable
                |- Default workspace folders: Projects, Research, Documents, Images, Code
                |
                |## Verified Accounts & Domains
                |- Allowed sites: github.com, news.ycombinator.com, wikipedia.org, google.com
                |
                |## Key Project Notes
                |- NOVA Workspace uses Android Keystore encryption for all credentials.
                |- Vision models are preferred for browser navigation tasks.
                """.trimMargin()
            )
        }

        // Initialize Training.md if not present
        val trainingFile = File(rootDir, "Memory/Training.md")
        if (!trainingFile.exists()) {
            trainingFile.writeText(
                """# AI Agent Training & Procedures
                |
                |*Step-by-step procedures, taught user workflows, and behavioral rules.*
                |
                |## General Directives
                |1. Always inspect page state and Set-of-Marks labels before clicking interactive elements.
                |2. Capture a screenshot after significant page transitions or navigation steps.
                |3. Never submit payment forms, destructive deletions, or sensitive account changes without user approval.
                |4. When extracting information, organize findings in markdown under Research/ or Documents/.
                |
                |## Taught Workflows
                |### Workflow: Search and Summarize
                |1. Navigate to search engine or target domain.
                |2. Find the primary search input, type query, and submit.
                |3. Wait for search results, extract top relevant entries.
                |4. Save summary to `/Research/search_summary.md`.
                |
                |### Workflow: Safe Web Interaction
                |1. If CAPTCHA or 2FA appears, request 'Take Control' immediately.
                |2. Do not attempt automated credential entry without explicit vault policy permission.
                """.trimMargin()
            )
        }

        // Initialize AI-Tasks index if not present
        val indexFile = File(rootDir, "AI-Tasks/index.md")
        if (!indexFile.exists()) {
            indexFile.writeText(
                """# AI Tasks Master Index
                |
                |Index of all autonomous agent tasks executed within NOVA Workspace.
                |
                || Date | Task Name | Status | Summary | Folder |
                || --- | --- | --- | --- | --- |
                """.trimMargin()
            )
        }
    }

    // --- ENVIRONMENT PACK ---

    fun isEnvironmentInstalled(): Boolean = File(rootDir, ENVIRONMENT_MARKER).exists()

    /**
     * Installs the bundled Environment pack (assets/environment/) into the
     * workspace. This is a purely local asset copy — it works fully offline.
     *
     * Safe-overwrite rule: a file is copied only when the destination does
     * not exist or is byte-identical. When the user has edited the live
     * Memory.md / Training.md, the incoming template is saved alongside as
     * `<name>.template.md` instead of overwriting their content.
     */
    fun installEnvironment(context: Context): EnvironmentInstallResult {
        initializeDefaultStructure()
        val installed = mutableListOf<String>()
        val templateCopies = mutableListOf<String>()

        // 1) Full pack into Environment/
        val envDir = File(rootDir, "Environment")
        if (!envDir.exists()) envDir.mkdirs()
        for (name in ENVIRONMENT_FILES) {
            val bytes = context.assets.open("environment/$name").use { it.readBytes() }
            val dest = File(envDir, name)
            if (!dest.exists()) {
                dest.writeBytes(bytes)
                installed.add("Environment/$name")
            }
        }

        // 2) Memory.md / Training.md templates refresh the live Memory files
        //    only when untouched; otherwise they land as .template.md copies.
        for (name in listOf("Memory.md", "Training.md")) {
            val bytes = context.assets.open("environment/$name").use { it.readBytes() }
            val live = File(rootDir, "Memory/$name")
            live.parentFile?.mkdirs()
            when {
                !live.exists() -> {
                    live.writeBytes(bytes)
                    installed.add("Memory/$name")
                }
                live.readBytes().contentEquals(bytes) -> Unit // already up to date
                else -> {
                    val templateCopy = File(rootDir, "Memory/${name.removeSuffix(".md")}.template.md")
                    if (!templateCopy.exists() || !templateCopy.readBytes().contentEquals(bytes)) {
                        templateCopy.writeBytes(bytes)
                        templateCopies.add("Memory/${templateCopy.name}")
                    }
                }
            }
        }

        // 3) Marker file
        val marker = File(rootDir, ENVIRONMENT_MARKER)
        marker.writeText(
            "installed_at=${System.currentTimeMillis()}\npack_bytes=$ENVIRONMENT_PACK_BYTES\nfiles=${ENVIRONMENT_FILES.joinToString(",")}\n",
            Charsets.UTF_8
        )

        return EnvironmentInstallResult(
            installedFiles = installed,
            templateCopies = templateCopies,
            markerWritten = marker.exists()
        )
    }

    fun resolve(relativePath: String): File {
        return PathValidator.resolveCanonicalFile(rootDir, relativePath)
    }

    fun listFiles(subDirectory: String = "", sort: FileSortOption = FileSortOption.NAME_ASC): List<WorkspaceFile> {
        return try {
            if (!rootDir.exists()) {
                rootDir.mkdirs()
                initializeDefaultStructure()
            }
            val target = resolve(subDirectory)
            if (!target.exists()) {
                if (subDirectory.isBlank()) target.mkdirs() else return emptyList()
            }
            if (!target.isDirectory) return emptyList()

            val files = target.listFiles() ?: return emptyList()
            val items = files.map { file ->
                val rel = file.relativeTo(rootDir).path.replace('\\', '/')
                WorkspaceFile(
                    relativePath = rel,
                    name = file.name,
                    isDirectory = file.isDirectory,
                    sizeBytes = if (file.isDirectory) 0 else file.length(),
                    lastModified = file.lastModified(),
                    extension = file.extension.lowercase()
                )
            }

            when (sort) {
                FileSortOption.NAME_ASC -> items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                FileSortOption.NAME_DESC -> items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).reversed()
                FileSortOption.DATE_DESC -> items.sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified }))
                FileSortOption.DATE_ASC -> items.sortedWith(compareBy({ !it.isDirectory }, { it.lastModified }))
                FileSortOption.SIZE_DESC -> items.sortedWith(compareBy({ !it.isDirectory }, { -it.sizeBytes }))
                FileSortOption.SIZE_ASC -> items.sortedWith(compareBy({ !it.isDirectory }, { it.sizeBytes }))
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun createDirectory(relativePath: String): Boolean {
        val target = resolve(relativePath)
        return if (!target.exists()) target.mkdirs() else true
    }

    fun readFile(relativePath: String): String {
        val target = resolve(relativePath)
        if (!target.exists()) throw NoSuchFileException(target, null, "File does not exist: $relativePath")
        if (target.isDirectory) throw IllegalArgumentException("Cannot read directory as file: $relativePath")
        return target.readText(Charsets.UTF_8)
    }

    fun writeFile(relativePath: String, content: String): Boolean {
        val target = resolve(relativePath)
        target.parentFile?.mkdirs()
        target.writeText(content, Charsets.UTF_8)
        // Verify creation
        return target.exists() && target.isFile
    }

    fun appendFile(relativePath: String, content: String): Boolean {
        val target = resolve(relativePath)
        target.parentFile?.mkdirs()
        FileOutputStream(target, true).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        }
        return target.exists()
    }

    fun deleteFile(relativePath: String): Boolean {
        val target = resolve(relativePath)
        if (!target.exists()) return false
        return if (target.isDirectory) target.deleteRecursively() else target.delete()
    }

    fun renameFile(sourceRelative: String, newName: String): Boolean {
        val source = resolve(sourceRelative)
        if (!source.exists()) return false
        val dest = File(source.parentFile, newName)
        // Security check dest path as well
        PathValidator.resolveCanonicalFile(rootDir, dest.relativeTo(rootDir).path)
        return source.renameTo(dest)
    }

    fun moveFile(sourceRelative: String, destFolderRelative: String): Boolean {
        val source = resolve(sourceRelative)
        val destFolder = resolve(destFolderRelative)
        if (!source.exists() || !destFolder.isDirectory) return false
        val target = File(destFolder, source.name)
        return source.renameTo(target)
    }

    fun copyFile(sourceRelative: String, destFolderRelative: String): Boolean {
        val source = resolve(sourceRelative)
        val destFolder = resolve(destFolderRelative)
        if (!source.exists() || !destFolder.isDirectory) return false
        val target = File(destFolder, source.name)
        return if (source.isDirectory) {
            source.copyRecursively(target, overwrite = true)
        } else {
            source.copyTo(target, overwrite = true)
            target.exists()
        }
    }

    fun searchFiles(query: String, startDir: String = ""): List<WorkspaceFile> {
        return try {
            val target = resolve(startDir)
            if (!target.exists()) return emptyList()
            val q = query.lowercase().trim()

            val results = mutableListOf<WorkspaceFile>()
            target.walkTopDown().forEach { file ->
                if (file != target && file.name.lowercase().contains(q)) {
                    results.add(
                        WorkspaceFile(
                            relativePath = file.relativeTo(rootDir).path.replace('\\', '/'),
                            name = file.name,
                            isDirectory = file.isDirectory,
                            sizeBytes = if (file.isDirectory) 0 else file.length(),
                            lastModified = file.lastModified(),
                            extension = file.extension.lowercase()
                        )
                    )
                }
            }
            results
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun getFileMetadata(relativePath: String): FileMetadata {
        val file = resolve(relativePath)
        if (!file.exists()) throw NoSuchFileException(file, null, "File does not exist: $relativePath")

        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        val dateStr = sdf.format(Date(file.lastModified()))
        val ext = file.extension.lowercase()
        val size = if (file.isDirectory) 0L else file.length()
        val readable = formatReadableSize(size)

        var lines: Int? = null
        if (file.isFile && isTextExtension(ext) && size < 5 * 1024 * 1024) {
            lines = try { file.readLines().size } catch (e: Exception) { null }
        }

        return FileMetadata(
            relativePath = file.relativeTo(rootDir).path.replace('\\', '/'),
            name = file.name,
            isDirectory = file.isDirectory,
            sizeBytes = size,
            readableSize = readable,
            lastModifiedDate = dateStr,
            lineCount = lines,
            mimeType = getMimeType(ext)
        )
    }

    /**
     * Generates a CSV file from headers and row data, writing to Exports/ folder.
     */
    fun exportCsv(fileName: String, headers: List<String>, rows: List<List<String>>): String {
        val safeName = if (fileName.endsWith(".csv", ignoreCase = true)) fileName else "$fileName.csv"
        val exportRel = "Exports/$safeName"
        val target = resolve(exportRel)
        target.parentFile?.mkdirs()

        val sb = StringBuilder()
        sb.append(headers.joinToString(",") { escapeCsvCell(it) }).append("\n")
        for (row in rows) {
            sb.append(row.joinToString(",") { escapeCsvCell(it) }).append("\n")
        }
        target.writeText(sb.toString(), Charsets.UTF_8)
        return exportRel
    }

    private fun escapeCsvCell(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun formatReadableSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    fun isTextExtension(ext: String): Boolean {
        return ext in listOf("txt", "md", "json", "csv", "html", "css", "js", "xml", "log", "kt", "java", "py", "sh", "yaml", "yml", "sql")
    }

    private fun getMimeType(ext: String): String {
        return when (ext) {
            "txt", "log" -> "text/plain"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "html" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "xml" -> "application/xml"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    fun readFileWithLimit(relativePath: String, maxBytes: Long = 2 * 1024 * 1024): String {
        val target = resolve(relativePath)
        if (!target.exists()) throw NoSuchFileException(target, null, "File does not exist: $relativePath")
        if (target.isDirectory) throw IllegalArgumentException("Cannot read directory as file: $relativePath")
        if (target.length() > maxBytes) {
            target.inputStream().use { input ->
                val buffer = ByteArray(maxBytes.toInt())
                val read = input.read(buffer)
                return String(buffer, 0, read, Charsets.UTF_8) + "\n\n... [File truncated for preview (size: ${formatReadableSize(target.length())})] ..."
            }
        }
        return target.readText(Charsets.UTF_8)
    }

    fun importFromInputStream(inputStream: java.io.InputStream, relativePath: String): Boolean {
        val target = resolve(relativePath)
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            inputStream.copyTo(out)
        }
        return target.exists()
    }

    fun createTaskDirectory(taskFolderName: String): File {
        val safeName = taskFolderName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val dir = File(rootDir, "AI-Tasks/$safeName")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "screenshots").mkdirs()
        File(dir, "downloads").mkdirs()
        return dir
    }

    fun saveTaskRun(
        taskFolderName: String,
        goal: String,
        plan: String,
        status: String,
        steps: List<String>,
        logText: String,
        resultSummary: String
    ) {
        val dir = createTaskDirectory(taskFolderName)
        val taskMd = File(dir, "task.md")
        val content = """# Task: $taskFolderName
        |
        |- **Status**: $status
        |- **Date**: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}
        |
        |## Goal
        |$goal
        |
        |## Plan
        |$plan
        |
        |## Steps Executed
        |${steps.joinToString("\n") { "- $it" }}
        |
        |## Result Summary
        |$resultSummary
        """.trimMargin()
        taskMd.writeText(content, Charsets.UTF_8)

        val logFile = File(dir, "log.txt")
        logFile.writeText(logText, Charsets.UTF_8)
    }

    fun updateTaskIndex(taskFolderName: String, status: String, summary: String) {
        val indexFile = File(rootDir, "AI-Tasks/index.md")
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val cleanSummary = summary.replace("|", "/").replace("\n", " ").take(100)
        val row = "| $dateStr | $taskFolderName | $status | $cleanSummary | [View](/AI-Tasks/$taskFolderName) |\n"
        if (!indexFile.exists()) {
            indexFile.writeText("# AI Tasks Master Index\n\n| Date | Task Name | Status | Summary | Folder |\n| --- | --- | --- | --- | --- |\n$row", Charsets.UTF_8)
        } else {
            FileOutputStream(indexFile, true).use {
                it.write(row.toByteArray(Charsets.UTF_8))
            }
        }
    }

    fun backupMemoryFile(fileName: String) {
        val src = resolve("Memory/$fileName")
        if (!src.exists()) return
        val backupDir = resolve("Memory/.backups")
        backupDir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val backup = File(backupDir, "${fileName.substringBeforeLast('.')}_$ts.md")
        src.copyTo(backup, overwrite = true)
    }

    fun listMemoryBackups(fileName: String): List<String> {
        val backupDir = resolve("Memory/.backups")
        if (!backupDir.exists() || !backupDir.isDirectory) return emptyList()
        val prefix = fileName.substringBeforeLast('.')
        return backupDir.listFiles()
            ?.filter { it.name.startsWith(prefix) }
            ?.map { it.name }
            ?.sortedDescending() ?: emptyList()
    }

    fun restoreMemoryBackup(backupFileName: String, targetFileName: String): Boolean {
        val backup = resolve("Memory/.backups/$backupFileName")
        if (!backup.exists()) return false
        val target = resolve("Memory/$targetFileName")
        backup.copyTo(target, overwrite = true)
        return true
    }
}

