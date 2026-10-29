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
            name = "observe_page",
            description = "Observes the current page: returns the URL, title, a numbered list of interactive elements (links, buttons, inputs, selects) with their labels, and a text excerpt. Use the returned indices with click_element and type_text. Call this whenever the page has changed and you need to know what is on it — never guess element indices or selectors.",
            parametersSchemaJson = """{ "type": "object", "properties": {} }"""
        ),
        AIToolDefinition(
            name = "click_element",
            description = "Clicks the interactive element with the given index from the most recent page observation. Real click with focus and mouse events. If it fails, the page changed — call observe_page again for fresh indices.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "index": { "type": "integer", "description": "Element index from the latest observation" }
                },
                "required": ["index"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "type_text",
            description = "Types text into the input/textarea with the given index from the most recent page observation. Focuses the field and sets the value with the native setter plus input/change events, so real forms (including React apps and Google search) register it. Set submit=true to press Enter and submit the form afterwards (e.g. for a search box).",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "index": { "type": "integer", "description": "Element index from the latest observation" },
                    "text": { "type": "string", "description": "Text to enter" },
                    "clear_first": { "type": "boolean", "description": "Clear existing text first (default true)" },
                    "submit": { "type": "boolean", "description": "Submit after typing (Enter + form submit)" }
                },
                "required": ["index", "text"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "tap",
            description = "Fallback: taps the page at viewport coordinates (x, y), clicking whatever element is there. Prefer click_element with an observation index; use tap only for canvas/image-only areas located via screenshot.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "x": { "type": "integer", "description": "Viewport x coordinate" },
                    "y": { "type": "integer", "description": "Viewport y coordinate" }
                },
                "required": ["x", "y"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "browser_back",
            description = "Navigates the browser back one page in history.",
            parametersSchemaJson = """{ "type": "object", "properties": {} }"""
        ),
        AIToolDefinition(
            name = "browser_forward",
            description = "Navigates the browser forward one page in history.",
            parametersSchemaJson = """{ "type": "object", "properties": {} }"""
        ),
        AIToolDefinition(
            name = "browser_reload",
            description = "Reloads the current page.",
            parametersSchemaJson = """{ "type": "object", "properties": {} }"""
        ),
        AIToolDefinition(
            name = "wait_for_load",
            description = "Waits until the page finishes loading (document ready), up to the timeout. Use after actions that trigger slow page transitions.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "timeout_ms": { "type": "integer", "description": "Maximum wait in milliseconds (default 8000)" }
                }
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "click",
            description = "Clicks an element identified by CSS selector in the browser. Prefer observe_page + click_element (index-based); use this only when you already know a stable selector.",
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
            description = "Types text into a form input field identified by CSS selector. Prefer observe_page + type_text (index-based, supports submit); use this only when you already know a stable selector.",
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
            description = "Scrolls the browser viewport by horizontal (x) and vertical (y) pixel offsets. Prefer scroll_down / scroll_up (page-based, they report the new scroll position) for reading pages.",
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
            name = "scroll_down",
            description = "Scrolls down to read more of the page — the normal way to work through long pages and feeds. Moves `pages` screens (default 1, fractions like 0.5 allowed) with a small overlap so no content is skipped; a fresh observation is attached automatically after scrolling. The result reports the new scroll position (~% down) and whether more content exists below — when it says the bottom is reached, stop scrolling. Alternatively pass `pixels` for an exact pixel amount.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "pages": { "type": "number", "description": "How many screens to move down (default 1, e.g. 0.5 or 2)" },
                    "pixels": { "type": "integer", "description": "Exact pixel amount instead of pages (optional)" }
                }
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "scroll_up",
            description = "Scrolls up by `pages` screens (default 1, fractions allowed) or an exact `pixels` amount. The result reports the new scroll position. A fresh observation is attached automatically after scrolling.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "pages": { "type": "number", "description": "How many screens to move up (default 1, e.g. 0.5 or 2)" },
                    "pixels": { "type": "integer", "description": "Exact pixel amount instead of pages (optional)" }
                }
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "scroll_to_element",
            description = "Scrolls the element with the given observation index into the centre of the viewport and re-observes the page, so an off-screen element from the latest observe_page can be seen and acted on with fresh coordinates. Use the index from the most recent observation.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "index": { "type": "integer", "description": "Element index from the latest observe_page" }
                },
                "required": ["index"]
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
            name = "collect_facebook_post_links",
            description = "Trained Task #1: collects the latest post links of ONE Facebook profile, deterministically (no browsing decisions needed). Give a profile URL; it visits the profile, scrolls, and returns up to `count` post permalinks (default 5, allowed 1-100), latest first, tracking parameters stripped, plus a precise status: OK (all found), PARTIAL (fewer exist), NO_POSTS, PROFILE_LOCKED, PROFILE_UNAVAILABLE, OPEN_FAILED, FB_ERROR, or LOGIN_REQUIRED. `method` selects the extraction methods: hybrid (default; the full M1-M5 ladder), m1 (visible rendered-DOM links only), or m3 (page-source structured-data mining only). The result is also saved to a markdown file plus results.txt/.csv, results.json and summary.json artifacts in AI-Tasks/. If the status is LOGIN_REQUIRED, Facebook needs the user to log in: tell the user, do NOT ask for or enter any credentials, and do not retry in a loop. Call once per profile when the user asks for several profiles. Never invent or guess links — report exactly what this tool returns.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "profile_url": { "type": "string", "description": "Facebook profile URL, e.g. https://www.facebook.com/some.page or a profile.php?id=... link" },
                    "count": { "type": "integer", "description": "How many latest post links to collect (1-100, default 5)" },
                    "method": { "type": "string", "enum": ["hybrid", "m1", "m3"], "description": "Extraction method: hybrid = full M1-M5 ladder (default), m1 = visible DOM links only, m3 = structured-data mining only" }
                },
                "required": ["profile_url"]
            }
            """.trimIndent()
        ),
        // Linux Mode Tools (real Ubuntu guest via PRoot — see linux-terminal skill)
        AIToolDefinition(
            name = "linux_status",
            description = "Reports the real state of Spark's built-in Linux environment (Ubuntu via PRoot): whether it is installed and self-tested, the Ubuntu version, rootfs size, and which catalogued tools (git, python3, pip, nodejs, curl, wget, zip) are installed — from actual probes inside the guest. Never gated; call this before planning any Linux work.",
            parametersSchemaJson = """{ "type": "object", "properties": {} }"""
        ),
        AIToolDefinition(
            name = "linux_run",
            description = "Runs a REAL bash command inside Spark's Ubuntu Linux guest (PRoot) and returns its actual stdout, stderr and exit code. The app workspace is mounted at /workspace inside the guest; files created there are visible in Spark's Files. Read-only inspection commands (ls, cat, uname, git --version, command -v X, …) run without asking; anything that installs, writes outside /workspace and /root, deletes or otherwise changes the system asks the user for approval first — never try to bypass that. Commands run non-interactively (stdin is closed): always pass flags like -y to apt. If the environment is not installed, this returns an error — tell the user to install it from the Terminal screen instead of retrying.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "command": { "type": "string", "description": "The bash command line to execute in the guest, e.g. \"ls -la /workspace\" or \"python3 --version\"" },
                    "timeout_seconds": { "type": "integer", "description": "Kill the command after this many seconds (default 120, max 900)" },
                    "cwd": { "type": "string", "description": "Guest working directory (default /workspace)" }
                },
                "required": ["command"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "linux_install_tool",
            description = "Installs one supported tool inside the Ubuntu guest using the real apt flow (apt-get update + install), then verifies it with the tool's own version check. Supported tool ids: git, python3, pip, nodejs (Node.js + npm), curl, wget, zip (zip + unzip). Always asks the user for approval first. Use this instead of raw apt commands through linux_run when the user wants one of these tools.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "tool": { "type": "string", "enum": ["git", "python3", "pip", "nodejs", "curl", "wget", "zip"], "description": "Catalog id of the tool to install" }
                },
                "required": ["tool"]
            }
            """.trimIndent()
        ),
        AIToolDefinition(
            name = "collect_screenshots",
            description = "Trained Task #2: captures REAL screenshots, deterministically (no browsing decisions needed). Give a list of URLs. Each Facebook profile/Page URL gets: a screenshot of the profile page, then its latest post links are found with Trained Task #1's collector and each post page is screenshotted too (posts_per_profile, default 2, allowed 1-10). Any other http(s) URL gets one direct screenshot. `method` selects the post-discovery extraction: hybrid (default; full M1-M5 ladder), m1 (visible DOM links only), or m3 (page-source mining only). Every source returns a precise status (OK = all requested shots, PARTIAL = some, NO_POSTS, PROFILE_LOCKED, PROFILE_UNAVAILABLE, OPEN_FAILED, FB_ERROR, LOGIN_REQUIRED) and the exact workspace file paths of its screenshots, saved under AI-Tasks/screenshots-<timestamp>/ with an index.md and summary.json. If a status is LOGIN_REQUIRED, Facebook needs the user to log in: tell the user, do NOT ask for or enter any credentials, and do not retry in a loop. Never invent or guess file paths — report exactly what this tool returns.",
            parametersSchemaJson = """
            {
                "type": "object",
                "properties": {
                    "urls": { "type": "array", "items": { "type": "string" }, "description": "URLs to capture: Facebook profile/Page URLs and/or any other http(s) URLs" },
                    "posts_per_profile": { "type": "integer", "description": "Post screenshots per Facebook profile (1-10, default 2); the profile page itself is always captured too" },
                    "method": { "type": "string", "enum": ["hybrid", "m1", "m3"], "description": "Post discovery method: hybrid = full M1-M5 ladder (default), m1 = visible DOM links only, m3 = structured-data mining only" }
                },
                "required": ["urls"]
            }
            """.trimIndent()
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
