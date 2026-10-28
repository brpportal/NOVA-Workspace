package com.example.nova.workspace

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            "Exports"
        )

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
                |- **Exports/**: Generated CSV, summaries, and backups
                |
                |Explore, edit, and let the AI agent work securely within this directory!
                """.trimMargin()
            )
        }
    }

    private fun resolve(relativePath: String): File {
        return PathValidator.resolveCanonicalFile(rootDir, relativePath)
    }

    fun listFiles(subDirectory: String = "", sort: FileSortOption = FileSortOption.NAME_ASC): List<WorkspaceFile> {
        val target = resolve(subDirectory)
        if (!target.exists() || !target.isDirectory) return emptyList()

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

        return when (sort) {
            FileSortOption.NAME_ASC -> items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            FileSortOption.NAME_DESC -> items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).reversed()
            FileSortOption.DATE_DESC -> items.sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified }))
            FileSortOption.DATE_ASC -> items.sortedWith(compareBy({ !it.isDirectory }, { it.lastModified }))
            FileSortOption.SIZE_DESC -> items.sortedWith(compareBy({ !it.isDirectory }, { -it.sizeBytes }))
            FileSortOption.SIZE_ASC -> items.sortedWith(compareBy({ !it.isDirectory }, { it.sizeBytes }))
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
        return results
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
}
