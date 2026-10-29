package com.example.nova.linux

/**
 * Static configuration for Spark's Linux Mode (real PRoot + Ubuntu rootfs).
 *
 * Sources are official only:
 * - PRoot + libtalloc + libandroid-shmem binaries ship INSIDE the APK
 *   (jniLibs, built from the official Termux apt repo .deb packages —
 *   see the build notes in the release report).
 * - The Ubuntu rootfs is downloaded at setup time from Ubuntu's official
 *   cdimage server and verified against the official SHA256SUMS file
 *   published in the same directory.
 */
object LinuxConfig {

    /** Ubuntu 24.04 LTS (noble) base rootfs for ARM64, from cdimage.ubuntu.com. */
    const val ROOTFS_RELEASE_DIR =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"
    const val ROOTFS_FILE_NAME = "ubuntu-base-24.04.5-base-arm64.tar.gz"
    const val ROOTFS_URL = "$ROOTFS_RELEASE_DIR/$ROOTFS_FILE_NAME"
    const val SHA256SUMS_URL = "$ROOTFS_RELEASE_DIR/SHA256SUMS"
    const val ROOTFS_LABEL = "Ubuntu 24.04 LTS (noble) base, ARM64"

    /** Measured size of the 24.04.x arm64 base tarball (~29.9 MB). */
    const val ROOTFS_DOWNLOAD_BYTES_APPROX = 30_000_000L

    /** Free space demanded before an install starts (rootfs + tarball + slack). */
    const val MIN_FREE_BYTES = 500_000_000L

    /** Guest paths. */
    const val GUEST_WORKSPACE = "/workspace"
    const val GUEST_HOME = "/root"

    /** Only ABI shipped in this build (the Termux debs used are aarch64). */
    const val REQUIRED_ABI = "arm64-v8a"

    /** jniLibs-packaged runtime binaries (extracted to nativeLibraryDir). */
    const val PROOT_LIB = "libproot.so"
    const val PROOT_LOADER_LIB = "libproot-loader.so"
    const val TALLOC_LIB = "libtalloc.so"
    const val SHMEM_LIB = "libandroid-shmem.so"

    /** Fallback DNS when the device's own DNS servers cannot be read. */
    val FALLBACK_DNS = listOf("8.8.8.8", "1.1.1.1")

    /**
     * Parses the expected SHA-256 for [fileName] out of an official
     * SHA256SUMS document (lines look like:
     * `a91d...  *ubuntu-base-24.04.5-base-arm64.tar.gz` or two spaces for
     * text mode). Returns null when the file is not listed. Pure.
     */
    fun parseSha256Sums(sumsText: String, fileName: String): String? {
        for (rawLine in sumsText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) continue
            val hash = parts[0].lowercase()
            val name = parts[1].removePrefix("*").trim()
            if (name == fileName && hash.matches(Regex("[0-9a-f]{64}"))) {
                return hash
            }
        }
        return null
    }

    /** Builds the guest /etc/resolv.conf content for the given DNS servers. Pure. */
    fun buildResolvConf(dnsServers: List<String>): String {
        val servers = dnsServers.filter { it.isNotBlank() }.distinct()
            .ifEmpty { FALLBACK_DNS }
        return servers.joinToString(separator = "") { "nameserver $it\n" }
    }

    /** Builds the guest /etc/hosts content. Pure. */
    fun buildHosts(): String =
        "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"

    /**
     * Extracts PRETTY_NAME from /etc/os-release content
     * (e.g. `Ubuntu 24.04.3 LTS`). Returns null when absent. Pure.
     */
    fun parseOsReleasePrettyName(osReleaseText: String): String? {
        for (rawLine in osReleaseText.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("PRETTY_NAME=")) {
                return line.removePrefix("PRETTY_NAME=").trim().trim('"')
                    .ifBlank { null }
            }
        }
        return null
    }
}
