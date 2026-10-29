package com.example.nova.linux

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Outcome of one guest command execution. */
data class LinuxRunResult(
    /** Real process exit code; null when the process was killed (timeout / stop). */
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    /** Working directory the guest shell reported after the command. */
    val cwdAfter: String? = null
) {
    val success: Boolean get() = exitCode == 0
}

/** Real device/runtime checks shown on the setup screen before installing. */
data class LinuxDeviceSupport(
    val deviceAbi: String,
    val abiSupported: Boolean,
    val runtimeBinariesPresent: Boolean,
    val freeBytes: Long,
    val enoughSpace: Boolean
) {
    val ok: Boolean get() = abiSupported && runtimeBinariesPresent && enoughSpace
}

/**
 * Owns the Linux environment: install state machine, the rootfs on disk,
 * and the one-shot PRoot command engine.
 *
 * Execution design: every guest command is a fresh PRoot process
 * (process-per-command). The guest shell state that matters — files,
 * installed packages, working directory — persists via the rootfs and
 * the cwd marker protocol (see LinuxCommands). There is no daemon and
 * no fake "server": READY means "installed and self-tested", and a
 * command runs when one is actually submitted.
 */
class LinuxManager(
    context: Context,
    workspaceDir: File
) {
    private val appContext = context.applicationContext
    val paths = LinuxPaths(
        filesDir = appContext.filesDir,
        nativeLibDir = appContext.applicationInfo.nativeLibraryDir,
        workspaceDir = workspaceDir
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()

    private val _installInfo = MutableStateFlow(loadInitialInfo())
    val installInfo: StateFlow<LinuxInstallInfo> = _installInfo.asStateFlow()

    private val _installLog = MutableStateFlow<List<String>>(emptyList())
    val installLog: StateFlow<List<String>> = _installLog.asStateFlow()

    private val _toolStatuses = MutableStateFlow<Map<String, LinuxToolStatus>>(emptyMap())
    val toolStatuses: StateFlow<Map<String, LinuxToolStatus>> = _toolStatuses.asStateFlow()

    /** Interactive terminal session bound to this manager. */
    val terminal: LinuxTerminalSession = LinuxTerminalSession(this, scope)

    @Volatile
    private var activeProcess: Process? = null

    @Volatile
    private var stopRequested: Boolean = false

    @Volatile
    private var installActive: Boolean = false

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    fun isReady(): Boolean = _installInfo.value.state == LinuxInstallState.READY

    private fun loadInitialInfo(): LinuxInstallInfo {
        val decoded = runCatching {
            if (paths.stateFile.isFile) {
                LinuxStateCodec.decode(paths.stateFile.readText())
            } else {
                LinuxInstallInfo()
            }
        }.getOrDefault(LinuxInstallInfo())
        // A persisted INSTALLING at process start means the previous
        // process died mid-install: surface it honestly as an error with
        // a repair path instead of pretending the install is running.
        if (decoded.state == LinuxInstallState.INSTALLING) {
            return decoded.copy(
                state = LinuxInstallState.ERROR,
                stage = null,
                lastError = "Installation was interrupted before it finished. " +
                    "Use Repair (re-configure + self-test) or Reset and install again."
            )
        }
        // Cross-check the persisted marker against the rootfs itself:
        // READY without a guest bash is not ready, and an extracted
        // rootfs without a READY marker is an interrupted/unknown state,
        // never a silently trusted install.
        if (decoded.state == LinuxInstallState.READY && !paths.guestBashPresent()) {
            return decoded.copy(
                state = LinuxInstallState.ERROR,
                stage = null,
                lastError = "The install is marked ready but the guest bash is missing. " +
                    "Use Reset and install again."
            )
        }
        if (decoded.state == LinuxInstallState.NOT_INSTALLED && paths.guestBashPresent()) {
            return decoded.copy(
                state = LinuxInstallState.ERROR,
                stage = null,
                lastError = "An extracted rootfs was found without a completed install marker. " +
                    "Use Repair (re-configure + self-test) or Reset and install again."
            )
        }
        return decoded
    }

    private fun persist(info: LinuxInstallInfo) {
        _installInfo.value = info
        runCatching {
            paths.stateFile.parentFile?.mkdirs()
            val tmp = File(paths.stateFile.parentFile, "state.properties.tmp")
            tmp.writeText(LinuxStateCodec.encode(info))
            tmp.renameTo(paths.stateFile)
        }
    }

    private fun transition(to: LinuxInstallState, mutate: (LinuxInstallInfo) -> LinuxInstallInfo = { it }) {
        val next = LinuxStateMachine.transition(_installInfo.value, to)
        persist(mutate(next))
    }

    // ------------------------------------------------------------------
    // Device support
    // ------------------------------------------------------------------

    fun deviceSupport(): LinuxDeviceSupport {
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val free = runCatching { StatFs(paths.linuxDir.parentFile?.path ?: "/data").availableBytes }
            .getOrDefault(0L)
        return LinuxDeviceSupport(
            deviceAbi = abis.firstOrNull() ?: "unknown",
            abiSupported = LinuxConfig.REQUIRED_ABI in abis,
            runtimeBinariesPresent = paths.runtimeBinariesPresent(),
            freeBytes = free,
            enoughSpace = free >= LinuxConfig.MIN_FREE_BYTES
        )
    }

    // ------------------------------------------------------------------
    // Install / repair / reset
    // ------------------------------------------------------------------

    private fun logLine(line: String) {
        _installLog.value = (_installLog.value + line).takeLast(400)
        runCatching {
            paths.installLogFile.parentFile?.mkdirs()
            paths.installLogFile.appendText(line + "\n")
        }
    }

    private fun stage(name: String) {
        persist(_installInfo.value.copy(stage = name))
        logLine("== $name ==")
    }

    /**
     * Full install pipeline. Every stage does the real work and logs it;
     * READY is reached only after the PRoot self-test actually passes.
     * Safe to call again after ERROR (repairing) or READY (reinstall).
     */
    suspend fun install() {
        if (installActive) return
        installActive = true
        withContext(Dispatchers.IO) {
            try {
                _installLog.value = emptyList()
                runCatching { paths.installLogFile.delete() }
                transition(LinuxInstallState.INSTALLING) { it.copy(lastError = null, stage = null) }

                stage("Checking device")
                val support = deviceSupport()
                logLine("Device ABI: ${support.deviceAbi} (build ships ${LinuxConfig.REQUIRED_ABI})")
                if (!support.abiSupported) {
                    fail("Unsupported device architecture: ${support.deviceAbi}. " +
                        "This build of Spark ships the Linux runtime for ${LinuxConfig.REQUIRED_ABI} only.")
                }
                if (!support.runtimeBinariesPresent) {
                    fail("Runtime binaries are missing from the installed APK " +
                        "(expected ${LinuxConfig.PROOT_LIB}, ${LinuxConfig.PROOT_LOADER_LIB}, " +
                        "${LinuxConfig.TALLOC_LIB}, ${LinuxConfig.SHMEM_LIB} in the native library directory).")
                }
                logLine("Free storage: ${support.freeBytes / 1_000_000} MB")
                if (!support.enoughSpace) {
                    fail("Not enough free storage: need at least ${LinuxConfig.MIN_FREE_BYTES / 1_000_000} MB free.")
                }
                paths.workspaceBindProblem()?.let { fail("Workspace bind problem: $it") }

                stage("Downloading Ubuntu rootfs")
                val expectedSha = downloadRootfs()

                stage("Extracting rootfs")
                extractRootfs()

                stage("Configuring environment")
                configureGuest()

                stage("Running self-test")
                val ubuntuVersion = runSelfTest()

                transition(LinuxInstallState.READY) {
                    it.copy(
                        stage = null,
                        rootfsLabel = LinuxConfig.ROOTFS_LABEL,
                        ubuntuVersion = ubuntuVersion,
                        tarballSha256 = expectedSha,
                        installedAtMs = System.currentTimeMillis(),
                        lastError = null
                    )
                }
                logLine("Setup complete — $ubuntuVersion is ready.")
            } catch (e: CancellationException) {
                markError("Installation was interrupted.")
                throw e
            } catch (e: LinuxInstallException) {
                markError(e.message ?: "Installation failed")
            } catch (e: Exception) {
                markError("Installation failed: ${e.message}")
            } finally {
                installActive = false
            }
        }
    }

    /** Re-runs configuration + the real self-test on an existing rootfs. */
    suspend fun repair() {
        if (installActive) return
        if (!paths.guestBashPresent()) {
            markError("Repair needs an extracted rootfs (guest bash not found). Run a full install instead.")
            return
        }
        installActive = true
        withContext(Dispatchers.IO) {
            try {
                transition(LinuxInstallState.INSTALLING) { it.copy(lastError = null, stage = null) }
                stage("Configuring environment")
                configureGuest()
                stage("Running self-test")
                val ubuntuVersion = runSelfTest()
                transition(LinuxInstallState.READY) {
                    it.copy(stage = null, ubuntuVersion = ubuntuVersion, lastError = null)
                }
                logLine("Repair complete — $ubuntuVersion is ready.")
            } catch (e: CancellationException) {
                markError("Repair was interrupted.")
                throw e
            } catch (e: LinuxInstallException) {
                markError(e.message ?: "Repair failed")
            } catch (e: Exception) {
                markError("Repair failed: ${e.message}")
            } finally {
                installActive = false
            }
        }
    }

    /** Deletes the entire Linux environment (rootfs, downloads, state). */
    suspend fun reset() {
        withContext(Dispatchers.IO) {
            stopCurrentCommand()
            installActive = false
            runCatching { paths.linuxDir.deleteRecursively() }
            paths.linuxDir.mkdirs()
            _installLog.value = emptyList()
            _toolStatuses.value = emptyMap()
            persist(LinuxInstallInfo(state = LinuxInstallState.NOT_INSTALLED))
        }
    }

    private class LinuxInstallException(message: String) : Exception(message)

    private fun fail(message: String): Nothing = throw LinuxInstallException(message)

    private fun markError(message: String) {
        logLine("ERROR: $message")
        val current = _installInfo.value
        val next = if (LinuxStateMachine.canTransition(current.state, LinuxInstallState.ERROR)) {
            current.copy(state = LinuxInstallState.ERROR, stage = null, lastError = message)
        } else {
            current.copy(stage = null, lastError = message)
        }
        persist(next)
    }

    /** Downloads SHA256SUMS + the rootfs tarball; verifies the hash. Returns the verified SHA-256. */
    private fun downloadRootfs(): String {
        paths.downloadsDir.mkdirs()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        logLine("Fetching ${LinuxConfig.SHA256SUMS_URL}")
        val sumsText = httpGetString(client, LinuxConfig.SHA256SUMS_URL)
            ?: fail("Could not download SHA256SUMS from Ubuntu's cdimage server. Check the internet connection.")
        val expected = LinuxConfig.parseSha256Sums(sumsText, LinuxConfig.ROOTFS_FILE_NAME)
            ?: fail("${LinuxConfig.ROOTFS_FILE_NAME} is not listed in the official SHA256SUMS — refusing to install an unverifiable rootfs.")
        logLine("Expected SHA-256 (official SHA256SUMS): $expected")

        // Reuse a previously downloaded tarball when it already verifies.
        if (paths.tarballFile.isFile && sha256OfFile(paths.tarballFile) == expected) {
            logLine("Existing download already verifies — skipping download.")
            return expected
        }

        logLine("Downloading ${LinuxConfig.ROOTFS_URL}")
        val request = Request.Builder().url(LinuxConfig.ROOTFS_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                fail("Download failed: HTTP ${response.code} from cdimage.ubuntu.com")
            }
            val body = response.body ?: fail("Download failed: empty response body")
            val total = body.contentLength()
            var written = 0L
            var lastLoggedPercent = -1
            body.byteStream().use { input ->
                paths.tarballPartFile.outputStream().buffered().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = (written * 100 / total).toInt()
                            if (percent >= lastLoggedPercent + 10) {
                                lastLoggedPercent = percent
                                logLine("Downloading… $percent% (${written / 1_000_000} MB)")
                            }
                        }
                    }
                }
            }
            logLine("Downloaded ${written / 1_000_000} MB")
        }
        paths.tarballPartFile.renameTo(paths.tarballFile)

        stage("Verifying checksum (SHA-256)")
        val actual = sha256OfFile(paths.tarballFile)
        logLine("Actual SHA-256: $actual")
        if (actual != expected) {
            paths.tarballFile.delete()
            fail("Checksum MISMATCH — the downloaded rootfs does not match Ubuntu's official SHA256SUMS. " +
                "The file was deleted. This can mean a corrupted download or a tampered mirror.")
        }
        logLine("Checksum verified.")
        return expected
    }

    private fun extractRootfs() {
        // A fresh extraction target: a partial rootfs from an interrupted
        // install is discarded here (this is also the Repair-by-reinstall path).
        if (paths.rootfsDir.exists()) {
            paths.rootfsDir.deleteRecursively()
        }
        paths.rootfsDir.mkdirs()
        var lastLoggedMb = -1L
        val stats = paths.tarballFile.inputStream().buffered().use { input ->
            TarExtractor.extractGzipTar(input, paths.rootfsDir) { bytes ->
                val mb = bytes / 10_000_000
                if (mb > lastLoggedMb) {
                    lastLoggedMb = mb
                    logLine("Extracting… ${bytes / 1_000_000} MB unpacked")
                }
            }
        }
        logLine(
            "Extracted ${stats.files} files, ${stats.directories} directories, " +
                "${stats.symlinks} symlinks, ${stats.hardlinks} hardlinks " +
                "(${stats.skipped} entries skipped: device nodes/FIFOs need root and are provided by the /dev bind)."
        )
        if (!paths.guestBashPresent()) {
            fail("Extraction finished but the guest has no bash — the rootfs looks incomplete.")
        }
    }

    private fun configureGuest() {
        val rootfs = paths.rootfsDir
        // Networking: the guest resolves DNS through its own resolver, so
        // give it the device's DNS servers (best effort) or public fallbacks.
        val dns = systemDnsServers()
        File(rootfs, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText(LinuxConfig.buildResolvConf(dns))
        }
        logLine("DNS configured: ${(dns.ifEmpty { LinuxConfig.FALLBACK_DNS }).joinToString(", ")}")
        File(rootfs, "etc/hosts").writeText(LinuxConfig.buildHosts())
        File(rootfs, "etc/hostname").writeText("spark\n")
        // apt's sandbox user cannot drop privileges under PRoot's fake
        // root; running the sandbox as root avoids hard failures.
        val aptConfDir = File(rootfs, "etc/apt/apt.conf.d")
        aptConfDir.mkdirs()
        File(aptConfDir, "99spark-proot").writeText("APT::Sandbox::User \"root\";\n")
        // The bind target for the workspace mount.
        File(rootfs, "workspace").mkdirs()
        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").mkdirs()
        logLine("Guest configured (resolv.conf, hosts, apt sandbox, /workspace bind target).")
    }

    /** Runs the real self-test through PRoot. Returns the guest Ubuntu version string. */
    private suspend fun runSelfTest(): String {
        val result = runGuest(
            command = "uname -m && pwd && ls / && head -n 2 /etc/os-release",
            timeoutMs = 120_000,
            cwd = LinuxConfig.GUEST_HOME
        ) { line, isError -> logLine(if (isError) "stderr: $line" else line) }
        if (result.exitCode != 0) {
            fail("Self-test failed (exit ${result.exitCode}). The environment is NOT marked ready. " +
                "Last output: ${result.stderr.takeLast(400).ifBlank { result.stdout.takeLast(400) }}")
        }
        if (!result.stdout.contains("aarch64")) {
            fail("Self-test failed: `uname -m` did not report aarch64. Output: ${result.stdout.take(300)}")
        }
        val version = LinuxConfig.parseOsReleasePrettyName(result.stdout) ?: "Ubuntu (version unparsed)"
        logLine("Self-test passed: uname/pwd/ls all ran inside the guest (exit 0).")
        return version
    }

    private fun systemDnsServers(): List<String> = runCatching {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        cm?.allNetworks.orEmpty()
            .flatMap { network -> cm?.getLinkProperties(network)?.dnsServers.orEmpty() }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    private fun httpGetString(client: OkHttpClient, url: String): String? = runCatching {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()

    private fun sha256OfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------
    // Command engine
    // ------------------------------------------------------------------

    /**
     * Runs [command] in the guest via PRoot, streaming output lines to
     * [onLine] (line, isStderr) as they arrive. One guest process at a
     * time (serialized) so concurrent callers can never corrupt dpkg or
     * each other's session state.
     */
    suspend fun runGuest(
        command: String,
        timeoutMs: Long = 120_000,
        cwd: String = LinuxConfig.GUEST_HOME,
        extraEnv: Map<String, String> = emptyMap(),
        onLine: (String, Boolean) -> Unit = { _, _ -> }
    ): LinuxRunResult = runMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!paths.guestBashPresent()) {
                return@withContext LinuxRunResult(
                    exitCode = null, stdout = "",
                    stderr = "Linux environment is not installed (no guest bash found). " +
                        "Open the Terminal screen and install the environment first."
                )
            }
            paths.prootTmpDir.mkdirs()
            val argv = LinuxCommands.buildProotArgv(paths, cwd)
            val builder = ProcessBuilder(argv)
            builder.directory(paths.linuxDir)
            val env = builder.environment()
            env.clear()
            env.putAll(LinuxCommands.buildProcessEnv(paths, cwd, command, extraEnv))

            val process = builder.start()
            activeProcess = process
            stopRequested = false
            // Guest stdin is an empty, immediately-closed pipe: no tool can
            // block waiting for interactive input; apt always runs with -y.
            runCatching { process.outputStream.close() }

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            var cwdAfter: String? = null
            val outThread = thread(name = "spark-linux-stdout") {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        val marker = LinuxCommands.parseCwdMarker(line)
                        if (marker != null) {
                            cwdAfter = marker
                        } else {
                            appendCapped(stdout, line)
                            onLine(line, false)
                        }
                    }
                }
            }
            val errThread = thread(name = "spark-linux-stderr") {
                runCatching {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        appendCapped(stderr, line)
                        onLine(line, true)
                    }
                }
            }

            var timedOut = false
            try {
                val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1_000)
                while (true) {
                    ensureActive()
                    if (process.waitFor(200, TimeUnit.MILLISECONDS)) break
                    if (System.currentTimeMillis() > deadline) {
                        timedOut = true
                        process.destroy()
                        if (!process.waitFor(3, TimeUnit.SECONDS)) {
                            process.destroyForcibly()
                            process.waitFor(3, TimeUnit.SECONDS)
                        }
                        break
                    }
                }
            } catch (e: CancellationException) {
                process.destroy()
                runCatching { process.waitFor(3, TimeUnit.SECONDS) }
                throw e
            } finally {
                outThread.join(2_000)
                errThread.join(2_000)
                if (activeProcess === process) activeProcess = null
            }

            val exited = runCatching { process.exitValue() }.getOrNull()
            LinuxRunResult(
                exitCode = if (timedOut) null else exited,
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                timedOut = timedOut,
                cancelled = stopRequested && !timedOut,
                cwdAfter = cwdAfter
            )
        }
    }

    private fun appendCapped(sb: StringBuilder, line: String) {
        if (sb.length < MAX_CAPTURED_CHARS) {
            sb.append(line).append('\n')
        }
    }

    /** Kills the currently running guest command, if any. */
    fun stopCurrentCommand() {
        stopRequested = true
        activeProcess?.let { process ->
            runCatching { process.destroy() }
        }
    }

    // ------------------------------------------------------------------
    // Tool manager
    // ------------------------------------------------------------------

    /** Probes every catalogued tool inside the guest and caches the results. */
    suspend fun refreshToolStatuses() {
        if (!isReady()) return
        val results = LinkedHashMap<String, LinuxToolStatus>()
        for (tool in LinuxToolCatalog.TOOLS) {
            results[tool.id] = probeTool(tool)
        }
        _toolStatuses.value = results
    }

    private suspend fun probeTool(tool: LinuxTool): LinuxToolStatus {
        val check = runGuest(tool.checkCommand, timeoutMs = 60_000, cwd = LinuxConfig.GUEST_HOME)
        if (check.exitCode != 0) {
            return LinuxToolStatus(toolId = tool.id, installed = false, version = null, checked = true)
        }
        val versionRun = runGuest(tool.versionCommand, timeoutMs = 60_000, cwd = LinuxConfig.GUEST_HOME)
        val versionLines = versionRun.stdout.lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }.take(2).toList()
        return LinuxToolStatus(
            toolId = tool.id,
            installed = true,
            version = versionLines.joinToString(" · ").ifBlank { null },
            checked = true
        )
    }

    /** Installs (or reinstalls) one catalogued tool with the real apt flow. */
    suspend fun installTool(
        tool: LinuxTool,
        onLine: (String, Boolean) -> Unit = { _, _ -> }
    ): LinuxRunResult {
        val command = LinuxCommands.buildAptInstallCommand(tool.packages)
        val result = runGuest(
            command = command,
            timeoutMs = 15 * 60_000,
            cwd = LinuxConfig.GUEST_HOME,
            extraEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
            onLine = onLine
        )
        // Whatever happened, the cached status for this tool is now stale.
        if (isReady()) {
            _toolStatuses.value = _toolStatuses.value + (tool.id to probeTool(tool))
        }
        return result
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    fun rootfsSizeBytes(): Long = LinuxPathSafety.directorySize(paths.rootfsDir)

    fun linuxDirSizeBytes(): Long = LinuxPathSafety.directorySize(paths.linuxDir)

    /** Plain-text status used by the linux_status agent tool and the UI. */
    fun statusReport(): String {
        val info = _installInfo.value
        val support = deviceSupport()
        val sb = StringBuilder()
        sb.append("Linux environment state: ").append(info.state.name).append('\n')
        when (info.state) {
            LinuxInstallState.READY -> {
                sb.append("Ubuntu: ").append(info.ubuntuVersion ?: info.rootfsLabel ?: "installed").append('\n')
                sb.append("Rootfs size: ").append(rootfsSizeBytes() / 1_000_000).append(" MB\n")
                sb.append("Guest home: ").append(LinuxConfig.GUEST_HOME)
                    .append(" · Workspace bind: app workspace is mounted at ")
                    .append(LinuxConfig.GUEST_WORKSPACE).append('\n')
                val statuses = _toolStatuses.value
                if (statuses.isEmpty()) {
                    sb.append("Tools: not probed yet this session.\n")
                } else {
                    sb.append("Tools (probed in the guest):\n")
                    for (tool in LinuxToolCatalog.TOOLS) {
                        val st = statuses[tool.id]
                        sb.append("- ").append(tool.displayName).append(": ")
                        when {
                            st == null || !st.checked -> sb.append("unknown")
                            st.installed -> sb.append("installed").append(
                                st.version?.let { " ($it)" } ?: ""
                            )
                            else -> sb.append("not installed")
                        }
                        sb.append('\n')
                    }
                }
            }
            LinuxInstallState.NOT_INSTALLED ->
                sb.append("Not installed. Open the Terminal screen in Spark and tap Install Environment.\n")
            LinuxInstallState.INSTALLING ->
                sb.append("Installing (stage: ").append(info.stage ?: "starting").append(")…\n")
            LinuxInstallState.ERROR ->
                sb.append("Error: ").append(info.lastError ?: "installation failed").append('\n')
        }
        sb.append("Device ABI: ").append(support.deviceAbi)
            .append(" · Runtime binaries present: ").append(support.runtimeBinariesPresent)
        return sb.toString().trimEnd()
    }

    private companion object {
        const val MAX_CAPTURED_CHARS = 1_000_000
    }
}
