# NOVA Workspace

NOVA Workspace is a production-oriented, standalone native Android AI application built with Kotlin and Jetpack Compose (Material 3). It unifies an autonomous personal AI agent, an embedded WebKit browser, an encrypted Secret Vault, a sandboxed local file manager, and granular permission enforcement.

---

## 📥 Download & Install

[![Download APK](https://img.shields.io/badge/Download-APK-brightgreen?style=for-the-badge&logo=android)](https://github.com/brpportal/NOVA-Workspace/releases/latest/download/NOVA-Workspace-debug.apk)

**[⬇️ Download the latest APK](https://github.com/brpportal/NOVA-Workspace/releases/latest/download/NOVA-Workspace-debug.apk)**

1. Tap the button above on your Android phone — no login needed.
2. Open the downloaded `NOVA-Workspace-debug.apk` file.
3. Allow "install from this source" when Android asks, then install.

> **Note:** This download currently serves the verified **Milestone 1** APK (Release
> [v0.1.0-m1](https://github.com/brpportal/NOVA-Workspace/releases/tag/v0.1.0-m1)).
> The `main` branch now contains the **Google AI Studio implementation** described
> below, which is under CI verification; a new release will be published from it
> once its build is verified. The Milestone 1 code remains in this repo's git
> history, and its release is untouched.
>
> All releases: [github.com/brpportal/NOVA-Workspace/releases](https://github.com/brpportal/NOVA-Workspace/releases)

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
