package com.example.nova.linux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The interactive terminal's session state.
 *
 * The engine underneath is process-per-command (see LinuxManager), so
 * this session is what makes it feel like a terminal: it keeps the
 * scrollback, the working directory (updated from the guest shell's own
 * $PWD after every command via the marker protocol), the command
 * history (persisted), and the live run status. Every line shown came
 * from a real process stream — nothing is simulated.
 */
class LinuxTerminalSession(
    private val manager: LinuxManager,
    private val scope: CoroutineScope
) {
    private val _lines = MutableStateFlow<List<String>>(listOf(BANNER))
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _cwd = MutableStateFlow(LinuxConfig.GUEST_HOME)
    val cwd: StateFlow<String> = _cwd.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _lastExitCode = MutableStateFlow<Int?>(null)
    val lastExitCode: StateFlow<Int?> = _lastExitCode.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(loadHistory())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    /** Submits one command line to the real guest shell. */
    fun submit(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty() || _isRunning.value) return
        appendLine("${_cwd.value} $ $cmd")
        recordHistory(cmd)
        _isRunning.value = true
        scope.launch {
            try {
                val result = manager.runGuest(
                    command = cmd,
                    timeoutMs = TERMINAL_TIMEOUT_MS,
                    cwd = _cwd.value
                ) { line, _ ->
                    appendLine(line)
                }
                result.cwdAfter?.let { _cwd.value = it }
                _lastExitCode.value = result.exitCode
                when {
                    result.timedOut ->
                        appendLine("[timed out after ${TERMINAL_TIMEOUT_MS / 1000}s — process killed]")
                    result.cancelled ->
                        appendLine("[stopped by user]")
                    result.exitCode != 0 && result.exitCode != null ->
                        appendLine("[exit ${result.exitCode}]")
                }
            } catch (e: Exception) {
                appendLine("[error: ${e.message}]")
            } finally {
                _isRunning.value = false
            }
        }
    }

    /** Stops the currently running command (kills the guest process). */
    fun stop() {
        manager.stopCurrentCommand()
    }

    /** Clears the scrollback (the guest itself is untouched). */
    fun clear() {
        _lines.value = listOf(BANNER)
    }

    /**
     * Restarts the session view: stops any running command and resets
     * the working directory to the guest home. Installed packages and
     * files persist — they live in the rootfs, not in this session.
     */
    fun restart() {
        manager.stopCurrentCommand()
        _cwd.value = LinuxConfig.GUEST_HOME
        _lastExitCode.value = null
        _lines.value = listOf(BANNER, "[session restarted — rootfs and installed tools are unchanged]")
    }

    private fun appendLine(line: String) {
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
    }

    private fun loadHistory(): List<String> = runCatching {
        val file: File = manager.paths.historyFile
        if (file.isFile) file.readLines().filter { it.isNotBlank() }.takeLast(MAX_HISTORY) else emptyList()
    }.getOrDefault(emptyList())

    private fun recordHistory(command: String) {
        _history.value = (_history.value + command).takeLast(MAX_HISTORY)
        runCatching {
            val file = manager.paths.historyFile
            file.parentFile?.mkdirs()
            file.writeText(_history.value.joinToString("\n") + "\n")
        }
    }

    companion object {
        const val BANNER =
            "Spark Linux — real Ubuntu guest via PRoot. Type a command and tap Run."
        const val MAX_LINES = 1500
        const val MAX_HISTORY = 200
        const val TERMINAL_TIMEOUT_MS = 30 * 60_000L
    }
}
