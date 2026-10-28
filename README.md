# NOVA Workspace

Production-oriented, standalone Android AI application. Native Android APK,
Kotlin + Jetpack Compose, embedded browser, local file manager, persistent
memory, encrypted Secret Vault, and granular permission management.
Full specification: [docs/SPEC.md](docs/SPEC.md)

## 📥 Download & Install

[![Download APK](https://img.shields.io/badge/Download-APK%20(Milestone%201)-brightgreen?style=for-the-badge&logo=android)](https://github.com/brpportal/NOVA-Workspace/releases/latest/download/NOVA-Workspace-debug.apk)

**[⬇️ Download the latest APK](https://github.com/brpportal/NOVA-Workspace/releases/latest/download/NOVA-Workspace-debug.apk)**

1. Tap the button above on your Android phone — no login needed.
2. Open the downloaded `NOVA-Workspace-debug.apk` file.
3. Allow "install from this source" when Android asks, then install.

This is a **debug build** of Milestone 1 (app shell + 5 screens) for testing.
All releases: [github.com/brpportal/NOVA-Workspace/releases](https://github.com/brpportal/NOVA-Workspace/releases)

- **Package name:** `com.novaworkspace.app`
- **Budget:** $0 initially — free tools only, user-provided AI API keys.
- **Privacy:** local-first, persistent, private workspace. No custom cloud
  server is required for core features.

## Milestone status

| Milestone | Scope | Status |
|---|---|---|
| M1 | Native Android project, Compose navigation, Chat / Browser / Files / Tasks / Settings screens, initial debug APK | **Implemented in this repo (v0.1.0-m1)** |
| M2 | Room database, local workspace, functional file manager, export/import | Not yet implemented |
| M3 | Embedded WebView, navigation controls, tabs and history, lifecycle handling | Not yet implemented |
| M4 | AI provider abstraction, API configuration, real chat requests, error/timeout handling | Not yet implemented |
| M5 | Tool registry, agent execution loop, browser tools, file tools | Not yet implemented |
| M6 | Secret Vault, Keystore-backed encryption, permission policies, secure autofill | Not yet implemented |
| M7 | Take Control, agent pause/resume, browser state reacquisition, task recovery | Not yet implemented |
| M8 | Security testing, automated tests, performance, release documentation | Not yet implemented (one real unit-test suite exists from M1: `UrlValidatorTest`) |

## What Milestone 1 does — and does not do

Does:
- Launches as a native Android app with a Material 3 bottom-navigation shell.
- Five real Compose screens: Chat, Browser, Files, Tasks, Settings.
- Working theme preference (System / Light / Dark) that applies immediately.
- A real, unit-tested `UrlValidator` utility (HTTPS by default, HTTP flagged,
  non-web schemes rejected) ready for the M3 browser.
- Builds a debug APK in GitHub Actions and uploads it as a build artifact.

Does NOT (honestly, no simulation):
- No AI chat yet. The Chat composer does not send anything to a model and
  does not fabricate a reply; it states that a provider is not configured.
- No browser / WebView yet (M3). The Browser screen is an empty state.
- No file manager, database, tasks engine, Secret Vault, or API-key storage
  yet. In particular M1 has **no API-key field on purpose**: keys must only
  ever be stored in the Keystore-backed vault (M6), never in plain settings.

## Architecture (M1)

```
app/src/main/java/com/novaworkspace/app/
  MainActivity.kt          single activity, Compose entry point
  navigation/              NovaDestination (5 bottom-nav destinations)
  ui/theme/                Material 3 charcoal/white themes (dark + light)
  ui/chat|browser|files|tasks|settings/   one package per screen
  ui/EmptyState.kt         shared honest empty-state component
  core/util/UrlValidator.kt  pure-Kotlin URL validation for the future browser
app/src/test/              JVM unit tests (UrlValidatorTest)
```

Clean-architecture packages for data / agent / browser-controller layers are
introduced in their own milestones (M2/M3/M5) rather than as empty shells.

## Versions — and why

| Component | Version | Reason |
|---|---|---|
| Gradle | 8.9 | Minimum / matching distribution for AGP 8.7.x |
| Android Gradle Plugin | 8.7.3 | Stable 8.7 line, JDK 17, well-tested with Kotlin 2.0 |
| Kotlin | 2.0.21 | Stable 2.0 line; Compose compiler is now the `kotlin-compose` Gradle plugin |
| Jetpack Compose BOM | 2024.10.01 | Stable BOM matching the Kotlin 2.0 / AGP 8.7 era |
| compileSdk / targetSdk | 35 | Stable, widely supported target for this toolchain |
| minSdk | 24 | Broad device coverage including smaller / older phones |
| Navigation Compose | 2.8.4 | Stable 2.8 line |
| Lifecycle | 2.8.7 | Stable 2.8 line |
| Activity Compose | 1.9.3 | Stable |
| Core KTX | 1.15.0 | Stable |
| Coroutines | 1.9.0 | Stable |
| JDK | 17 | Required by AGP 8.x |

All versions are managed in `gradle/libs.versions.toml` (version catalog).

## Building

### Option A — GitHub Actions (no local setup, used for M1)
Push to `main`. The workflow in `.github/workflows/android.yml` sets up
JDK 17 + Gradle 8.9, regenerates the wrapper if the jar is ever missing,
runs `testDebugUnitTest`, then `assembleDebug`, and uploads
`app-debug.apk` as the artifact **nova-workspace-debug-apk**.

### Option B — Android Studio (phone-only developers: use a free cloud
### workspace or any PC when available; Android Studio cannot run on Android)
1. Install Android Studio (JDK 17 is bundled) and SDK Platform 35.
2. Open this folder, let Gradle sync (it downloads Gradle 8.9 via the wrapper).
3. Run: `./gradlew testDebugUnitTest assembleDebug`
4. APK: `app/build/outputs/apk/debug/app-debug.apk` — install on the device
   with `adb install app-debug.apk` or by transferring the file to the phone.

### Option C — Command line only
Requires JDK 17 + Android SDK Platform 35 (`ANDROID_HOME` set), then
`./gradlew assembleDebug`.

Note: the development VM that produced M1 has no Java/Gradle/Android SDK,
so the M1 APK is built exclusively via GitHub Actions (SPEC Sec. 23/24
fallback: complete buildable source + reproducible CI build).

## API provider configuration

Not implemented yet (M4/M6). Planned behaviour, per SPEC Sec. 5:
- Provider adapter interface (`validateConfiguration`, `testConnection`,
  `sendMessage`, `streamMessage`, `requestToolCalls`, `getCapabilities`).
- User supplies their own API key per provider; keys are never hardcoded,
  never logged, never committed, and are stored only in the Keystore-backed
  Secret Vault (M6) with masked UI display.
- Capabilities differ per provider (tool calling, vision, streaming, JSON
  mode) and are detected/displayed, never assumed.
- No API key configured => local non-AI features still work, chat shows a
  clear setup state. AI requests may incur provider charges; nothing in
  this app promises free inference.

## Security & privacy notes (summary — full text in SPEC Sec. 10-12, 18)

- Least privilege, app-private local workspace, scoped file access only.
- Secrets: authenticated encryption with a Keystore-protected key, no
  home-grown crypto, no secret values in logs/chat/memory/analytics.
- Granular permissions per secret: Allow Once / Always Allow (scoped to a
  verified domain + action only) / Ask Every Time / Never Allow, revocable
  at any time and enforced by the app, never by trusting the model.
- Browser: HTTPS by default, no silent TLS bypass, no unrestricted
  JavaScript bridges, webpage content is always untrusted input and can
  never grant permissions or invoke privileged tools.
- A key on a compromised device cannot be guaranteed safe; the app never
  promises perfect security.

## Known limitations (M1)

- Theme preference is held for the session (saved state), not yet persisted
  to disk — persistence arrives with the settings store in M2/M4.
- No database, no files, no browser, no AI, no vault (see milestone table).
- Release build is not minified and there is no signing config yet (M8).
- Launcher icon is a simple placeholder vector/PNG, not final branding.

## Release checklist

See SPEC Sec. 22 acceptance criteria. M1 is checked against criteria A
(APK builds, launches, navigation works, configuration changes handled by
Compose saved state) via the CI build; on-device verification is pending
Al installing the CI-produced debug APK.
