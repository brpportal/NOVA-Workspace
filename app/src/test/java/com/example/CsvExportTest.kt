package com.example

import com.example.nova.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CsvExportTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceManager: WorkspaceManager

    @Before
    fun setup() {
        val root = tempFolder.newFolder("workspace")
        workspaceManager = WorkspaceManager(root)
        workspaceManager.initializeDefaultStructure()
    }

    @Test
    fun testCsvGenerationAndCellEscaping() {
        val headers = listOf("Task ID", "Tool", "Status", "Note")
        val rows = listOf(
            listOf("1", "navigate", "SUCCESS", "Standard navigation"),
            listOf("2", "file_write", "SUCCESS", "Writing, with comma"),
            listOf("3", "click", "FAILED", "Quote \"inside\" note\nand newline")
        )

        val relPath = workspaceManager.exportCsv("TaskReport", headers, rows)
        assertEquals("Exports/TaskReport.csv", relPath)

        val exportedContent = workspaceManager.readFile(relPath)
        assertTrue(exportedContent.startsWith("Task ID,Tool,Status,Note"))
        assertTrue(exportedContent.contains("\"Writing, with comma\""))
        assertTrue(exportedContent.contains("\"Quote \"\"inside\"\" note\nand newline\""))
    }
}
