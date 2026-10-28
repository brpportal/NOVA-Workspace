package com.example.nova.agent

/**
 * Decides whether a message typed in Chat is plain conversation or an
 * agent task request. Deliberately simple and predictable — no ML, no
 * hidden heuristics:
 *
 * 1. A message containing a URL or a bare domain (e.g. "open google.com")
 *    is an ACTION request.
 * 2. A message that looks like a question (ends with "?" or starts with a
 *    question word — what/why/how/kia/kya/kaise/...) is CHAT, even if it
 *    mentions an action ("How do I create a file?" is a question).
 * 3. Otherwise, a message containing an action verb (open, search, browse,
 *    go, find, download, create, write, save, send, fetch, ... including
 *    common Roman Urdu imperatives like kholo, banao, likho, bhejo) is an
 *    ACTION request.
 * 4. Everything else — greetings, thanks, discussion — is CHAT.
 *
 * CHAT messages are answered directly by the configured AI provider and
 * never create a task record. ACTION messages launch an agent task.
 */
object ChatIntent {

    private val DOMAIN_REGEX = Regex(
        """\b[a-z0-9]([a-z0-9-]*[a-z0-9])?\.(com|net|org|io|ai|co|pk|dev|app|me|info|xyz|site|online)\b"""
    )

    private val QUESTION_STARTERS = setOf(
        "what", "why", "how", "when", "where", "who", "whom", "which",
        "is", "are", "do", "does", "can", "could", "should", "would",
        "kia", "kya", "kyun", "kyu", "kab", "kahan", "kidhar",
        "kaun", "kon", "kis", "kitna", "kitne", "kaise"
    )

    private val ACTION_VERBS = setOf(
        "open", "search", "browse", "go", "goto", "visit", "navigate",
        "find", "download", "create", "write", "save", "delete",
        "fetch", "send", "summarize", "summarise", "research", "extract",
        "fill", "click", "book", "buy", "order", "play",
        // Roman Urdu imperatives
        "kholo", "khol", "talash", "dhoondo", "dhundo",
        "banao", "likho", "bhejo", "nikalo", "chalao"
    )

    fun isActionRequest(rawText: String): Boolean {
        val text = rawText.trim().lowercase()
        if (text.isEmpty()) return false

        // Rule 1: explicit link or domain -> the user wants the agent to act.
        if (text.contains("://") || DOMAIN_REGEX.containsMatchIn(text)) return true

        val tokens = text.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false

        // Rule 2: questions are conversation, not commands.
        val looksLikeQuestion = text.endsWith("?") || tokens.first() in QUESTION_STARTERS
        if (looksLikeQuestion) return false

        // Rule 3/4: action verb decides.
        return tokens.any { it in ACTION_VERBS }
    }
}
