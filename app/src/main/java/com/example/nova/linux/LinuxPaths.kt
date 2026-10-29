package com.example.nova.linux

import java.io.File

/**
 * On-disk layout for Linux Mode. Everything lives under
 * `filesDir/linux/` — deliberately OUTSIDE the user workspace
 * (filesDir/workspace), so the rootfs never pollutes the file manager;
 * the workspace is bind-mounted INTO the guest at /workspace instead.
 *
 * Pure java.io — unit-testable without Android.
 */
class LinuxPaths(
    filesDir: File,
    val nativeLibDir: String,
    val workspaceDir: File
) {
    /** filesDir/linux — the whole Linux Mode home. */
    val linuxDir: File = File(filesDir, "linux")

    /** The extracted Ubuntu root filesystem (guest "/"). */
    val rootfsDir: File = File(linuxDir, "rootfs")

    /** PRoot's own temp dir (PROOT_TMP_DIR) — must be app-writable. */
    val prootTmpDir: File = File(linuxDir, "tmp")

    /** Downloaded rootfs tarball staging. */
    val downloadsDir: File = File(linuxDir, "downloads")
    val tarballFile: File = File(downloadsDir, LinuxConfig.ROOTFS_FILE_NAME)
    val tarballPartFile: File = File(downloadsDir, LinuxConfig.ROOTFS_FILE_NAME + ".part")

    /** Install + session logs. */
    val logsDir: File = File(linuxDir, "logs")
    val installLogFile: File = File(logsDir, "install.log")

    /** Persisted install state (see LinuxStateCodec). */
    val stateFile: File = File(linuxDir, "state.properties")

    /** Terminal command history (one command per line, newest last). */
    val historyFile: File = File(linuxDir, "history.txt")

    // Runtime binaries, extracted by Android from the APK's jniLibs into
    // nativeLibraryDir — the one app-owned directory whose files may be
    // executed on Android 10+ (writable data dirs are noexec for
    // targetSdk 29+ apps).
    val prootBinary: File = File(nativeLibDir, LinuxConfig.PROOT_LIB)
    val prootLoader: File = File(nativeLibDir, LinuxConfig.PROOT_LOADER_LIB)
    val tallocLib: File = File(nativeLibDir, LinuxConfig.TALLOC_LIB)
    val shmemLib: File = File(nativeLibDir, LinuxConfig.SHMEM_LIB)

    /** True when every runtime binary the linker needs is present. */
    fun runtimeBinariesPresent(): Boolean =
        prootBinary.isFile && prootLoader.isFile && tallocLib.isFile && shmemLib.isFile

    /** True when an extracted guest /bin/bash exists (rootfs plausibly intact). */
    fun guestBashPresent(): Boolean = File(rootfsDir, "usr/bin/bash").isFile ||
        File(rootfsDir, "bin/bash").isFile

    /**
     * Validates the workspace bind source. Returns null when the bind is
     * safe, or a human-readable problem otherwise. The workspace must be a
     * real directory, must not be the rootfs itself (binding the guest
     * root over /workspace would be nonsense), and its canonical path
     * must not contain the rootfs (no recursive bind of the guest into
     * itself).
     */
    fun workspaceBindProblem(): String? {
        if (!workspaceDir.isDirectory) return "Workspace directory does not exist: ${workspaceDir.path}"
        val ws = runCatching { workspaceDir.canonicalPath }.getOrElse { return "Workspace path cannot be resolved" }
        val root = runCatching { rootfsDir.canonicalPath }.getOrElse { return "Rootfs path cannot be resolved" }
        if (ws == root) return "Workspace and rootfs must be different directories"
        if (root.startsWith(ws + File.separator)) {
            return "Rootfs must not live inside the bind-mounted workspace"
        }
        return null
    }
}

/**
 * Path-safety for tar extraction: an entry may only land strictly inside
 * the destination directory. Rejects absolute names and `..` escapes.
 * Pure — the extractor refuses any entry for which this returns null.
 */
object LinuxPathSafety {
    fun entryTarget(destDir: File, entryName: String): File? {
        if (entryName.isBlank()) return null
        // Tar names are POSIX; strip leading "/" and "./" defensively.
        val cleaned = entryName.replace('\\', '/').trimStart('/').removePrefix("./")
        if (cleaned.isBlank() || cleaned == ".") return null
        val dest = File(destDir, cleaned)
        val destCanon = runCatching { dest.canonicalPath }.getOrNull() ?: return null
        val baseCanon = runCatching { destDir.canonicalPath }.getOrNull() ?: return null
        return if (destCanon == baseCanon || destCanon.startsWith(baseCanon + File.separator)) {
            dest
        } else {
            null
        }
    }

    /** Total size of a directory tree in bytes (missing dir = 0). Pure java.io. */
    fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { f -> if (f.isFile) total += f.length() }
        return total
    }
}
