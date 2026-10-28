package com.example.nova.ai

/**
 * Repairs a chat-completions message history so it is valid for strict
 * OpenAI-compatible providers (OpenRouter routes to backends like Cohere that
 * reject malformed tool history with HTTP 400, e.g.
 * "invalid tool message at messages[3]: tool call id '...' not found in
 * previous tool calls").
 *
 * The validity rule enforced here: a `tool` message is only legal when its
 * `toolCallId` was declared by the tool_calls of the immediately preceding
 * assistant message (consecutive tool messages may all answer that one
 * assistant message). Anything else is an orphan and is dropped. Conversely,
 * tool calls the assistant declared but that were never answered (the loop
 * paused, failed, or the history was synthesized by compaction) are trimmed
 * from the assistant message, because strict providers also reject unanswered
 * tool calls; an assistant message left with no calls and no text is dropped.
 *
 * This function is pure and never invents ids: provider-issued tool_call ids
 * pass through verbatim.
 */
object ToolHistorySanitizer {

    data class Result(
        val messages: List<AIMessage>,
        /** Number of messages dropped or trimmed to make the history valid. */
        val repairs: Int
    )

    fun sanitize(messages: List<AIMessage>): Result {
        var repairs = 0
        val out = mutableListOf<AIMessage>()

        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            val calls = msg.toolCalls
            if (msg.role == "assistant" && !calls.isNullOrEmpty()) {
                // Collect the tool results that immediately follow.
                val results = mutableListOf<AIMessage>()
                var j = i + 1
                while (j < messages.size && messages[j].role == "tool") {
                    results.add(messages[j])
                    j++
                }
                val declaredIds = calls.map { it.id }
                val answeredIds = HashSet<String>()
                val keptResults = mutableListOf<AIMessage>()
                for (r in results) {
                    val id = r.toolCallId
                    if (id != null && id in declaredIds && answeredIds.add(id)) {
                        keptResults.add(r)
                    } else {
                        // Orphan result (id never declared), duplicate answer,
                        // or missing id — all invalid for strict providers.
                        repairs++
                    }
                }
                val keptCalls = calls.filter { it.id in answeredIds }
                if (keptCalls.size < calls.size) repairs++
                when {
                    keptCalls.isNotEmpty() -> {
                        out.add(
                            if (keptCalls.size == calls.size) msg
                            else msg.copy(toolCalls = keptCalls)
                        )
                        out.addAll(keptResults)
                    }
                    msg.content.isNotBlank() -> {
                        // No surviving calls, but the text is real content.
                        out.add(msg.copy(toolCalls = null))
                    }
                    else -> {
                        // Assistant turn existed only to carry tool calls that
                        // were never answered — dropping it keeps the history
                        // valid; its (empty) text carries no information.
                        repairs++
                    }
                }
                i = j
            } else if (msg.role == "tool") {
                // Tool message with no preceding assistant tool_calls block.
                repairs++
                i++
            } else {
                out.add(msg)
                i++
            }
        }
        return Result(out, repairs)
    }
}
