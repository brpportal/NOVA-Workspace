package com.example

import com.example.nova.agent.LessonsStore
import com.example.nova.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The failure-learning loop: lessons are recorded on failure, deduplicated
 * by (tool + error class) with a seen counter, and read back for context
 * injection into the next task.
 */
class LessonsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): Pair<LessonsStore, File> {
        val root = tmp.newFolder()
        val wm = WorkspaceManager(root)
        wm.initializeDefaultStructure()
        return LessonsStore(wm) to root
    }

    @Test
    fun `records a structured lesson with a corrective rule`() {
        val (store, root) = newStore()
        store.recordLesson(
            goal = "google Per Search Kero Pakista",
            failedStep = "click_element (step 4)",
            toolName = "click_element",
            rawError = "Element 7 not found on this page. The page changed — call observe_page for fresh indices."
        )
        val file = File(root, LessonsStore.LESSONS_PATH)
        assertTrue(file.isFile)
        val text = file.readText()
        assertTrue(text.contains("Task goal: google Per Search Kero Pakista"))
        assertTrue(text.contains("Failed step: click_element (step 4)"))
        assertTrue(text.contains("Next time:"))
        assertTrue(text.contains("observe_page"))
        assertTrue(text.contains("Seen: 1 times"))
    }

    @Test
    fun `near-identical failures dedupe into a seen counter`() {
        val (store, root) = newStore()
        val err = "Element 3 not found on this page."
        store.recordLesson("task A", "click_element (step 2)", "click_element", err)
        store.recordLesson("task B", "click_element (step 5)", "click_element", err)
        store.recordLesson("task C", "click_element (step 1)", "click_element", err)
        val text = File(root, LessonsStore.LESSONS_PATH).readText()
        assertEquals(1, Regex("""## Lesson \[key:""").findAll(text).count())
        assertTrue(text.contains("Seen: 3 times"))
    }

    @Test
    fun `different error classes stay separate lessons`() {
        val (store, root) = newStore()
        store.recordLesson("t", "click (step 1)", "click", "Element 9 not found")
        store.recordLesson("t", "navigate (step 1)", "navigate", "API Error (429): quota exceeded")
        val text = File(root, LessonsStore.LESSONS_PATH).readText()
        assertEquals(2, Regex("""## Lesson \[key:""").findAll(text).count())
        assertTrue(text.contains("ELEMENT_NOT_FOUND"))
        assertTrue(text.contains("PROVIDER_LIMIT"))
    }

    @Test
    fun `recent lessons text is bounded and empty when no lessons exist`() {
        val (store, _) = newStore()
        assertEquals("", store.recentLessonsText())
        repeat(15) { i ->
            store.recordLesson("task $i", "tool$i (step 1)", "tool$i", "Something failed badly $i")
        }
        val recent = store.recentLessonsText(maxCount = 10, maxChars = 3200)
        assertTrue(recent.isNotBlank())
        assertTrue(recent.length <= 3200)
        assertFalse(recent.contains("task 0"))
        assertTrue(recent.contains("task 14"))
    }

    @Test
    fun `classify maps the cohere history failure and duplicate loop`() {
        val (store, _) = newStore()
        val (cls1, _) = store.classify("navigate", "invalid tool message at messages[3]: tool call id 'x' not found")
        assertEquals("TOOL_HISTORY", cls1)
        val (cls2, _) = store.classify("click", "Duplicate tool loop detected: identical action called 3 times")
        assertEquals("DUPLICATE_LOOP", cls2)
    }
}
