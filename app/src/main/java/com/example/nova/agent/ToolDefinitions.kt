package com.example.nova.agent

import com.example.nova.ai.AIToolDefinition

object ToolDefinitions {
    val ALL_TOOLS: List<AIToolDefinition> = listOf(
        // Browser Tools
        AIToolDefinition(
            name = "navigate",
            description = "Navigates the embedded browser to a specified HTTP or HTTPS URL.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "url": { "type": "string", "description": "The target URL to load, starting with https://" }
                },
                "required": ["url"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "get_current_url",
            description = "Returns the current verified URL of the active browser tab.",
            parametersSchemaJson = """{ "type": "object", "properties": {} }"""
        ),
        AIToolDefinition(
            name = "get_page_title",
            description = "Returns the title of the active browser webpage.",
            parametersSchemaJson = """{ "type": "object", "properties": {} }"""
        ),
        AIToolDefinition(
            name = "get_visible_text",
            description = "Extracts the visible textual content from the currently loaded webpage.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "max_chars": { "type": "integer", "description": "Maximum characters to extract (default 8000)" }
                }
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "find_element",
            description = "Checks if a CSS selector exists on the page and returns its bounding coordinates and text.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "selector": { "type": "string", "description": "CSS selector to search for" }
                },
                "required": ["selector"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "click",
            description = "Clicks an element identified by CSS selector in the browser.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "selector": { "type": "string", "description": "CSS selector to click" }
                },
                "required": ["selector"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "type",
            description = "Types text into a form input field identified by CSS selector.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "selector": { "type": "string", "description": "CSS selector of input" },
                    "text": { "type": "string", "description": "Text to enter" },
                    "clear_first": { "type": "boolean", "description": "Whether to clear existing text" }
                },
                "required": ["selector", "text"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "scroll",
            description = "Scrolls the browser viewport by horizontal (x) and vertical (y) pixel offsets.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "dx": { "type": "integer", "description": "Horizontal scroll offset" },
                    "dy": { "type": "integer", "description": "Vertical scroll offset" }
                },
                "required": ["dx", "dy"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "take_screenshot",
            description = "Captures the current browser webpage as a PNG image and saves it in workspace Images/.",
            parametersSchemaJson = """{ "type": "object", "properties": {} }"""
        ),
        AIToolDefinition(
            name = "get_page_state",
            description = "Returns current webpage summary including form counts, inputs, buttons, and readiness.",
            parametersSchemaJson = """{ "type": "object", "properties": {} }"""
        ),
        AIToolDefinition(
            name = "get_set_of_marks",
            description = "Overlays numbered visual labels on all clickable elements and returns element IDs, tags, text, and coordinates.",
            parametersSchemaJson = """{ "type": "object", "properties": {} }"""
        ),
        AIToolDefinition(
            name = "clear_set_of_marks",
            description = "Removes the numbered visual labels from the browser webpage.",
            parametersSchemaJson = """{ "type": "object", "properties": {} }"""
        ),
        AIToolDefinition(
            name = "task_record_artifact",
            description = "Saves an artifact (screenshot, extracted note, or file) to the active task folder in /AI-Tasks/.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "file_name": { "type": "string", "description": "Name of artifact file (e.g. data.json, note.md)" },
                    "content": { "type": "string", "description": "Text content to save" }
                },
                "required": ["file_name", "content"]
            }
            """.trimIndent()
        ),

        // File Tools
        AIToolDefinition(
            name = "file_list",
            description = "Lists files and subdirectories in the local workspace directory.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "directory": { "type": "string", "description": "Subdirectory path relative to workspace root" }
                }
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "file_create_directory",
            description = "Creates a new subdirectory inside the workspace.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "path": { "type": "string", "description": "Directory path relative to workspace root" }
                },
                "required": ["path"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "file_read",
            description = "Reads the UTF-8 text contents of a file inside the workspace.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "path": { "type": "string", "description": "Relative file path inside workspace" }
                },
                "required": ["path"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "file_write",
            description = "Writes UTF-8 text content to a file inside the workspace, creating it if needed.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "path": { "type": "string", "description": "Relative file path inside workspace" },
                    "content": { "type": "string", "description": "UTF-8 content to write" }
                },
                "required": ["path", "content"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "file_append",
            description = "Appends text content to an existing file inside the workspace.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "path": { "type": "string", "description": "Relative file path inside workspace" },
                    "content": { "type": "string", "description": "Content to append" }
                },
                "required": ["path", "content"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "file_search",
            description = "Searches for files matching a query substring inside the workspace.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "query": { "type": "string", "description": "Search term" },
                    "directory": { "type": "string", "description": "Optional subdirectory to start search from" }
                },
                "required": ["query"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "file_delete",
            description = "Deletes a file or directory inside the workspace.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "path": { "type": "string", "description": "Relative path to delete" }
                },
                "required": ["path"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "file_get_metadata",
            description = "Gets file metadata (size, last modified, lines, type) for a workspace file.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "path": { "type": "string", "description": "Relative file path" }
                },
                "required": ["path"]
            }
            """.trimIndent()
        ),

        // Memory Tools
        AIToolDefinition(
            name = "memory_save",
            description = "Saves an inspectable memory entry for long-term user context (never auto-saves browsing data).",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "key": { "type": "string", "description": "Short memory identifier" },
                    "content": { "type": "string", "description": "Memory content" },
                    "category": { "type": "string", "description": "user_preference, project_context, agent_rule" }
                },
                "required": ["key", "content"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "memory_retrieve",
            description = "Searches stored memories by query keyword.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "query": { "type": "string", "description": "Search query" }
                },
                "required": ["query"]
            }
            """.trimIndent()
        ),

        // Secret Vault Autofill Tool (Securely bound to domain; raw secret NEVER sent to model)
        AIToolDefinition(
            name = "vault_autofill",
            description = "Applies stored credentials to the current webpage form via native encrypted vault without exposing secret values to the AI model.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "secret_id": { "type": "string", "description": "ID of vault secret to autofill" },
                    "username_selector": { "type": "string", "description": "Optional CSS selector for username field" },
                    "password_selector": { "type": "string", "description": "Optional CSS selector for password field" }
                },
                "required": ["secret_id"]
            }
            """.trimIndent()
        )
    )
}
