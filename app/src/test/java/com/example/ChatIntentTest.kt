package com.example

import com.example.nova.agent.ChatIntent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat/agent routing rule must stay predictable: greetings and
 * questions are conversation (no task created), clear action requests
 * and links launch the agent.
 */
class ChatIntentTest {

    @Test
    fun `greetings and small talk are chat`() {
        assertFalse(ChatIntent.isActionRequest("kia Hall Hy"))
        assertFalse(ChatIntent.isActionRequest("hello"))
        assertFalse(ChatIntent.isActionRequest("Hi there"))
        assertFalse(ChatIntent.isActionRequest("Thanks!"))
        assertFalse(ChatIntent.isActionRequest("ok"))
        assertFalse(ChatIntent.isActionRequest(""))
    }

    @Test
    fun `questions are chat even when they mention actions`() {
        assertFalse(ChatIntent.isActionRequest("What is the capital of Pakistan?"))
        assertFalse(ChatIntent.isActionRequest("How do I create a file?"))
        assertFalse(ChatIntent.isActionRequest("Kya tum meri madad kar sakte ho?"))
        assertFalse(ChatIntent.isActionRequest("why is the sky blue"))
    }

    @Test
    fun `clear action requests launch the agent`() {
        assertTrue(ChatIntent.isActionRequest("open Browser And Search Pakistan"))
        assertTrue(ChatIntent.isActionRequest("Search for the latest tech news"))
        assertTrue(ChatIntent.isActionRequest("go to wikipedia and find the moon landing"))
        assertTrue(ChatIntent.isActionRequest("Create a file called notes.txt"))
        assertTrue(ChatIntent.isActionRequest("Download the report and save it"))
        assertTrue(ChatIntent.isActionRequest("google kholo"))
    }

    @Test
    fun `urls and domains are action requests`() {
        assertTrue(ChatIntent.isActionRequest("open google.com"))
        assertTrue(ChatIntent.isActionRequest("https://news.ycombinator.com"))
        assertTrue(ChatIntent.isActionRequest("check example.com for me"))
    }
}
