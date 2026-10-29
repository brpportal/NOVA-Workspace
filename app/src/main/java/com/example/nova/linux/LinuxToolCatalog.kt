package com.example.nova.linux

/**
 * One installable tool in the Tool Installation Manager. All commands are
 * fixed strings from this catalog — the agent/user picks a tool, never a
 * raw package list — and [packages] are validated against Debian package
 * name syntax before any apt command is built.
 */
data class LinuxTool(
    val id: String,
    val displayName: String,
    val description: String,
    /** Debian packages installed for this tool. */
    val packages: List<String>,
    /** Exit code 0 of this command means "installed". */
    val checkCommand: String,
    /** Command whose first output line(s) report the installed version. */
    val versionCommand: String
)

/** Result of a real version probe inside the guest. */
data class LinuxToolStatus(
    val toolId: String,
    val installed: Boolean,
    /** Version text reported by the tool itself (null when not installed / unparseable). */
    val version: String? = null,
    /** True when a probe has actually run in this session. */
    val checked: Boolean = false
)

object LinuxToolCatalog {

    val TOOLS: List<LinuxTool> = listOf(
        LinuxTool(
            id = "git",
            displayName = "Git",
            description = "Version control — clone repositories, track code changes.",
            packages = listOf("git"),
            checkCommand = "command -v git",
            versionCommand = "git --version"
        ),
        LinuxTool(
            id = "python3",
            displayName = "Python 3",
            description = "Python 3 interpreter for scripts and automation.",
            packages = listOf("python3"),
            checkCommand = "command -v python3",
            versionCommand = "python3 --version"
        ),
        LinuxTool(
            id = "pip",
            displayName = "pip (Python packages)",
            description = "Package installer for Python (pip3). Needs Python 3.",
            packages = listOf("python3-pip"),
            checkCommand = "python3 -m pip --version",
            versionCommand = "python3 -m pip --version"
        ),
        LinuxTool(
            id = "nodejs",
            displayName = "Node.js + npm",
            description = "Node.js JavaScript runtime with the npm package manager.",
            packages = listOf("nodejs", "npm"),
            checkCommand = "command -v node",
            versionCommand = "node --version && npm --version"
        ),
        LinuxTool(
            id = "curl",
            displayName = "curl",
            description = "Transfer data from URLs — APIs, downloads, testing endpoints.",
            packages = listOf("curl"),
            checkCommand = "command -v curl",
            versionCommand = "curl --version | head -n 1"
        ),
        LinuxTool(
            id = "wget",
            displayName = "wget",
            description = "Non-interactive file downloader.",
            packages = listOf("wget"),
            checkCommand = "command -v wget",
            versionCommand = "wget --version | head -n 1"
        ),
        LinuxTool(
            id = "zip",
            displayName = "ZIP utilities",
            description = "zip and unzip for creating and extracting archives.",
            packages = listOf("zip", "unzip"),
            checkCommand = "command -v zip && command -v unzip",
            versionCommand = "unzip -v | head -n 1"
        )
    )

    private val BY_ID: Map<String, LinuxTool> = TOOLS.associateBy { it.id }

    fun byId(id: String): LinuxTool? = BY_ID[id.trim().lowercase()]

    fun ids(): List<String> = TOOLS.map { it.id }
}
