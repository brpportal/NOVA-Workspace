package com.example.nova.linux

/**
 * Install lifecycle of the Linux environment. This is persisted state —
 * it describes the ROOTFS on disk, not whether a command happens to be
 * running right now (that is the terminal session's own live status).
 */
enum class LinuxInstallState {
    /** No rootfs on disk (never installed, or reset). */
    NOT_INSTALLED,

    /** An install/repair is in flight in this process. */
    INSTALLING,

    /** Rootfs present and the real self-test (uname/pwd/ls via PRoot) passed. */
    READY,

    /** Install failed or was interrupted; the log explains why. Repair/Reset offered. */
    ERROR
}

/** Persisted facts about the Linux environment. */
data class LinuxInstallInfo(
    val state: LinuxInstallState = LinuxInstallState.NOT_INSTALLED,
    /** Current/last install stage label while INSTALLING (e.g. "Extracting rootfs"). */
    val stage: String? = null,
    /** Rootfs release that was installed (e.g. "Ubuntu 24.04 LTS (noble) base, ARM64"). */
    val rootfsLabel: String? = null,
    /** Guest-reported Ubuntu version from the self-test (e.g. "Ubuntu 24.04.3 LTS"). */
    val ubuntuVersion: String? = null,
    /** SHA-256 the installed tarball was verified against. */
    val tarballSha256: String? = null,
    val installedAtMs: Long = 0L,
    /** Human-readable failure reason while ERROR. */
    val lastError: String? = null
)

/**
 * Legal state transitions. Pure — the state machine is:
 *
 *   NOT_INSTALLED ──install──▶ INSTALLING ──self-test ok──▶ READY
 *        ▲                        │                            │
 *        │                        └──failure──▶ ERROR          │ reinstall
 *        │                                        │            ▼
 *        └────────────── reset (any state) ───────┴──── INSTALLING
 *
 * ERROR ──repair──▶ INSTALLING is also allowed (re-configure + self-test).
 */
object LinuxStateMachine {
    fun canTransition(from: LinuxInstallState, to: LinuxInstallState): Boolean = when (to) {
        // Reset is always allowed.
        LinuxInstallState.NOT_INSTALLED -> true
        LinuxInstallState.INSTALLING ->
            from == LinuxInstallState.NOT_INSTALLED ||
                from == LinuxInstallState.ERROR ||
                from == LinuxInstallState.READY
        LinuxInstallState.READY -> from == LinuxInstallState.INSTALLING
        LinuxInstallState.ERROR ->
            from == LinuxInstallState.INSTALLING || from == LinuxInstallState.READY
    }

    /** Returns [info] moved to [to], or throws when the transition is illegal. */
    fun transition(info: LinuxInstallInfo, to: LinuxInstallState): LinuxInstallInfo {
        require(canTransition(info.state, to)) {
            "Illegal Linux state transition ${info.state} -> $to"
        }
        return info.copy(state = to)
    }
}

/**
 * Key=value text codec for the persisted state file
 * (filesDir/linux/state.properties). Pure and deliberately boring:
 * unknown keys are ignored, missing keys fall back to defaults, and a
 * corrupt state value decodes to NOT_INSTALLED rather than crashing.
 */
object LinuxStateCodec {
    fun encode(info: LinuxInstallInfo): String = buildString {
        append("state=").append(info.state.name).append('\n')
        info.stage?.let { append("stage=").append(it.replace('\n', ' ')).append('\n') }
        info.rootfsLabel?.let { append("rootfs_label=").append(it.replace('\n', ' ')).append('\n') }
        info.ubuntuVersion?.let { append("ubuntu_version=").append(it.replace('\n', ' ')).append('\n') }
        info.tarballSha256?.let { append("tarball_sha256=").append(it).append('\n') }
        append("installed_at_ms=").append(info.installedAtMs).append('\n')
        info.lastError?.let { append("last_error=").append(it.replace('\n', ' ')).append('\n') }
    }

    fun decode(text: String): LinuxInstallInfo {
        var info = LinuxInstallInfo()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || !line.contains('=')) continue
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            info = when (key) {
                "state" -> info.copy(
                    state = runCatching { LinuxInstallState.valueOf(value) }
                        .getOrDefault(LinuxInstallState.NOT_INSTALLED)
                )
                "stage" -> info.copy(stage = value.ifBlank { null })
                "rootfs_label" -> info.copy(rootfsLabel = value.ifBlank { null })
                "ubuntu_version" -> info.copy(ubuntuVersion = value.ifBlank { null })
                "tarball_sha256" -> info.copy(tarballSha256 = value.ifBlank { null })
                "installed_at_ms" -> info.copy(installedAtMs = value.toLongOrNull() ?: 0L)
                "last_error" -> info.copy(lastError = value.ifBlank { null })
                else -> info
            }
        }
        return info
    }
}
