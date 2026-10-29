package com.example

import com.example.nova.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Workspace seeding must create the standard structure on first launch and
 * must NEVER overwrite files the user has edited (idempotency).
 */
class WorkspaceSeedingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `seeding creates folders and starter files`() {
        val root = tmp.newFolder("ws")
        val wm = WorkspaceManager(root)
        wm.initializeDefaultStructure()

        for (folder in WorkspaceManager.STANDARD_FOLDERS) {
            assertTrue("missing folder $folder", File(root, folder).isDirectory)
        }
        assertTrue(File(root, "README.md").isFile)
        assertTrue(File(root, "Memory/Memory.md").isFile)
        assertTrue(File(root, "Memory/Training.md").isFile)
        assertTrue(File(root, "AI-Tasks/index.md").isFile)
        assertFalse(wm.isEnvironmentInstalled())
    }

    @Test
    fun `seeding never overwrites user edits`() {
        val root = tmp.newFolder("ws2")
        val wm = WorkspaceManager(root)
        wm.initializeDefaultStructure()

        File(root, "Memory/Memory.md").writeText("MY EDITED MEMORY")
        File(root, "README.md").writeText("MY README")

        wm.initializeDefaultStructure()

        assertEquals("MY EDITED MEMORY", File(root, "Memory/Memory.md").readText())
        assertEquals("MY README", File(root, "README.md").readText())
    }

    @Test
    fun `environment pack metadata is consistent`() {
        // Pack v2: 14 SKILL.md files — the 6 converted pack docs,
        // phone-agent-playbook and research-with-sources (Phase 3),
        // browser-mastery, browser-cursor-control and keyboard-and-typing
        // (browser fix round), facebook-post-links (Trained Task #1),
        // screenshots-collection (Trained Task #2) and linux-terminal
        // (Linux Mode).
        assertEquals(14, WorkspaceManager.ENVIRONMENT_FILES.size)
        assertTrue(WorkspaceManager.ENVIRONMENT_PACK_BYTES > 0)
        assertTrue(WorkspaceManager.environmentPackSizeLabel().endsWith("MB"))
        for (path in WorkspaceManager.ENVIRONMENT_FILES) {
            assertTrue("pack file must be a skill: $path", path.startsWith("skills/") && path.endsWith("/SKILL.md"))
        }
    }

    @Test
    fun `environment pack installs skills and never overwrites edits`() {
        val root = tmp.newFolder("ws3")
        val wm = WorkspaceManager(root)
        wm.initializeDefaultStructure()

        val installed = wm.installEnvironmentPack()
        assertEquals(WorkspaceManager.ENVIRONMENT_FILES.size, installed)
        assertTrue(wm.isEnvironmentInstalled())
        for (path in WorkspaceManager.ENVIRONMENT_FILES) {
            val f = File(root, path)
            assertTrue("missing installed skill $path", f.isFile)
            assertTrue("skill $path must carry front matter", f.readText().startsWith("---\nname: "))
        }

        // A user edit to an installed skill must survive a reinstall.
        val skill = File(root, "skills/agent-core/SKILL.md")
        skill.writeText("MY EDITED SKILL")
        val reinstalled = wm.installEnvironmentPack()
        assertEquals(0, reinstalled)
        assertEquals("MY EDITED SKILL", skill.readText())
    }
}
