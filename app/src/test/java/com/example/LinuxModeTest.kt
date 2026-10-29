package com.example

import com.example.nova.agent.ToolDefinitions
import com.example.nova.linux.LinuxCommandClassifier
import com.example.nova.linux.LinuxCommandRisk
import com.example.nova.linux.LinuxCommands
import com.example.nova.linux.LinuxConfig
import com.example.nova.linux.LinuxInstallInfo
import com.example.nova.linux.LinuxInstallState
import com.example.nova.linux.LinuxPathSafety
import com.example.nova.linux.LinuxPaths
import com.example.nova.linux.LinuxStateCodec
import com.example.nova.linux.LinuxStateMachine
import com.example.nova.linux.LinuxToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Pure JVM coverage for Linux Mode's state, command and path contracts. */
class LinuxModeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `state codec round trips persisted install facts`() {
        val info = LinuxInstallInfo(
            state = LinuxInstallState.READY,
            stage = "Self-test",
            rootfsLabel = LinuxConfig.ROOTFS_LABEL,
            ubuntuVersion = "Ubuntu 24.04.5 LTS",
            tarballSha256 = "a".repeat(64),
            installedAtMs = 123456789L,
            lastError = null
        )

        assertEquals(info, LinuxStateCodec.decode(LinuxStateCodec.encode(info)))
    }

    @Test
    fun `state codec tolerates corrupt and unknown values`() {
        val decoded = LinuxStateCodec.decode(
            """
            state=NOT_A_REAL_STATE
            installed_at_ms=not-a-number
            unknown_key=ignored
            broken line without an equals sign
            ubuntu_version=Ubuntu 24.04 LTS
            """.trimIndent()
        )

        assertEquals(LinuxInstallState.NOT_INSTALLED, decoded.state)
        assertEquals(0L, decoded.installedAtMs)
        assertEquals("Ubuntu 24.04 LTS", decoded.ubuntuVersion)
        assertEquals(LinuxInstallInfo(), LinuxStateCodec.decode(""))
        assertEquals(LinuxInstallInfo(), LinuxStateCodec.decode("garbage"))
    }

    @Test
    fun `state machine permits the install lifecycle and reset`() {
        val legal = listOf(
            LinuxInstallState.NOT_INSTALLED to LinuxInstallState.INSTALLING,
            LinuxInstallState.INSTALLING to LinuxInstallState.READY,
            LinuxInstallState.INSTALLING to LinuxInstallState.ERROR,
            LinuxInstallState.ERROR to LinuxInstallState.INSTALLING,
            LinuxInstallState.READY to LinuxInstallState.INSTALLING,
            LinuxInstallState.READY to LinuxInstallState.ERROR
        )
        for ((from, to) in legal) {
            assertTrue("$from -> $to should be legal", LinuxStateMachine.canTransition(from, to))
        }
        for (from in LinuxInstallState.entries) {
            assertTrue("reset from $from should be legal", LinuxStateMachine.canTransition(from, LinuxInstallState.NOT_INSTALLED))
        }

        assertFalse(LinuxStateMachine.canTransition(LinuxInstallState.NOT_INSTALLED, LinuxInstallState.READY))
        assertFalse(LinuxStateMachine.canTransition(LinuxInstallState.ERROR, LinuxInstallState.READY))
        assertFalse(LinuxStateMachine.canTransition(LinuxInstallState.NOT_INSTALLED, LinuxInstallState.ERROR))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `state machine rejects skipping the self test`() {
        LinuxStateMachine.transition(LinuxInstallInfo(), LinuxInstallState.READY)
    }

    @Test
    fun `linux paths keep the rootfs outside the workspace`() {
        val filesDir = tmp.newFolder("files")
        val workspace = tmp.newFolder("workspace")
        val paths = LinuxPaths(filesDir, "/data/app/lib/arm64", workspace)

        assertEquals(File(filesDir, "linux"), paths.linuxDir)
        assertEquals(File(filesDir, "linux/rootfs"), paths.rootfsDir)
        assertEquals(File(filesDir, "linux/tmp"), paths.prootTmpDir)
        assertEquals(File(filesDir, "linux/state.properties"), paths.stateFile)
        assertFalse(paths.rootfsDir.path.startsWith(workspace.path + File.separator))
        assertNull(paths.workspaceBindProblem())

        assertEquals("libproot.so", paths.prootBinary.name)
        assertEquals("libproot-loader.so", paths.prootLoader.name)
        assertEquals("libtalloc.so", paths.tallocLib.name)
        assertEquals("libandroid-shmem.so", paths.shmemLib.name)
        assertFalse(paths.runtimeBinariesPresent())
    }

    @Test
    fun `workspace bind rejects rootfs overlap`() {
        val workspace = tmp.newFolder("workspace")
        val rootfsAsWorkspace = LinuxPaths(tmp.newFolder("files-a"), "/lib", workspace)
        assertNull(rootfsAsWorkspace.workspaceBindProblem())

        val sameFilesDir = tmp.newFolder("files-b")
        val sameDirectory = LinuxPaths(
            sameFilesDir,
            "/lib",
            File(sameFilesDir, "linux/rootfs")
        )
        sameDirectory.rootfsDir.mkdirs()
        assertNotNull(sameDirectory.workspaceBindProblem())
        assertTrue(sameDirectory.workspaceBindProblem()!!.contains("different directories"))

        val filesInsideWorkspace = File(workspace, "app-files")
        val nested = LinuxPaths(filesInsideWorkspace, "/lib", workspace)
        assertNotNull(nested.workspaceBindProblem())
        assertTrue(nested.workspaceBindProblem()!!.contains("inside the bind-mounted workspace"))
    }

    @Test
    fun `tar entry targets cannot escape the rootfs`() {
        val dest = tmp.newFolder("rootfs")

        assertEquals(
            File(dest, "usr/bin/bash").canonicalPath,
            LinuxPathSafety.entryTarget(dest, "usr/bin/bash")?.canonicalPath
        )
        assertEquals(
            File(dest, "usr/bin/bash").canonicalPath,
            LinuxPathSafety.entryTarget(dest, "./usr/bin/bash")?.canonicalPath
        )
        // Absolute tar names are defensively made relative to the rootfs.
        assertEquals(
            File(dest, "etc/passwd").canonicalPath,
            LinuxPathSafety.entryTarget(dest, "/etc/passwd")?.canonicalPath
        )

        assertNull(LinuxPathSafety.entryTarget(dest, "../escape"))
        assertNull(LinuxPathSafety.entryTarget(dest, "usr/../../escape"))
        assertNull(LinuxPathSafety.entryTarget(dest, ""))
        assertNull(LinuxPathSafety.entryTarget(dest, "."))
    }

    @Test
    fun `directory size sums real files only`() {
        val root = tmp.newFolder("sized")
        File(root, "a.bin").writeBytes(ByteArray(10))
        val nested = File(root, "nested").apply { mkdirs() }
        File(nested, "b.bin").writeBytes(ByteArray(15))

        assertEquals(25L, LinuxPathSafety.directorySize(root))
        assertEquals(0L, LinuxPathSafety.directorySize(File(root, "missing")))
    }

    @Test
    fun `proot argv is fixed and binds the workspace`() {
        val filesDir = tmp.newFolder("files")
        val workspace = tmp.newFolder("workspace")
        val paths = LinuxPaths(filesDir, "/data/app/lib/arm64", workspace)

        val argv = LinuxCommands.buildProotArgv(paths, "/workspace")

        assertEquals(paths.prootBinary.absolutePath, argv[0])
        assertEquals(
            listOf(
                "-r", paths.rootfsDir.absolutePath,
                "-0", "--kill-on-exit",
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "${workspace.absolutePath}:/workspace",
                "-w", "/workspace",
                "/bin/bash", "-c", LinuxCommands.WRAPPER_SCRIPT
            ),
            argv.drop(1)
        )
        assertTrue(argv.none { it == "rm" || it == "apt-get" })
    }

    @Test
    fun `process environment is built from scratch and protects protocol keys`() {
        val paths = LinuxPaths(tmp.newFolder("files"), "/data/app/lib/arm64", tmp.newFolder("workspace"))
        val env = LinuxCommands.buildProcessEnv(
            paths = paths,
            guestCwd = "/workspace",
            userCommand = "printf hello",
            extraEnv = mapOf(
                "DEBIAN_FRONTEND" to "noninteractive",
                "SPARK_USER_CMD" to "rm -rf /",
                "PROOT_LOADER" to "/tmp/fake-loader",
                "lowercase" to "ignored",
                "BAD-KEY" to "ignored"
            )
        )

        assertEquals(
            listOf(
                "PROOT_LOADER", "PROOT_TMP_DIR", "LD_LIBRARY_PATH",
                "HOME", "USER", "LOGNAME", "SHELL", "TERM", "LANG", "PATH",
                "TMPDIR", "SPARK_CWD", "SPARK_USER_CMD", "DEBIAN_FRONTEND"
            ),
            env.keys.toList()
        )
        assertEquals(paths.prootLoader.absolutePath, env["PROOT_LOADER"])
        assertEquals(paths.prootTmpDir.absolutePath, env["PROOT_TMP_DIR"])
        assertEquals("/data/app/lib/arm64", env["LD_LIBRARY_PATH"])
        assertEquals("/root", env["HOME"])
        assertEquals("/workspace", env["SPARK_CWD"])
        assertEquals("printf hello", env["SPARK_USER_CMD"])
        assertEquals("noninteractive", env["DEBIAN_FRONTEND"])
        assertFalse(env.keys.any { it.contains("API") || it.contains("SECRET") || it.contains("TOKEN") })
    }

    @Test
    fun `guest cwd sanitization and marker parsing are strict`() {
        assertEquals("/workspace", LinuxCommands.sanitizeGuestCwd("/workspace"))
        assertEquals("/root", LinuxCommands.sanitizeGuestCwd(null))
        assertEquals("/workspace", LinuxCommands.sanitizeGuestCwd("relative/path", "/workspace"))
        assertEquals("/workspace", LinuxCommands.sanitizeGuestCwd("/workspace\nrm", "/workspace"))
        assertEquals("/workspace", LinuxCommands.sanitizeGuestCwd("/" + "a".repeat(600), "/workspace"))

        assertEquals("/workspace", LinuxCommands.parseCwdMarker("@@SPARK_CWD@@/workspace"))
        assertEquals("/root", LinuxCommands.parseCwdMarker("  @@SPARK_CWD@@/root  "))
        assertNull(LinuxCommands.parseCwdMarker("ordinary output"))
        assertNull(LinuxCommands.parseCwdMarker("@@SPARK_CWD@@relative"))
        assertNull(LinuxCommands.parseCwdMarker("@@SPARK_CWD@@"))
    }

    @Test
    fun `classifier allows only inspection commands without approval`() {
        val readOnly = listOf(
            "ls -la",
            "git --version",
            "pwd && ls /",
            "cat /etc/hostname",
            "find . -name '*.py'",
            "python3 -m pip --version",
            "command -v node",
            "cd /workspace && ls",
            "cat /workspace/log.txt | grep error",
            "FOO=bar ls -la",
            "head -n 2 /etc/os-release",
            "apt-get --version",
            "echo hello"
        )
        for (command in readOnly) {
            assertEquals("$command should be read-only", LinuxCommandRisk.READ_ONLY, LinuxCommandClassifier.classify(command))
        }
    }

    @Test
    fun `classifier gates writes installs scripts and substitutions`() {
        val gated = listOf(
            "",
            "   ",
            "find . -delete",
            "find . -exec cat {} +",
            "rm -rf build",
            "apt-get install git",
            "echo hi > file.txt",
            "ls \$(pwd)",
            "ls `pwd`",
            "python3 script.py",
            "curl https://example.com/file",
            "ls && rm file",
            "chmod 755 script.sh",
            "mv old new",
            "git clone https://example.com/repo.git"
        )
        for (command in gated) {
            assertEquals("$command should need approval", LinuxCommandRisk.NEEDS_APPROVAL, LinuxCommandClassifier.classify(command))
        }
    }

    @Test
    fun `sha256 sums parser reads the official format`() {
        val expected = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
        val sums = """
            0000000000000000000000000000000000000000000000000000000000000000  ubuntu-base-other.tar.gz
            ${expected.uppercase()} *${LinuxConfig.ROOTFS_FILE_NAME}
            not-a-hash  ubuntu-base-broken.tar.gz
        """.trimIndent()

        assertEquals(expected, LinuxConfig.parseSha256Sums(sums, LinuxConfig.ROOTFS_FILE_NAME))
        assertNull(LinuxConfig.parseSha256Sums(sums, "missing.tar.gz"))
        assertNull(LinuxConfig.parseSha256Sums("", LinuxConfig.ROOTFS_FILE_NAME))
        assertTrue(LinuxConfig.ROOTFS_URL.startsWith("https://cdimage.ubuntu.com/"))
        assertEquals(LinuxConfig.ROOTFS_URL.replace(LinuxConfig.ROOTFS_FILE_NAME, "SHA256SUMS"), LinuxConfig.SHA256SUMS_URL)
    }

    @Test
    fun `guest config builders use supplied DNS or the fallback`() {
        assertEquals(
            "nameserver 8.8.8.8\nnameserver 1.1.1.1\n",
            LinuxConfig.buildResolvConf(emptyList())
        )
        assertEquals(
            "nameserver 192.168.1.1\nnameserver 9.9.9.9\n",
            LinuxConfig.buildResolvConf(listOf("192.168.1.1", "", "192.168.1.1", "9.9.9.9"))
        )
        assertTrue(LinuxConfig.buildHosts().contains("127.0.0.1 localhost"))
        assertEquals(
            "Ubuntu 24.04.5 LTS",
            LinuxConfig.parseOsReleasePrettyName("NAME=\"Ubuntu\"\nPRETTY_NAME=\"Ubuntu 24.04.5 LTS\"\n")
        )
        assertNull(LinuxConfig.parseOsReleasePrettyName("NAME=\"Ubuntu\"\n"))
    }

    @Test
    fun `tool catalog is complete unique and uses valid package names`() {
        assertEquals(
            listOf("git", "python3", "pip", "nodejs", "curl", "wget", "zip"),
            LinuxToolCatalog.ids()
        )
        assertEquals(LinuxToolCatalog.ids().size, LinuxToolCatalog.ids().distinct().size)
        for (tool in LinuxToolCatalog.TOOLS) {
            assertTrue(tool.packages.isNotEmpty())
            assertTrue(tool.packages.all { LinuxCommands.isValidPackageName(it) })
            assertTrue(tool.checkCommand.isNotBlank())
            assertTrue(tool.versionCommand.isNotBlank())
            assertEquals(tool, LinuxToolCatalog.byId(" ${tool.id.uppercase()} "))
        }
        assertNull(LinuxToolCatalog.byId("not-a-tool"))
        assertTrue(LinuxToolCatalog.byId("zip")!!.packages.contains("unzip"))
    }

    @Test
    fun `apt command builder validates package names`() {
        assertEquals(
            "apt-get update && apt-get install -y git python3-pip",
            LinuxCommands.buildAptInstallCommand(listOf("git", "python3-pip"))
        )
        assertTrue(LinuxCommands.isValidPackageName("libstdc++6"))
        assertFalse(LinuxCommands.isValidPackageName("Git"))
        assertFalse(LinuxCommands.isValidPackageName("git;rm"))
        assertFalse(LinuxCommands.isValidPackageName(""))

        try {
            LinuxCommands.buildAptInstallCommand(listOf("git; rm -rf /"))
            fail("an invalid package name must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Invalid package name"))
        }
        try {
            LinuxCommands.buildAptInstallCommand(emptyList())
            fail("an empty package list must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("No packages"))
        }
    }

    @Test
    fun `linux agent tools are registered with their required arguments`() {
        val linuxTools = ToolDefinitions.ALL_TOOLS.filter { it.name.startsWith("linux_") }
        assertEquals(setOf("linux_status", "linux_run", "linux_install_tool"), linuxTools.map { it.name }.toSet())

        val run = ToolDefinitions.ALL_TOOLS.single { it.name == "linux_run" }
        assertTrue(run.parametersSchemaJson.contains("\"required\": [\"command\"]"))
        val install = ToolDefinitions.ALL_TOOLS.single { it.name == "linux_install_tool" }
        assertTrue(install.parametersSchemaJson.contains("\"required\": [\"tool\"]"))
        for (id in LinuxToolCatalog.ids()) {
            assertTrue("install schema should list $id", install.parametersSchemaJson.contains("\"$id\""))
        }
    }
}
