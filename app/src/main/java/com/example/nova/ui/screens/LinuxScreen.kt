package com.example.nova.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nova.data.NovaRepository
import com.example.nova.linux.LinuxConfig
import com.example.nova.linux.LinuxInstallState
import com.example.nova.linux.LinuxTool
import com.example.nova.linux.LinuxToolCatalog
import kotlinx.coroutines.launch

private enum class LinuxTab { TERMINAL, TOOLS, ABOUT }

/**
 * Linux Mode home: first-launch setup (real install pipeline with real
 * stage logs), the terminal, the tool installation manager, and an
 * honest About/limitations section. Which view shows is driven entirely
 * by the persisted install state in LinuxManager.
 */
@Composable
fun LinuxScreen(repository: NovaRepository) {
    val manager = remember { repository.linuxManager }
    val info by manager.installInfo.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Linux Environment",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black
        )
        when (info.state) {
            LinuxInstallState.NOT_INSTALLED -> LinuxSetupView(repository, showError = null)
            LinuxInstallState.ERROR -> LinuxSetupView(
                repository,
                showError = info.lastError ?: "Installation failed."
            )
            LinuxInstallState.INSTALLING -> LinuxInstallingView(repository)
            LinuxInstallState.READY -> LinuxReadyView(repository)
        }
    }
}

@Composable
private fun LinuxSetupView(repository: NovaRepository, showError: String?) {
    val manager = remember { repository.linuxManager }
    val scope = rememberCoroutineScope()
    val support = remember { manager.deviceSupport() }
    val installLog by manager.installLog.collectAsState()
    var showResetConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Real Ubuntu, inside Spark", fontWeight = FontWeight.Bold)
                Text(
                    "Spark can install a real Ubuntu ${"24.04 LTS"} userland that runs " +
                        "on this phone through PRoot (a user-space translator — no root, " +
                        "no virtual machine). You get a working bash shell with apt: " +
                        "install git, Python, Node.js and use them from the terminal " +
                        "and from the AI agent (with your approval).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Needs: an internet connection and about " +
                        "${LinuxConfig.ROOTFS_DOWNLOAD_BYTES_APPROX / 1_000_000} MB of downloads; " +
                        "the installed environment uses 100 MB+ of storage. The rootfs comes " +
                        "only from Ubuntu's official cdimage server and is verified against " +
                        "the official SHA256SUMS before anything is extracted.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Device checks", fontWeight = FontWeight.Bold)
                CheckLine("Architecture: ${support.deviceAbi}", support.abiSupported)
                CheckLine("Runtime binaries in APK (PRoot)", support.runtimeBinariesPresent)
                CheckLine(
                    "Free storage: ${support.freeBytes / 1_000_000} MB " +
                        "(need ${LinuxConfig.MIN_FREE_BYTES / 1_000_000} MB)",
                    support.enoughSpace
                )
            }
        }

        if (showError != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Installation problem", fontWeight = FontWeight.Bold)
                    Text(showError, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Button(
            onClick = { scope.launch { manager.install() } },
            enabled = support.ok,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (showError == null) "Install Environment" else "Retry Install")
        }
        if (showError != null && manager.paths.guestBashPresent()) {
            OutlinedButton(
                onClick = { scope.launch { manager.repair() } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Repair (re-configure + self-test)")
            }
        }
        if (showError != null) {
            OutlinedButton(
                onClick = { showResetConfirm = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset (delete the environment)")
            }
        }
        if (!support.ok) {
            Text(
                "This device cannot install the environment right now — see the checks above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (installLog.isNotEmpty()) {
            Text("Installation log", fontWeight = FontWeight.Bold)
            LogBox(lines = installLog, heightDp = 220)
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset Linux environment?") },
            text = { Text("The Ubuntu rootfs, downloaded files and installed packages will be deleted. Your workspace files are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    scope.launch { manager.reset() }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LinuxInstallingView(repository: NovaRepository) {
    val manager = remember { repository.linuxManager }
    val info by manager.installInfo.collectAsState()
    val installLog by manager.installLog.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Column {
                Text("Installing…", fontWeight = FontWeight.Bold)
                Text(
                    info.stage ?: "Starting",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Text(
            "Every stage does the real work — download, SHA-256 verification, extraction, " +
                "and a self-test that runs uname, pwd and ls inside the guest. The environment " +
                "is only marked ready when the self-test actually passes.",
            style = MaterialTheme.typography.bodySmall
        )
        LogBox(lines = installLog, heightDp = 380)
    }
}

@Composable
private fun ColumnScope.LinuxReadyView(repository: NovaRepository) {
    val manager = remember { repository.linuxManager }
    val info by manager.installInfo.collectAsState()
    var tab by remember { mutableStateOf(LinuxTab.TERMINAL) }
    var rootfsMb by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(info.state) {
        rootfsMb = manager.rootfsSizeBytes() / 1_000_000
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                info.ubuntuVersion ?: info.rootfsLabel ?: "Ubuntu",
                fontWeight = FontWeight.Bold
            )
            Text(
                "State: READY · Rootfs: ${rootfsMb?.toString() ?: "…"} MB · " +
                    "Your app workspace is mounted in the guest at /workspace",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = tab == LinuxTab.TERMINAL,
            onClick = { tab = LinuxTab.TERMINAL },
            label = { Text("Terminal") }
        )
        FilterChip(
            selected = tab == LinuxTab.TOOLS,
            onClick = { tab = LinuxTab.TOOLS },
            label = { Text("Tools") }
        )
        FilterChip(
            selected = tab == LinuxTab.ABOUT,
            onClick = { tab = LinuxTab.ABOUT },
            label = { Text("About") }
        )
    }

    when (tab) {
        LinuxTab.TERMINAL -> LinuxTerminalTab(repository)
        LinuxTab.TOOLS -> LinuxToolsTab(repository)
        LinuxTab.ABOUT -> LinuxAboutTab(repository)
    }
}

@Composable
private fun ColumnScope.LinuxTerminalTab(repository: NovaRepository) {
    val manager = remember { repository.linuxManager }
    val session = remember { manager.terminal }
    val lines by session.lines.collectAsState()
    val cwd by session.cwd.collectAsState()
    val isRunning by session.isRunning.collectAsState()
    val lastExit by session.lastExitCode.collectAsState()
    var input by remember { mutableStateOf("") }
    var historyIndex by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "cwd: $cwd",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = when {
                isRunning -> "running…"
                lastExit != null -> "last exit: $lastExit"
                else -> "idle"
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = { session.clear() }, enabled = !isRunning) {
            Icon(Icons.Default.Delete, contentDescription = "Clear terminal")
        }
        IconButton(onClick = { session.restart() }) {
            Icon(Icons.Default.Refresh, contentDescription = "Restart session")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(lines) { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("e.g. ls /workspace", fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        )
        IconButton(
            onClick = {
                val history = session.history.value
                if (history.isNotEmpty()) {
                    historyIndex = if (historyIndex < 0) history.size - 1
                    else maxOf(0, historyIndex - 1)
                    input = history[historyIndex]
                }
            },
            enabled = !isRunning
        ) {
            Text("↑", fontWeight = FontWeight.Bold)
        }
        if (isRunning) {
            Button(onClick = { session.stop() }) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.padding(2.dp))
                Text("Stop")
            }
        } else {
            Button(
                onClick = {
                    session.submit(input)
                    input = ""
                    historyIndex = -1
                },
                enabled = input.isNotBlank()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.padding(2.dp))
                Text("Run")
            }
        }
    }
    Text(
        "Commands run for real inside the Ubuntu guest. Interactive programs " +
            "(editors, REPLs without a script) are not supported — input is closed; " +
            "use non-interactive commands and flags like -y.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ColumnScope.LinuxToolsTab(repository: NovaRepository) {
    val manager = remember { repository.linuxManager }
    val scope = rememberCoroutineScope()
    val statuses by manager.toolStatuses.collectAsState()
    var installingToolId by remember { mutableStateOf<String?>(null) }
    var toolLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var probing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Tool Installation Manager",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = {
                    probing = true
                    scope.launch {
                        manager.refreshToolStatuses()
                        probing = false
                    }
                },
                enabled = !probing && installingToolId == null
            ) {
                Text(if (probing) "Checking…" else "Check status")
            }
        }
        Text(
            "Status comes from actually running the tool's version check inside the guest — " +
                "nothing is marked installed without a successful probe. Installs run the real " +
                "apt-get update + install and stream the output below.",
            style = MaterialTheme.typography.bodySmall
        )

        for (tool in LinuxToolCatalog.TOOLS) {
            ToolCard(
                tool = tool,
                statusText = statusTextFor(tool, statuses[tool.id]?.installed, statuses[tool.id]?.version, statuses[tool.id]?.checked == true),
                installEnabled = installingToolId == null && !probing,
                isInstalling = installingToolId == tool.id,
                onInstall = {
                    installingToolId = tool.id
                    toolLog = listOf("Installing ${tool.displayName} (${tool.packages.joinToString(" ")})…")
                    scope.launch {
                        val result = manager.installTool(tool) { line, _ ->
                            toolLog = (toolLog + line).takeLast(200)
                        }
                        toolLog = toolLog + if (result.exitCode == 0) {
                            "Done — ${tool.displayName} installed and re-checked."
                        } else {
                            "Install finished with exit ${result.exitCode ?: "killed"} — see the log above."
                        }
                        installingToolId = null
                    }
                }
            )
        }

        if (toolLog.isNotEmpty()) {
            Text("Install output", fontWeight = FontWeight.Bold)
            LogBox(lines = toolLog, heightDp = 240)
        }
    }
}

private fun statusTextFor(tool: LinuxTool, installed: Boolean?, version: String?, checked: Boolean): String =
    when {
        !checked -> "Not checked yet"
        installed == true -> "Installed" + (version?.let { " — $it" } ?: "")
        else -> "Not installed (installs: ${tool.packages.joinToString(" ")})"
    }

@Composable
private fun ToolCard(
    tool: LinuxTool,
    statusText: String,
    installEnabled: Boolean,
    isInstalling: Boolean,
    onInstall: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tool.displayName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (isInstalling) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                }
            }
            Text(tool.description, style = MaterialTheme.typography.bodySmall)
            Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onInstall, enabled = installEnabled) {
                Text(if (statusText.startsWith("Installed")) "Reinstall" else "Install")
            }
        }
    }
}

@Composable
private fun ColumnScope.LinuxAboutTab(repository: NovaRepository) {
    val manager = remember { repository.linuxManager }
    val scope = rememberCoroutineScope()
    var showResetConfirm by remember { mutableStateOf(false) }
    var totalMb by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        totalMb = manager.linuxDirSizeBytes() / 1_000_000
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What this is — and what it is not", fontWeight = FontWeight.Bold)
                Text(
                    "The guest is a real Ubuntu 24.04 userland executed through PRoot. " +
                        "PRoot is a ptrace-based translator: it is NOT a virtual machine and " +
                        "NOT a security sandbox. Guest processes run as this app's own user " +
                        "with fake root inside the guest only.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Honest limitations: performance is lower than native (every system call " +
                        "is translated); some packages or binaries may not work under PRoot " +
                        "(kernel modules, systemd services, FUSE and real device access are out); " +
                        "there is no root on the host and no access to other apps' data. " +
                        "The guest environment never receives Spark's API keys or vault secrets.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "The AI agent can run commands here through the linux_run / linux_install_tool " +
                        "tools. Read-only inspection commands run directly; installs and anything " +
                        "that can change or delete data always ask for your approval first.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Licenses: PRoot is GPL-2.0 (binaries built from the official Termux " +
                        "packages: proot, libtalloc, libandroid-shmem). The Ubuntu rootfs is " +
                        "Ubuntu Base 24.04 LTS from cdimage.ubuntu.com, SHA-256 verified at " +
                        "install time; its packages keep their own open-source licenses.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Storage", fontWeight = FontWeight.Bold)
                Text(
                    "Linux environment on disk: ${totalMb?.toString() ?: "…"} MB " +
                        "(rootfs + downloaded tarball + logs). The rootfs persists across " +
                        "app restarts — installed packages stay installed.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { manager.install() } }) {
                        Text("Reinstall")
                    }
                    OutlinedButton(onClick = { showResetConfirm = true }) {
                        Text("Reset environment")
                    }
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset Linux environment?") },
            text = { Text("The Ubuntu rootfs and every package installed inside it will be deleted. Your workspace files are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    scope.launch { manager.reset() }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CheckLine(text: String, ok: Boolean) {
    Text(
        text = (if (ok) "✓ " else "✗ ") + text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
    )
}

@Composable
private fun LogBox(lines: List<String>, heightDp: Int) {
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Column(modifier = Modifier.verticalScroll(scroll)) {
            for (line in lines) {
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}
