package com.example.nova.workspace

data class WorkspaceFile(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val extension: String
)

data class FileMetadata(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val readableSize: String,
    val lastModifiedDate: String,
    val lineCount: Int? = null,
    val mimeType: String
)

enum class FileSortOption {
    NAME_ASC,
    NAME_DESC,
    DATE_DESC,
    DATE_ASC,
    SIZE_DESC,
    SIZE_ASC
}

enum class FileViewMode {
    LIST,
    GRID
}

enum class FileCategory {
    FOLDER,
    DOCUMENT,
    MARKDOWN,
    CODE,
    IMAGE,
    DATA,
    MEDIA,
    OTHER
}

