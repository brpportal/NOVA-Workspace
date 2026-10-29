package com.example.nova.linux

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Streaming extractor for the Ubuntu rootfs tarball (.tar.gz), built on
 * Apache Commons Compress so GNU long names, pax headers, symlinks and
 * hardlinks are handled correctly.
 *
 * Android/root constraints handled honestly:
 * - Every entry path passes LinuxPathSafety — a hostile or corrupt entry
 *   can never escape the destination directory.
 * - Device nodes and FIFOs are SKIPPED (creating them needs root). They
 *   are unnecessary: PRoot bind-mounts the host /dev into the guest.
 * - File modes from the tar are applied with Os.chmod so executables in
 *   the rootfs stay executable; symlinks are created with Os.symlink.
 */
object TarExtractor {

    data class Stats(
        val files: Int = 0,
        val directories: Int = 0,
        val symlinks: Int = 0,
        val hardlinks: Int = 0,
        val skipped: Int = 0,
        val bytesWritten: Long = 0L
    )

    /**
     * Extracts a gzip-compressed tar stream into [destDir].
     * [onProgress] receives cumulative uncompressed bytes read.
     */
    fun extractGzipTar(input: InputStream, destDir: File, onProgress: (Long) -> Unit = {}): Stats {
        destDir.mkdirs()
        var files = 0
        var dirs = 0
        var symlinks = 0
        var hardlinks = 0
        var skipped = 0
        var bytes = 0L
        val buffer = ByteArray(256 * 1024)

        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(input))).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextEntry as TarArchiveEntry? ?: break
                val target = LinuxPathSafety.entryTarget(destDir, entry.name)
                if (target == null) {
                    skipped++
                    continue
                }
                when {
                    entry.isDirectory -> {
                        target.mkdirs()
                        applyMode(target, entry.mode)
                        dirs++
                    }
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        deleteIfPresent(target)
                        Os.symlink(entry.linkName, target.absolutePath)
                        symlinks++
                    }
                    entry.isLink -> {
                        // Hardlink: link to another extracted path (also safety-checked).
                        val source = LinuxPathSafety.entryTarget(destDir, entry.linkName)
                        if (source != null && source.exists()) {
                            target.parentFile?.mkdirs()
                            if (target.exists()) target.delete()
                            Os.link(source.absolutePath, target.absolutePath)
                            hardlinks++
                        } else {
                            skipped++
                        }
                    }
                    entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> {
                        // Cannot mknod without root; /dev is bind-mounted by PRoot.
                        skipped++
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            while (true) {
                                val read = tar.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                bytes += read
                            }
                        }
                        applyMode(target, entry.mode)
                        files++
                    }
                    else -> skipped++
                }
                onProgress(bytes)
            }
        }
        return Stats(files, dirs, symlinks, hardlinks, skipped, bytes)
    }

    /** Deletes [file] if anything (file, dir or dangling symlink) sits at its path. */
    private fun deleteIfPresent(file: File) {
        // File.exists() is false for a dangling symlink, so probe with lstat too.
        val present = file.exists() ||
            runCatching { Os.lstat(file.absolutePath); true }.getOrDefault(false)
        if (present) file.delete()
    }

    private fun applyMode(file: File, tarMode: Int) {
        // tar mode carries permission bits in the low 12 bits.
        val mode = tarMode and 0xFFF
        if (mode != 0) {
            runCatching { Os.chmod(file.absolutePath, mode) }
        }
    }
}
