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
        assertEquals(6, WorkspaceManager.ENVIRONMENT_FILES.size)
        assertTrue(WorkspaceManager.ENVIRONMENT_PACK_BYTES > 0)
        assertTrue(WorkspaceManager.environmentPackSizeLabel().endsWith("MB"))
    }
}
