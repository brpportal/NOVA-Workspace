package com.example.nova.linux

/**
 * Pure construction of PRoot invocations and the risk classifier the
 * approval policy delegates to. Everything here is JVM-testable.
 *
 * How a guest command runs (one-shot process design):
 * - argv is FIXED (see [buildProotArgv]) — the user's command text is
 *   never concatenated into it. The command travels in the SPARK_USER_CMD
 *   environment variable and the guest bash `eval`s it, exactly like a
 *   line typed in a terminal.
 * - The wrapper records the resulting working directory through a
 *   marker line (see [CWD_MARKER]) so `cd` persists across one-shot
 *   processes, and exits with the command's real exit code.
 * - The process environment is built from scratch ([buildProcessEnv]) —
 *   the app's own environment is never inherited, so no API key or other
 *   app secret can leak into the guest or its logs.
 */
object LinuxCommands {

    const val CWD_MARKER = "@@SPARK_CWD@@"

    /** Fixed guest-side wrapper executed by bash -c for every command. */
    const val WRAPPER_SCRIPT: String =
        "cd \"\${SPARK_CWD:-/root}\" 2>/dev/null || cd /root\n" +
            "eval \"\$SPARK_USER_CMD\"\n" +
            "__spark_rc=\$?\n" +
            "printf '\\n@@SPARK_CWD@@%s\\n' \"\$PWD\"\n" +
            "exit \$__spark_rc"

    const val GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Sanitizes a guest working directory: absolute, single-line, no NUL. */
    fun sanitizeGuestCwd(cwd: String?, default: String = LinuxConfig.GUEST_HOME): String {
        val c = cwd?.trim().orEmpty()
        return if (c.startsWith("/") && !c.contains('\n') && !c.contains('\u0000') && c.length <= 512) {
            c
        } else {
            default
        }
    }

    /**
     * Builds the fixed PRoot argv. The guest command is NOT part of argv
     * (it is passed via SPARK_USER_CMD in the environment).
     */
    fun buildProotArgv(paths: LinuxPaths, guestCwd: String): List<String> = listOf(
        paths.prootBinary.absolutePath,
        "-r", paths.rootfsDir.absolutePath,
        // Fake root (uid 0) so dpkg/apt work inside the guest.
        "-0",
        // Traced children must not outlive the proot process.
        "--kill-on-exit",
        "-b", "/dev",
        "-b", "/proc",
        "-b", "/sys",
        // The app workspace is the shared folder, visible as /workspace.
        "-b", "${paths.workspaceDir.absolutePath}:${LinuxConfig.GUEST_WORKSPACE}",
        "-w", sanitizeGuestCwd(guestCwd),
        "/bin/bash", "-c", WRAPPER_SCRIPT
    )

    /**
     * The COMPLETE environment for the proot process (the caller clears
     * the inherited environment first). Because proot passes its
     * environment to the guest, this is also the guest environment —
     * by construction it contains no app secrets.
     */
    fun buildProcessEnv(
        paths: LinuxPaths,
        guestCwd: String,
        userCommand: String,
        extraEnv: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val env = linkedMapOf(
            // How proot finds its loader + writable temp space.
            "PROOT_LOADER" to paths.prootLoader.absolutePath,
            "PROOT_TMP_DIR" to paths.prootTmpDir.absolutePath,
            // How the host linker finds libtalloc.so / libandroid-shmem.so
            // (both live in nativeLibraryDir next to libproot.so). Inside
            // the guest this path does not exist, so it is inert there.
            "LD_LIBRARY_PATH" to paths.nativeLibDir,
            // Guest session environment.
            "HOME" to LinuxConfig.GUEST_HOME,
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to "/bin/bash",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "PATH" to GUEST_PATH,
            "TMPDIR" to "/tmp",
            // The one-shot wrapper protocol.
            "SPARK_CWD" to sanitizeGuestCwd(guestCwd),
            "SPARK_USER_CMD" to userCommand
        )
        for ((key, value) in extraEnv) {
            // Extra keys (e.g. DEBIAN_FRONTEND) must be plain env names;
            // the protocol keys above can never be overridden.
            if (key.matches(Regex("[A-Z][A-Z0-9_]*")) && key !in env) {
                env[key] = value
            }
        }
        return env
    }

    /**
     * If [line] is the wrapper's working-directory marker, returns the
     * reported guest cwd; otherwise null. The marker line is consumed by
     * the runner and never shown to the user.
     */
    fun parseCwdMarker(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(CWD_MARKER)) return null
        val path = trimmed.removePrefix(CWD_MARKER).trim()
        return if (path.startsWith("/") && path.length <= 512) path else null
    }

    private val PACKAGE_NAME_REGEX = Regex("^[a-z0-9][a-z0-9.+-]*$")

    /** True for a syntactically valid Debian package name. */
    fun isValidPackageName(name: String): Boolean = PACKAGE_NAME_REGEX.matches(name)

    /**
     * Builds the apt install command for already-catalogued packages.
     * Throws on an invalid package name — catalog data must never smuggle
     * shell syntax into the guest.
     */
    fun buildAptInstallCommand(packages: List<String>): String {
        require(packages.isNotEmpty()) { "No packages to install" }
        for (pkg in packages) {
            require(isValidPackageName(pkg)) { "Invalid package name: $pkg" }
        }
        return "apt-get update && apt-get install -y " + packages.joinToString(" ")
    }
}

/** Risk verdict for one guest shell command (drives the approval gate). */
enum class LinuxCommandRisk { READ_ONLY, NEEDS_APPROVAL }

/**
 * Conservative classifier: a command is READ_ONLY only when EVERY shell
 * segment is a known inspection command and the text contains no
 * redirection, command substitution, or other write-capable construct.
 * Anything unrecognized needs approval — the safe default.
 */
object LinuxCommandClassifier {

    private val READ_ONLY_HEADS = setOf(
        "ls", "pwd", "whoami", "uname", "hostname", "id",
        "cat", "head", "tail", "grep", "egrep", "fgrep",
        "wc", "du", "df", "stat", "file", "which", "type",
        "true", "date", "nproc", "uptime", "find",
        // echo/printf write only to stdout here: any redirection (`>`)
        // already forced NEEDS_APPROVAL in the global pre-check above.
        "echo", "printf"
    )

    /** Tools whose `--version`-style probes are pure reads. */
    private val VERSION_PROBE_TOOLS = setOf(
        "git", "python3", "python", "pip", "pip3",
        "node", "nodejs", "npm", "curl", "wget",
        "zip", "unzip", "java", "apt", "apt-get", "dpkg", "bash"
    )

    private val VERSION_ARGS = setOf("--version", "-version", "version", "-V", "--help", "-h")

    private val SEGMENT_SPLIT = Regex("\\&\\&|\\|\\||[;|&\\n]")

    fun classify(command: String): LinuxCommandRisk {
        val cmd = command.trim()
        if (cmd.isEmpty()) return LinuxCommandRisk.NEEDS_APPROVAL
        // Redirection and substitution can hide writes anywhere.
        if (cmd.contains('>') || cmd.contains('`') || cmd.contains("$(") || cmd.contains("<(")) {
            return LinuxCommandRisk.NEEDS_APPROVAL
        }
        val segments = cmd.split(SEGMENT_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return LinuxCommandRisk.NEEDS_APPROVAL
        return if (segments.all { isReadOnlySegment(it) }) {
            LinuxCommandRisk.READ_ONLY
        } else {
            LinuxCommandRisk.NEEDS_APPROVAL
        }
    }

    private fun isReadOnlySegment(segment: String): Boolean {
        var tokens = segment.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .map { it.trim('"', '\'') }
        // Skip leading VAR=value assignments (e.g. `FOO=1 ls`).
        tokens = tokens.dropWhile { it.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*=.*")) }
        if (tokens.isEmpty()) return true // pure assignment changes nothing on disk
        val head = tokens[0].substringAfterLast('/')
        val args = tokens.drop(1)
        return when {
            // Changing directory only moves the session's cwd.
            head == "cd" -> true
            // `command -v tool` is the canonical availability probe.
            head == "command" -> args.firstOrNull() == "-v"
            // python3 -m pip --version style probes.
            (head == "python3" || head == "python") && args.size >= 2 &&
                args[0] == "-m" && (args[1] == "pip" || args[1] == "pip3") &&
                args.drop(2).all { it in VERSION_ARGS } -> true
            head in VERSION_PROBE_TOOLS && args.isNotEmpty() &&
                args.all { it in VERSION_ARGS } -> true
            head == "find" ->
                // find becomes a writer with -delete / -exec / -ok.
                args.none { it == "-delete" || it == "-exec" || it == "-execdir" || it == "-ok" || it == "-okdir" }
            head in READ_ONLY_HEADS -> true
            else -> false
        }
    }
}
