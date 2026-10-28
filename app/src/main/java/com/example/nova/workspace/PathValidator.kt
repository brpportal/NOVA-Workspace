package com.example.nova.workspace

import java.io.File
import java.io.IOException

object PathValidator {
    /**
     * Resolves and canonicalizes a path against the workspace root.
     * Prevents escaping the workspace directory while supporting normal filenames and special characters.
     */
    fun resolveCanonicalFile(root: File, relativeOrSubPath: String): File {
        val cleanSubPath = relativeOrSubPath.trim()
            .replace('\\', '/')
            .removePrefix("/")

        // Check if any path segment is explicitly ".."
        val segments = cleanSubPath.split('/')
        for (segment in segments) {
            if (segment.trim() == "..") {
                throw SecurityException("Access Denied: Path traversal detected ('..') in path '$relativeOrSubPath'")
            }
        }

        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, cleanSubPath).canonicalFile

        if (!candidate.path.startsWith(canonicalRoot.path)) {
            throw SecurityException("Access Denied: Canonical path '${candidate.path}' escapes workspace root '${canonicalRoot.path}'")
        }

        return candidate
    }

    /**
     * Returns true if safe, false if path traversal is detected.
     */
    fun isSafePath(root: File, relativePath: String): Boolean {
        return try {
            resolveCanonicalFile(root, relativePath)
            true
        } catch (e: Exception) {
            false
        }
    }
}
