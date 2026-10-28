# NOVA WORKSPACE — Full Specification

> Source: Al's project specification, 2026-10-28. Saved verbatim in substance
> (all 25 sections) as the governing spec for this repository.

PROJECT: NOVA WORKSPACE
TYPE: Production-oriented, standalone Android AI application
PLATFORM: Android APK
DEVELOPMENT BUDGET: $0 initially
DEVELOPMENT ENVIRONMENT: Android phone may be the only available device
AI CONFIGURATION: User-provided API keys
BROWSER: Embedded in-app browser
STORAGE: Local-first, persistent, private workspace

## 1. Role and Mission
Act as a senior Android engineer, AI-agent architect, browser-automation
engineer, security engineer, UX designer, and QA engineer. Build NOVA
Workspace, a native Android application that combines a personal AI agent,
embedded browser, local file manager, persistent memory, encrypted Secret
Vault, and granular permission management. This is not a landing page,
chatbot mockup, or browser-only application. The finished application must
integrate these components into one consistent Android experience.

Core requirements: Native Android APK; Kotlin and Jetpack Compose; Embedded
Android WebView; Configurable AI model API; Real tool-calling agent runtime;
Real local file operations; Persistent database; Encrypted secret storage;
Browser automation with manual takeover; User-controlled permissions; Error
recovery and automated tests.

Do not claim that a feature works unless it has been implemented and tested.
Never replace a required feature with a decorative button, hardcoded
response, fake progress indicator, or simulated success message. If a
feature cannot be completed in the current environment, document the
limitation and implement the safest working alternative.

## 2. Technology Stack
ANDROID: Kotlin; Jetpack Compose; Material 3; AndroidX Navigation Compose;
Lifecycle-aware state management; Coroutines and Flow; Android WebView;
AndroidX Room with SQLite; Android Keystore-backed encryption; AndroidX
Security Crypto only where compatible and maintained, otherwise an audited,
appropriate Keystore-based design; WorkManager for appropriate deferred
background work, not for keeping an interactive browser alive.
NETWORK: A maintained HTTP client; Kotlin serialization or another
maintained structured serialization library; Streaming responses when the
chosen API supports them.
BUILD: Gradle; Android Gradle Plugin; Version catalog where practical;
Git-based source control; GitHub Actions for optional cloud APK builds.
Do not assume every AI provider uses the same API format. Implement a
provider adapter interface. Keep dependency versions compatible. Use
maintained stable releases when development begins. Do not invent library
APIs or nonexistent packages.

## 3. Application Navigation
Premium, professional, responsive Android UI. Main navigation: 1. Chat
2. Browser 3. Files 4. Tasks 5. Settings. Bottom navigation bar on compact
screens; adaptive layouts where supported. Design: premium charcoal/white
interface; optional dark and light themes; clean typography; restrained
accent colors; clear visual hierarchy; minimal unnecessary animation;
accessible touch targets; loading, empty, success, and error states; proper
keyboard and Android back-button handling. Must remain usable on smaller
Android phones. Do not use emoji as substitutes for interface icons. All
visible controls must perform their stated action.

## 4. Chat Page
Persistent AI chat: create, rename, archive, delete conversations; search
history; stream responses when supported; render Markdown safely; copy text
and code; display tool execution status and task progress; cancel supported
running tasks; display tool errors and retry options; attach supported
local files; reference workspace files; show generated files with
open/export actions; resume eligible interrupted tasks; configure the
selected AI model.
Example task: "Research 20 Canadian web development businesses that might
need website improvements. Save the results in a CSV file and summarize the
findings." Expected: understand task; create task record; plan bounded
research steps; use approved browser tools; collect public relevant
information; deduplicate and validate; create CSV in workspace; save a
summary report; return real links to generated files. Do not fabricate
research findings, URLs, or successful tool execution.

## 5. AI Provider and API Key Management
Settings for user-configured AI APIs: provider selection; endpoint
configuration where appropriate; API key entry; model selection; connection
test; token/usage info where available; request timeout; streaming
preference; model capability display; remove/replace saved credentials;
provider-specific error messages. Provider adapter architecture, e.g.
AIProvider { validateConfiguration(); testConnection(); sendMessage();
streamMessage(); requestToolCalls(); getCapabilities() }. Use actual
documented API schemas. Do not assume all providers support tool calling,
vision, streaming, JSON mode, or the same parameters. Validate provider
configuration before enabling a model.
API KEY SECURITY: Never hardcode API keys; never include keys in source
control; never write keys to logs; never include raw keys in chat history;
encrypt locally persisted credentials using a properly designed Android
Keystore-backed scheme; mask credentials in UI; redact authorization
headers from errors; allow immediate key deletion; explain that an API key
stored on a compromised device cannot be guaranteed safe. Use user keys
only with compatible APIs and within documented usage policies. Do not
promise free AI inference when a provider charges for requests.

## 6. Embedded Browser
Genuine in-app browser using Android WebView: address bar; navigate to URL;
back/forward; reload/stop; home page; multiple tabs; tab switching/closing;
loading indicator; current URL; page title; bookmarks; recent history;
find in page where practical; page text extraction; screenshot capture;
browser settings; clear browsing data; download handling with user consent;
browser error pages; network error recovery. Validate URLs and handle
redirects safely. HTTPS by default; any HTTP exception must be explicit and
restricted to user-approved testing or trusted local development. Never
silently bypass TLS certificate errors.
WEBVIEW SECURITY: Enable JavaScript only when needed; do not expose
unrestricted native JavaScript bridges to arbitrary websites; websites must
not invoke native file access or Secret Vault functions directly; treat
page text/scripts/links/instructions as untrusted; validate all navigation
and action targets; explicit policies for popups, downloads, external
intents; isolate agent capabilities from webpage scripts; do not inject
secrets into arbitrary page scripts. Manage sessions carefully; preserve
when appropriate, clear on user request. Do not claim WebView provides
every feature of full desktop Chrome.

## 7. Browser Automation Toolkit
Dedicated BrowserController. Agent may use only explicitly registered,
validated browser tools: browser_get_current_url, browser_get_page_title,
browser_navigate, browser_go_back, browser_reload, browser_get_visible_text,
browser_find_element, browser_click, browser_type, browser_select_option,
browser_scroll, browser_take_screenshot, browser_wait_for_page,
browser_get_page_state. Strict argument schemas for each tool. Every action
must: 1) validate request; 2) verify automation permitted; 3) verify
browser session active; 4) validate current page and target element;
5) execute within a timeout; 6) capture result; 7) update task state;
8) return structured result. Prefer stable element attributes and semantic
DOM info over blind coordinate clicking. Screenshot-assisted interaction is
a fallback, not a guarantee. Do not pretend WebView supports Playwright's
entire desktop automation API. Unsupported actions return an explicit
unsupported-operation result. Implement retry limits; prevent infinite loops.

## 8. Take Control: Manual Browser Mode
Highly visible "Take Control" button. When activated: pause agent browser
actions; preserve current session; enable normal user interaction
(scroll/type/click/navigate); display clear MANUAL CONTROL status; prevent
conflicting automation; keep agent task paused. Manual mode lets the user
handle legitimate CAPTCHAs, login challenges, consent screens, unexpected
site behaviour personally. Do not implement CAPTCHA bypass. "Return to AI"
button: on return — 1) reacquire browser state; 2) read actual current URL;
3) inspect page title and visible text; 4) refresh element references;
5) discard stale assumptions; 6) ask model to reassess; 7) resume only
after new state is validated. If the page changed unexpectedly, do not
blindly repeat the previous action. Support pause, resume, cancel,
emergency stop. Manual user actions take priority over queued agent actions.

## 9. Agent Runtime and Task Planner
Real agent orchestration: AgentLoop, TaskPlanner, ToolRegistry,
ToolExecutor, AgentStateManager, PermissionManager, ContextBuilder,
ResultProcessor, TaskRepository. Controlled loop: 1) receive user task;
2) build context from authorized sources; 3) request model response;
4) detect tool call; 5) validate tool name and arguments; 6) check user
permissions; 7) request approval when required; 8) execute approved tool;
9) capture actual result; 10) persist relevant state; 11) return result to
model; 12) continue until completion, cancellation, blocked action, or
configured limit; 13) final summary of actual outcomes. Implement: max
tool-call count; max task duration; request timeouts; cancellation tokens;
retry limits; duplicate-action protection; structured errors; task state
persistence; recovery after app recreation; clear completion criteria.
The model must not execute arbitrary Kotlin code, shell commands, or
unrestricted system operations via the tool dispatcher. No unrestricted
access to the entire device filesystem.

## 10. Secret Vault
Dedicated Secret Vault screen: create, view metadata for, update, delete
secrets. Types: website username; website password; API key; access token;
custom secret; other explicitly supported credentials. Each secret: unique
ID; user-defined label; secret type; optional approved domain scope;
optional approved action scope; creation/update timestamps; permission
policy; encrypted secret value. Never display secret values by default.
Never save raw secret values in chat messages, agent memory, task logs,
analytics, crash reports, or generated reports. Dedicated SecretStore
abstraction. Encrypt persisted values with an appropriate authenticated-
encryption design; protect the key with Android Keystore. Do not invent a
home-grown encryption algorithm. Implement: masked display; explicit reveal
action; clipboard warning and optional auto-clear; delete confirmation;
secret replacement; permission revocation; access audit records without
secret values.

## 11. Granular Permission Manager
Per secret: A. ALLOW ONCE — one clearly defined operation. B. ALWAYS ALLOW
— persisted scoped rule for a specified secret, verified domain, permitted
action. C. ASK EVERY TIME — fresh approval for every use. D. NEVER ALLOW —
block use through the agent's registered tools. Each rule defines: Secret
ID; domain/destination scope; permitted operation; permission mode;
optional expiration; revocation status; audit metadata. "Always Allow" is
never unrestricted across every website/tool. Changing domains,
destinations, or operation types triggers a fresh permission check. User
can review, edit, revoke rules at any time. Permissions are enforced by
the application, not by trusting the model. A webpage can never grant
permissions. High-risk operations require a separate approval policy even
if basic secret access was previously allowed.

## 12. Secure Secret Autofill
Controlled credential autofill. Example (agent logs in to a saved site):
1) navigate to approved domain; 2) verify current origin; 3) identify
appropriate username/password fields; 4) check Vault policy; 5) ask
permission if required; 6) retrieve secret only for the authorized
operation; 7) fill fields; 8) avoid sending the raw value to the AI model;
9) report whether filling succeeded; 10) request separate approval for
sensitive submission actions when policy requires. Do not send passwords
in tool results. A saved domain name alone does not make every URL on that
domain safe. Handle lookalike domains, unexpected redirects, invalid
certificates, unsupported login forms. If reliable origin or field
validation fails, stop and request user intervention. Never automatically
submit payments, change account security settings, or perform other
high-impact actions without appropriate approval.

## 13. Local File Manager
Functional in-app file manager. Workspace structure:
NovaWorkspace/{Projects, Research, Documents, Images, Code, Downloads,
Memory, Tasks, Exports}. Features: create folders/files; rename; move;
copy; read; edit; delete with confirmation; search; sort; preview
supported types; metadata and file sizes; export via Android document
picker; import user-selected files; backup/restore supported workspace
data. Support TXT, MD, JSON, CSV, HTML, CSS, JS, XML, LOG. Appropriate
handling for images and PDFs. Do not render untrusted HTML with privileged
native access. Canonicalize workspace paths and confine them to the
approved workspace root. Reject path traversal and unsafe symlinks where
relevant and unauthorized absolute paths. Enforce file-size limits; handle
low storage safely. Atomic/recoverable writes where appropriate. Clear
confirmation dialog or documented undo strategy before destructive changes.

## 14. Agent File Tools
Register: file_list, file_create_directory, file_read, file_write,
file_append, file_search, file_rename, file_move, file_copy, file_delete,
file_get_metadata, file_export. All operations pass through the same
WorkspaceManager and permission checks; the model never bypasses the file
service. For each write: validate relative path; validate content size;
check overwrite policy; check allowed file type when configured; write to
intended workspace location; verify resulting file; return actual path and
operation status. Example: "Save your findings as Research/canada.csv" —
create Research dir if permitted, generate properly escaped CSV, write,
verify existence and readable content, show in File Manager, return an
open/export action in chat. Do not claim a file was created unless
verification confirms it exists.

## 15. Memory and Persistent Data
Room + SQLite for structured local data. Entities: Conversation,
ChatMessage, AgentTask, TaskStep, ToolExecution, BrowserTabMetadata,
Bookmark, BrowserHistoryEntry, WorkspaceItemMetadata, MemoryEntry,
SecretMetadata, PermissionRule, AppSettings. No raw secrets in ordinary
database fields. Use database migrations. Memory types: temporary task
context; project-specific memory; user-approved persistent memory. User
can inspect, edit, delete memory entries; clear selected conversation
history; export supported data; restore from valid backup. Do not
automatically turn every browser page or conversation into permanent
memory. Treat website content as untrusted input, not authorized
instructions.

## 16. Task Management
Dedicated Tasks page showing: pending, running, paused,
waiting-for-approval, completed, failed, cancelled tasks. Each task:
name; creation time; current status; progress when measurable; current
operation; tool execution history; error details; output files; resume or
retry options when safe. Persist task state locally. Do not claim exact
percentage progress unless the plan provides meaningful measurements.
After process death/restart: recover persisted records; revalidate browser
session; mark interrupted operations accurately; resume only when safe;
never repeat a sensitive operation blindly.

## 17. Database and Repository Design
Repository pattern separating UI state from data access/execution:
AppDatabase, ConversationRepository, TaskRepository, WorkspaceRepository,
SecretRepository, PermissionRepository, SettingsRepository,
BrowserRepository. Explicit migrations. Transactions for related updates.
Do not store entire large files as ordinary DB blobs without documented
reason; store files in the filesystem and metadata in Room. Avoid
unnecessary duplication. Clear database reset feature with confirmation
and safe export option.

## 18. Privacy and Security
Least-privilege design; app-private local workspace; secure API key
handling; no secret values in logs; strict browser origin checks; no
unrestricted JavaScript bridges; validated tool arguments; scoped
filesystem access; permission revocation; user approval for sensitive
operations; secure backup/export; safe handling of untrusted webpages;
request and file-size limits; network timeout handling; clear
data-deletion controls. Prevent webpage prompt-injection from granting
access to secrets or files. Treat model output as untrusted until
validated. The model does not decide its own permissions. Do not silently
upload workspace files to an API; when attaching local content to an AI
request, show/document what will be transmitted. Privacy notice explaining:
local storage behaviour; external AI API requests; data sent to configured
providers; browser cookies and history; secret storage limitations; backup
and uninstall implications. Never promise perfect security.

## 19. Performance and Resource Management
Designed for devices with limited RAM, storage, battery: no blocking the
main UI thread; coroutines for background work; release obsolete WebViews
and resources safely; limit concurrent model requests; limit agent task
concurrency; paginate large chat histories and file lists; stream large
file operations where appropriate; enforce task timeouts; handle network
disconnections; handle process recreation; respect Android lifecycle;
show when the browser session is unavailable. Do not assume a browser can
run continuously in the background without Android restrictions. Core app
must not require a permanent custom cloud server.

## 20. Settings
Settings for: AI provider; API key; model; API endpoint when supported;
temperature or other supported parameters; theme; default home page;
browser JavaScript settings; download behaviour; workspace location within
app storage; agent tool limits; approval policies; Secret Vault
management; memory controls; privacy controls; export and restore; clear
browsing data; clear application data. Explain consequences of destructive
settings before applying them.

## 21. Testing Requirements
UNIT TESTS: permission evaluation; tool argument validation; path
traversal rejection; secret redaction; task state transitions; database
repositories; CSV generation; API error handling.
INTEGRATION TESTS: API configuration/request handling; tool call
execution; workspace file creation and verification; Secret Vault access
policies; browser controller lifecycle; task persistence; DB migrations.
ANDROID UI TESTS: main navigation; chat interactions; browser navigation;
Take Control and Return to AI; file creation/deletion; secret masking;
permission dialogs; API configuration errors.
SECURITY TESTS: wrong-domain credential use blocked; revoked permission
enforced immediately; invalid paths cannot escape workspace; secrets do
not appear in logs; malicious webpage text cannot grant permissions;
untrusted pages cannot invoke privileged tools; cancelled tasks do not
continue issuing actions; invalid tool arguments rejected. Use fake AI
providers in automated tests. Never expose real API keys in test fixtures.

## 22. Acceptance Criteria
A. APPLICATION: APK builds; app launches on a compatible Android device;
navigation works; handles configuration changes.
B. CHAT AND AI: a real configured API responds; errors displayed
accurately; tool calls execute through the registered runtime;
conversation history persists.
C. BROWSER: user can navigate real websites; tabs work as implemented;
page text can be extracted where supported; Take Control pauses agent
actions; Return to AI revalidates the current page.
D. FILE MANAGER: agent can create and read a workspace file; file appears
in File Manager; content survives app restart; export works via Android's
supported flow.
E. SECRET VAULT: secret values protected at rest; Allow Once is a
one-operation grant; Ask Every Time prompts each use; Never Allow blocks;
Always Allow limited to its configured scope; revoked permissions take
effect immediately.
F. RELIABILITY: timeouts do not freeze UI; failed actions return real
errors; cancelled tasks stop where safe; no false success messages; no
unresolved critical security defects. Report test results honestly. Do not
fabricate test execution, screenshots, builds, or approvals.

## 23. Zero-Budget Development Strategy
Keep the project buildable with free tools. Workflow: 1) create Android
project; 2) init Git repo; 3) push source to GitHub; 4) build minimal debug
APK; 5) install and test on Android; 6) implement one subsystem at a time;
7) run available tests after each major change; 8) automate APK builds
with GitHub Actions if the free allowance supports it. No paid cloud
browser service. No Supabase required for core local persistence. No paid
AI inference required to compile the app. AI requests still require a
compatible API and may incur provider usage charges. If no API key is
configured, provide a clear setup screen and allow local non-AI features
to work. Never embed a provider's private server credentials in the app.

## 24. Development Execution Plan
Small, verifiable milestones:
MILESTONE 1: Native Android project; Compose navigation; Chat, Browser,
Files, Tasks, Settings screens; initial debug APK.
MILESTONE 2: Room database; local workspace; functional file manager;
export and import.
MILESTONE 3: Embedded WebView; navigation controls; tabs and history;
browser lifecycle handling.
MILESTONE 4: AI provider abstraction; API configuration; real chat
requests; error and timeout handling.
MILESTONE 5: Tool registry; agent execution loop; browser tools; file tools.
MILESTONE 6: Secret Vault; encryption; permission policies; secure
credential autofill.
MILESTONE 7: Take Control; agent pause/resume; browser state
reacquisition; task recovery.
MILESTONE 8: Security testing; automated tests; performance improvements;
APK build workflow; release documentation.
After each milestone: summarize implemented features; list changed files;
report build and test results; identify remaining defects; do not claim
unfinished milestones are complete.

## 25. Final Deliverables
1. Complete Android source code. 2. Working Gradle configuration.
3. Android manifest and required permissions. 4. Database schema and
migrations. 5. Agent runtime and tool registry. 6. Browser controller.
7. Secret Vault and permission manager. 8. Workspace and file manager.
9. Automated tests. 10. README with setup instructions. 11. API provider
configuration instructions. 12. Security and privacy documentation.
13. Build instructions for Android-only development. 14. GitHub Actions
workflow if supported. 15. Debug APK when a successful build is possible.
16. Known limitations and release checklist.

Do not return only a plan or a UI prototype. Start by inspecting the
available environment, choosing compatible dependencies, creating the
project structure, and implementing Milestone 1. If the environment cannot
build an APK directly, generate the complete buildable source project and
provide reproducible instructions for building it.

PRIORITY ORDER: 1. Security and privacy. 2. Correctness. 3. Real working
functionality. 4. Persistence and reliability. 5. Usability. 6. Visual polish.

Build a real application, not a convincing-looking simulation of one.
