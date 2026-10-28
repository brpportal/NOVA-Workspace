package com.example

import com.example.nova.workspace.PathValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PathTraversalRejectionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceRoot: File

    @Before
    fun setup() {
        workspaceRoot = tempFolder.newFolder("workspace")
    }

    @Test
    fun testSafeRelativePathsAccepted() {
        assertTrue(PathValidator.isSafePath(workspaceRoot, "Projects/notes.txt"))
        assertTrue(PathValidator.isSafePath(workspaceRoot, "Documents/Reports/final.md"))
        assertTrue(PathValidator.isSafePath(workspaceRoot, "hello.json"))
    }

    @Test
    fun testDoubleDotPathTraversalRejected() {
        assertFalse(PathValidator.isSafePath(workspaceRoot, "../../../etc/passwd"))
        assertFalse(PathValidator.isSafePath(workspaceRoot, "Projects/../../secret.key"))
        assertFalse(PathValidator.isSafePath(workspaceRoot, "..\\..\\windows\\system32"))
    }

    @Test(expected = SecurityException::class)
    fun testResolveCanonicalFileThrowsOnEscape() {
        PathValidator.resolveCanonicalFile(workspaceRoot, "../outside.txt")
    }

    @Test(expected = SecurityException::class)
    fun testResolveCanonicalFileThrowsOnDeepTraversal() {
        PathValidator.resolveCanonicalFile(workspaceRoot, "sub/../../escape.txt")
    }
}
