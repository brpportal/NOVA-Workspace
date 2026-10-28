# Spark (formerly NOVA Workspace)

Spark (formerly NOVA Workspace) is a production-oriented, standalone native Android AI application built with Kotlin and Jetpack Compose (Material 3). It unifies an autonomous personal AI agent, an embedded WebKit browser, an encrypted Secret Vault, a sandboxed local file manager, and granular permission enforcement.

---

## 📥 Download & Install

[![Download APK](https://img.shields.io/badge/Download-APK-brightgreen?style=for-the-badge&logo=android)](https://github.com/brpportal/NOVA-Workspace/releases/latest/download/NOVA-Workspace-debug.apk)

**[⬇️ Download the latest APK](https://github.com/brpportal/NOVA-Workspace/releases/latest/download/NOVA-Workspace-debug.apk)**

1. Tap the button above on your Android phone — no login needed.
2. Open the downloaded `NOVA-Workspace-debug.apk` file.
3. Allow "install from this source" when Android asks, then install.

> **Note:** This download currently serves the verified **Milestone 1** APK (Release
> [v0.1.0-m1](https://github.com/brpportal/NOVA-Workspace/releases/tag/v0.1.0-m1)).
> The `main` branch now contains the **Spark upgrade** (the app was renamed
> from NOVA Workspace to **Spark** in the Google AI Studio upgrade of
> 2026-10-28): presence states in chat, approval records with timeout and an
> Approvals history screen, Muse-style browser control wording
> ("Take control" / "You're in control" / "Hand back to the agent") with a
> "Needs you" task state, Add memory / Summarize into memory, and a task
> audit timeline — merged on top of the AI Studio v2 update and the
> 2026-10-28 fix round (chat-vs-task routing, Fetch Models empty-list
> handling, workspace seeding + Environment pack), whose behaviours were
> re-verified piece by piece against the new code before shipping. Earlier
> code remains in this repo's git history, and the Milestone 1 release is
> untouched.
>
> **Phase 2 (2026-10-28, under CI verification):** `main` now also contains
> the Phase 2 upgrade from the REA-study plan — a **Goals** tab (goals and
> one level of subgoals with statuses and task-completion progress, tasks
> linkable to goals), **recurring tasks** (run-once / daily / weekly /
> monthly cadence executed by a WorkManager background job, with an
> Upcoming view and a per-task run history recorded from real outcomes),
> a **Created by Spark** files section with text/markdown/CSV and HTML
> viewers, and **Sources** citations on browser tasks. The Phase 1 and
> fix-round behaviours above were re-checked against this code as well
> before shipping.
>
> All releases: [github.com/brpportal/NOVA-Workspace/releases](https://github.com/brpportal/NOVA-Workspace/releases)

---

## Chat vs agent tasks

Sending a message in Chat does **not** always start a task. The rule
(implemented in `ChatIntent.kt`) is deterministic — no ML, no hidden
heuristics:

- A message containing a **URL or domain** (e.g. `open google.com`) → **agent task**.
- A **question** (ends with `?` or starts with a question word — what / why /
  how / kia / kya / kaise / ...) → **plain chat**, answered directly by the
  configured AI model.
- Any other message containing an **action verb** (open, search, browse, go,
  find, download, create, write, save, send, fetch, summarize, research, ...
  including Roman Urdu imperatives like `kholo`, `banao`, `bhejo`) → **agent task**.
- Everything else (greetings, thanks, discussion) → **plain chat**.

Plain chat never creates a task record. Agent step narration stays in the
Tasks tab. If no real AI provider is configured, chat and task launch show an
honest system notice with the real reason — simulated/canned replies are
never presented as if a model answered.

## Environment pack

The app bundles its agent environment files and installs them into the
workspace from the **Files** tab: the **Install Environment** card shows the
pack's exact size (computed from the bundled contents) and writes the pack
as eight `SKILL.md` files under `skills/<name>/` in the workspace — each
with a front-matter header (`name` + `when_to_use`) followed by its
instructions: `agent-core`, `browser-automation`, `safety-guardrails`,
`task-template`, `personal-memory`, `agent-training`, plus the newer
`phone-agent-playbook` (observe → plan → act → verify) and
`research-with-sources` (collect titles/URLs, present them as sources).
Installing **never overwrites an existing file** — edited files are always
kept — and a `.environment-installed` marker records the install (the card
then offers "Reinstall Environment"). It is a purely local copy and works
offline.

A human-readable reference copy of the environment pack is kept in the
repo's `environment/skills/` folder, mirrored at
`app/src/main/assets/environment/skills/`.
The workspace itself is seeded on first launch with the standard folders, a
root `README.md` explaining the layout, the Memory/Training starters, and
`AI-Tasks/index.md` — seeding is idempotent and never overwrites edits.

## Provider notes (Fetch Models & Test Connection)

- **Provider profiles** (Settings → AI Engine): OpenRouter, Hugging Face,
  OpenAI-Compatible, Anthropic Claude, Google Gemini and Custom. Selecting
  a profile auto-fills its Base URL (`https://openrouter.ai/api/v1`,
  `https://router.huggingface.co/v1`, …); Custom leaves the Base URL freely
  editable. The chosen profile is persisted. If the profile family and the
  Base URL host disagree (e.g. Anthropic profile with an OpenRouter URL),
  an inline warning is shown.
- **Pasted API keys are sanitized** on save/fetch/test: invisible and
  non-ASCII characters (a common paste accident) are stripped, with a
  one-line note saying how many were removed — so a bad paste can no longer
  produce a raw "Unexpected char … in Authorization value" error anywhere
  in the UI.
- **Fetch Models** calls `GET {baseUrl}/models` with your key and shows a
  selectable list; tapping a model makes it the Active Model (manual entry
  stays available). A model gets a **FREE** tag only from the provider's
  own data: an id ending in `:free`, or pricing with prompt **and**
  completion both `0` (OpenRouter). Providers that return no free signal
  (e.g. Hugging Face) show no tags. An HTTP 200 with an **empty** list is
  shown as an info note, not an error — some providers/plans (e.g. an
  exhausted free plan) return `{"data": []}` — and you can always type a
  model name manually in the Active Model field. Non-200 responses show
  the real HTTP status code. The chat screen's model chip opens the same
  fetch-and-switch flow.
- **Test Connection** sends a minimal chat request (`ping`) with the
  configured model and reports the real outcome — success with latency, or
  the real HTTP status and error body. A manually typed model name that does
  not exist on your plan fails here and in chat with the provider's real
  error, never a fake success.

---

## Key Features

### 1. Personal AI Agent
- **Autonomous Agent Loop**: Evaluates incoming goals, breaks tasks down into structured steps, interacts with tools, and provides real-time streaming feedback.
- **Strict Guardrails**: Prevents arbitrary shell execution, duplicate-action loops (aborts on identical consecutive actions), enforces step limits (default 25), and requires user approval before performing sensitive operations.
- **Provider Adapters**: Supports Google Gemini, standard OpenAI-compatible endpoints (Ollama, Groq, DeepSeek), Anthropic Claude, and an Autonomous Offline Simulator for local operation without external keys.

### 2. Embedded Browser & Automation Controller
- **Genuine Android WebView**: Tabs, address bar, history, bookmarks, and in-page text search.
- **Enforced HTTPS**: Never silently bypasses SSL/TLS errors.
- **Automation Toolkit**: Validated tools (`navigate`, `get_current_url`, `get_page_title`, `get_visible_text`, `find_element`, `click`, `type`, `scroll`, `take_screenshot`, `get_page_state`).
- **Take Control Safeguard**: A prominent manual control toggle allows the user to pause agent execution to complete CAPTCHAs, bot detections, or biometric logins. Resuming via "Return to AI" re-reads and re-validates the live webpage state.

### 3. Encrypted Secret Vault
- **Hardware-Backed Encryption**: Utilizes AES-256-GCM via the Android `KeyStore` provider.
- **Origin-Scoped Autofill**: Validates website origins prior to filling form credentials. Raw secrets are never sent to the LLM or stored in chat logs.
- **Granular Permissions**: Supports `ALLOW_ONCE`, `ALWAYS_ALLOW`, `ASK_EVERY_TIME`, and `NEVER_ALLOW`.

### 4. Local File Manager
- **Sandboxed Storage**: Fully confined within the private application directory (`workspace/`). Standard folders include `Projects/`, `Research/`, `Documents/`, `Images/`, `Code/`, `Downloads/`, `Memory/`, `Tasks/`, and `Exports/`.
- **Path Traversal Defense**: Canonical path checks prevent traversal attacks (`../`).
- **In-App Text Editor**: Read and edit TXT, MD, JSON, CSV, HTML, CSS, JS, XML, and LOG files directly.

### 5. Task & Process Monitoring
- **Live Lifecycle**: Real-time status badges (`PENDING`, `RUNNING`, `WAITING_APPROVAL`, `PAUSED`, `COMPLETED`, `FAILED`, `CANCELLED`).
- **Interactive Approval**: Explicit one-click approval prompts (`Approve Once`, `Always Allow For This Domain`, `Reject`).
- **Persistence**: All conversation threads, tasks, steps, tool executions, tabs, and memories persist across application restarts in Room/SQLite.

---

## Architecture & Technology Stack

- **Language**: Kotlin 2.2
- **UI Framework**: Jetpack Compose (Material Design 3)
- **Database**: Room 2.7 with SQLite
- **Network**: OkHttp 4.10, Retrofit 2.12
- **Cryptography**: Android KeyStore (AES-256-GCM)
- **Browser**: Android WebKit WebView with custom clients
- **Architecture**: MVVM with Repository Pattern and Coroutines Flow

---

## Build & Test Instructions

### Android Build
Build the debug APK using Gradle:
```bash
gradle :app:assembleDebug
```

### Run Unit Tests
Run local JVM unit and Robolectric tests:
```bash
gradle :app:testDebugUnitTest
```

---

## Known Limitations & Security Boundaries

1. **Untrusted Web Content**: JavaScript on loaded web pages has no native bridges to device files, the encrypted vault, or shell commands.
2. **Payment & Security Forms**: The agent is explicitly prohibited from auto-submitting financial transactions, payments, or security setting changes without user interaction.
3. **CAPTCHA Solutions**: The app does not bypass CAPTCHAs or Cloudflare verification; the user must utilize the "Take Control" button to solve challenges natively.
