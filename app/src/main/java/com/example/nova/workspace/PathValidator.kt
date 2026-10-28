package com.example.nova.workspace

import java.io.File
import java.io.IOException

object PathValidator {
    /**
     * Resolves and canonicalizes a path against the workspace root.
     * Throws SecurityException if the target path attempts to escape the root.
     */
    fun resolveCanonicalFile(root: File, relativeOrSubPath: String): File {
        val cleanSubPath = relativeOrSubPath.trim()
            .removePrefix("/")
            .removePrefix("\\")

        // Explicit check against basic traversal patterns
        if (cleanSubPath.contains("..") || cleanSubPath.startsWith("..") || cleanSubPath.endsWith("..")) {
            val segments = cleanSubPath.split('/', '\\')
            if (segments.any { it == ".." }) {
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
