# AGENT_KNOWLEDGE.md — Ryzix Code

> **Read this file first before touching any code.**
> This document is the single source of truth for the Ryzix Code Android project.
> Every AI agent working on this project must follow the rules in this file exactly.

---

## 1. Project Identity

| Field            | Value                        |
|-----------------|------------------------------|
| App name         | Ryzix Code                   |
| Package          | `com.ryzix.code`             |
| Current version  | `9.0` (versionCode 9)        |
| Language         | Kotlin                       |
| Min SDK          | 26 (Android 8.0)             |
| Target SDK       | 31 (Android 12)              |
| Build tool       | CodeAssist (Tyron IDE) + Gradle 7.0.2 + AGP 7.0.1 |
| Dev environment  | Termux on Android (aarch64)  |

---

## 2. What This App Does

**Ryzix Code** is a full mobile code editor/IDE for Android. It lets you:
- Open any folder from device storage and edit files inside it
- View and navigate a file tree (folders/files, expand/collapse, create/rename/delete)
- Edit code in a tabbed editor with undo/redo, symbol keys, find-in-files search
- Chat with an autonomous AI agent (OpenAI-compatible API or Google Gemini) that can read your entire project and write files directly into it with a diff accept/reject UI
- Open an AI Studio (WebView) — a browser panel for using web-based AI tools with context injection of your current file

---

## 3. Architecture

### Activities
| Activity            | Role |
|--------------------|------|
| `SplashActivity`   | Launch screen with logo + fade-in animation. Transitions to MainActivity after 2.2s |
| `MainActivity`     | Project list. Open folder via SAF (Storage Access Framework). Saves recent projects to SharedPreferences. SAF picker does NOT require MANAGE_EXTERNAL_STORAGE. |
| `EditorActivity`   | Core editor. DrawerLayout with left drawer (file tree + search) and right drawer (AI agent chat). Tabbed editor, bottom toolbar, symbol keys. Auto-saves all modified tabs in onPause(). |
| `StudioActivity`   | WebView that loads any AI web tool (e.g. Google AI Studio). Can inject project context into the page and extract/apply code responses |

### Key Classes
| Class                 | Role |
|----------------------|------|
| `AIClient`           | Autonomous AI agent. Supports OpenAI-compatible REST and Google Gemini. **Gemini URL fix v9**: strips any trailing `/v1beta` or `/v1` from the user's base URL before appending the standard path — prevents 404 from double path when base URL already contains `/v1beta/`. Conversation history is thread-safe (Collections.synchronizedList). Supports request cancellation. writeChange() supports subdirectory paths (creates intermediate folders). Returns boolean on write — failure shown in summary instead of silently ignored. extractStr() correctly handles \uXXXX unicode escapes. extractOpenAIText() searches after "assistant" role marker. Both parseFileWrites() and handleAgentNoteCreation() stop on missing FILE_END instead of consuming subsequent blocks. |
| `FileTreeAdapter`    | RecyclerView adapter for the file tree. ALL SAF I/O (listFiles) is async on an executor — init and refresh no longer block the main thread. |
| `ChatAdapter`        | RecyclerView adapter for the AI chat bubbles |
| `SearchResultAdapter`| RecyclerView adapter for in-project search results |
| `PrefsHelper`        | SharedPreferences wrapper for saving/loading the recent project list |
| `Project`            | Data class: name, uriString, lastOpened (Long) |
| `OpenTab`            | Data class per editor tab: URI, name, content, cursorPos, modified flag, undoStack (capped at 200), redoStack |
| `ChatMessage`        | Data class: text, isUser |
| `SearchResultItem`   | Data class: fileUri, fileName, lineNumber, lineText, query |

---

## 4. File Structure

```
Ryzix_Code/
├── app/
│   ├── app_config.json          ← CodeAssist project config (minSdk, versionName, libraries)
│   ├── build.gradle             ← App-level Gradle (compileSdk 31, minSdk 26, versionCode 9)
│   ├── libraries.json           ← CodeAssist library list
│   ├── proguard-rules.pro
│   ├── repositories.json
│   └── src/main/
│       ├── AndroidManifest.xml  ← Declares all 4 activities + permissions
│       ├── java/com/ryzix/code/
│       │   ├── AIClient.kt
│       │   ├── ChatAdapter.kt
│       │   ├── ChatMessage.kt
│       │   ├── EditorActivity.kt
│       │   ├── FileTreeAdapter.kt
│       │   ├── MainActivity.kt
│       │   ├── PrefsHelper.kt
│       │   ├── Project.kt
│       │   ├── ProjectAdapter.kt
│       │   ├── SplashActivity.kt
│       │   └── StudioActivity.kt
│       └── res/
│           ├── drawable/        ← All vector icons (ic_*.xml) + logo.png (app icon foreground)
│           ├── layout/          ← activity_editor.xml, activity_main.xml, activity_splash.xml,
│           │                       activity_studio.xml, item_project.xml
│           ├── mipmap-anydpi-v26/ ← Adaptive icon XMLs (use @drawable/logo as foreground)
│           ├── values/          ← colors.xml, strings.xml, themes.xml
│           └── values-night/    ← themes.xml (dark theme, same as day theme)
├── .github/
│   └── workflows/
│       └── android-release.yml  ← CI: builds debug APK and creates GitHub Release on push to main
├── build.gradle                 ← Root Gradle (AGP 7.0.1 classpath)
├── gradle/wrapper/
│   └── gradle-wrapper.properties ← Gradle 7.0.2
├── gradle.properties
├── gradlew / gradlew.bat
└── settings.gradle
```

---

## 5. Permissions Used

| Permission | Why |
|-----------|-----|
| `INTERNET` | AI API calls |
| `READ_EXTERNAL_STORAGE` | Read project files |
| `WRITE_EXTERNAL_STORAGE` | Write files (≤ API 29) |
| `MANAGE_EXTERNAL_STORAGE` | Broad storage access (API 30+) |
| `POST_NOTIFICATIONS` | Future notification support |

---

## 6. AI Agent Protocol (AIClient) — v9

The AI agent operates autonomously, completing tasks end-to-end. Protocol embedded in responses:

**Write project file:**
```
FILE_WRITE:path/to/filename.kt
<full file content here>
FILE_END
```

**Save agent note:**
```
FILE_CREATE:notename.md
<content>
FILE_END
```

When the AI responds with `FILE_WRITE`:
1. `AIClient.handleAgentNoteCreation()` processes any `FILE_CREATE` blocks first
2. `AIClient.parseFileWrites()` extracts `FILE_WRITE` blocks
3. The old file content is read for diffing
4. `EditorActivity.showDiffDialog()` shows a diff to the user
5. User accepts → `writeChange()` saves via ContentResolver → file tree auto-refreshes
6. User rejects → change is discarded

The system prompt (`buildSystemPrompt`) includes:
- Full file tree of the project (up to **200** nodes)
- Content of the currently active file
- Content of up to 4 other open tabs
- Agent note files from `Android/data/com.ryzix.code/files/agent/`

**Conversation history:** Up to 20 turns (10 full exchanges) stored in memory per session.
Long-press the send button to clear history.

**Supported AI providers:**
- OpenAI-compatible (OpenRouter, local LLMs, etc.) — proper `system` + `user`/`assistant` message roles
- Google Gemini — uses `system_instruction` + `contents` with `user`/`model` roles

**Gemini URL construction (v9 fix):**
Base URL from settings may already contain `/v1beta/` (e.g. `https://generativelanguage.googleapis.com/v1beta/`).
The client now strips any trailing `/v1beta` or `/v1` before appending its own path:
```
normalizedBase = baseUrl.trimEnd('/').removeSuffix("/v1beta").removeSuffix("/v1")
url = "$normalizedBase/v1beta/models/$model:generateContent?key=$apiKey"
```
This means BOTH of these base URL formats work correctly:
- `https://generativelanguage.googleapis.com`
- `https://generativelanguage.googleapis.com/v1beta/`

**Agent settings** (stored in SharedPreferences under key `ryzix_prefs`):
- `agent_url` — Base URL (default: `https://openrouter.ai/api/v1`)
- `agent_key` — API key
- `agent_model` — Model name (default: `qwen/qwen3-coder:free`)

**Token limit:** 8192 max output tokens

---

## 7. Design System (Colors & Theme)

| Token | Hex | Usage |
|-------|-----|-------|
| `bg_app` | `#0F0F11` | Main background |
| `bg_panel` | `#1A1A1D` | Panels, cards |
| `bg_card` | `#161618` | Dialogs, overlays |
| `bg_input` | `#242428` | Input fields |
| `accent` | `#E52A3F` | Buttons, highlights, send icon |
| `text_main` | `#EEEEF2` | Primary text |
| `text_muted` | `#80808A` | Secondary text |
| `divider` | `#2A2A2F` | Separator lines |

Theme: `Theme.RyzixCode` → `android:Theme.Holo.NoActionBar` (no AppCompat, no Material3).  
**Important:** This project does NOT use `AppCompatActivity` — all activities extend `android.app.Activity` directly.

App icon: `@drawable/logo` (logo.png) used as adaptive icon foreground. Background: `#0F0F11` (dark).

---

## 8. Libraries & Dependencies

All resolved via CodeAssist's own cache (paths under `/storage/emulated/0/Android/data/com.tyron.code/`).  
Key libraries:
- `androidx.constraintlayout:constraintlayout:2.1.0`
- `androidx.drawerlayout:drawerlayout:1.0.0`
- `androidx.recyclerview:recyclerview:1.1.0`
- `androidx.documentfile:documentfile:1.0.0`
- `androidx.appcompat:appcompat:1.3.1`
- `com.google.android.material:material:1.4.0`
- `org.jetbrains.kotlin:kotlin-stdlib:1.5.21`

---

## 9. Bugs Fixed Per Version

### v9 (current)
- **AIClient (CRITICAL)**: Gemini 404 error — base URL already had `/v1beta/` but code appended another `/v1beta/models/...`, producing a double-path URL. Fixed: normalize base URL by stripping any trailing `/v1beta` or `/v1` before building the request URL.
- **AIClient (CRITICAL)**: `extractStr()` did not handle `\uXXXX` JSON unicode escape sequences — AI responses with smart quotes, special chars, etc. were garbled. Fixed: full unicode escape decoding.
- **AIClient (CRITICAL)**: `writeChange()` silently swallowed all exceptions — write failures were reported as "Applied" in the summary. Fixed: returns Boolean, failure message shows in summary.
- **FileTreeAdapter (CRITICAL)**: `init{}` and `refresh()` called `loadChildrenSync()` (SAF I/O) on the main thread — ANR risk on large projects. Fixed: both now load children on the executor thread asynchronously.
- **AIClient (HIGH)**: `extractOpenAIText()` searched for first `"content":"` occurrence — could match metadata before the assistant reply. Fixed: searches after the `"role":"assistant"` marker.
- **AIClient (HIGH)**: `history` list accessed from main thread (clearHistory/getHistorySize) and pool thread (add/remove) without synchronization. Fixed: `Collections.synchronizedList`.
- **AIClient (HIGH)**: Missing `FILE_END` caused `parseFileWrites()` and `handleAgentNoteCreation()` to consume the rest of the response, swallowing subsequent blocks. Fixed: stop processing on missing FILE_END.
- **AIClient (MEDIUM)**: `writeChange()` flattened subdirectory paths (`src/main/Foo.kt` → `src_main_Foo.kt`). Fixed: creates intermediate directories via SAF.
- **AIClient (MEDIUM)**: No way to cancel in-flight requests. Added `cancelRequest()`.
- **EditorActivity (MEDIUM)**: No auto-save on pause — unsaved changes lost if app killed. Fixed: `onPause()` auto-saves all modified tabs.
- **MainActivity (LOW)**: SAF folder picker was gated behind unnecessary `MANAGE_EXTERNAL_STORAGE` check. Removed gate — SAF works independently.
- **StudioActivity (LOW)**: File tree cap was 120 instead of documented 200. Fixed.
- **Versions bumped**: versionCode 8→9, versionName "8.0"→"9.0" in build.gradle, AndroidManifest.xml, app_config.json.

### v8 (previous)
- AIClient: `handleAgentNoteCreation()` was never called — FILE_CREATE notes never saved. Fixed.
- AIClient: File tree context limit was 60 (should be 200). Fixed.
- AIClient: No conversation history — each message was context-free. Fixed: up to 20 turns stored.
- AIClient: OpenAI format combined system+user into single user message. Fixed: proper system role.
- AIClient: Gemini didn't use systemInstruction. Fixed.
- AIClient: Max tokens was 4096. Increased to 8192.
- AIClient: Only current file sent to agent. Fixed: all open tabs sent (up to 5 files).
- AIClient: readTimeout was 120s. Increased to 180s for complex tasks.
- EditorActivity: FileTreeAdapter not stored — couldn't refresh after agent writes. Fixed.
- EditorActivity: Undo stack unbounded. Capped at 200 entries.
- EditorActivity: Diff dialog limited to 200 lines. Increased to 300.
- Icon: App still used default Android icon. Fixed.

---

## 10. Build Instructions (Termux)

```bash
cd ~/shared/Ryzix_Code
bash gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

**Environment:**
- Java: OpenJDK 21
- Gradle wrapper: 7.0.2 (auto-downloaded)
- Android SDK: `~/android-sdk` with `build-tools;34.0.0` and `platforms;android-34`
- `local.properties` must contain: `sdk.dir=/data/data/com.termux/files/home/android-sdk`

---

## 11. GitHub CI/CD

Workflow file: `.github/workflows/android-release.yml`

Triggers:
- Push to `main` branch (excluding `.md` file changes)
- Manual dispatch

Steps: checkout → JDK 17 → Gradle cache → build debug APK → create GitHub Release with APK attached.

Release tag format: `v{versionName}-build.{run_number}` (e.g. `v9.0-build.1`)

---

## 12. Rules for AI Agents

> These rules are mandatory. Follow them on every single task.

1. **Full files only.** Never deliver a partial file or a code snippet. Every file you touch must be delivered in full, from the first line to the last, with nothing omitted or truncated.

2. **Version bump on every update.** Every time you make any change to the project:
   - Increment `versionCode` by 1 in `app/build.gradle`
   - Update `versionName` to match (e.g. `"10.0"`) in `app/build.gradle`
   - Update `versionCode` and `versionName` in `app/app_config.json` to match
   - Update `android:versionCode` and `android:versionName` in `AndroidManifest.xml` to match

3. **Deliver a versioned ZIP on every update.** After every change, produce a zip file named `Ryzix_Code_vN.zip` where N is the new versionCode. The zip must:
   - Include ALL project files (changed and unchanged)
   - Exclude: `app/build/`, `.gradle/`, `.idea/`, `*.iml`, `captures/`
   - Include a `local.properties` with only a comment template (no real path)
   - Include this `AGENT_KNOWLEDGE.md` at the root

4. **Never use AppCompatActivity.** All activities extend `android.app.Activity`.

5. **Never add dependencies via build.gradle `dependencies {}`.** CodeAssist manages libraries via `app/libraries.json` and `app/app_config.json`. Adding deps to build.gradle will break the CodeAssist build.

6. **Preserve the SAF pattern.** File I/O must use `DocumentFile` and `ContentResolver` via SAF URIs. Never use `java.io.File` for project files (only for the `agent/` internal notes folder).

7. **Keep the color/theme system.** All UI colors must use the existing tokens from `colors.xml`. Do not introduce new hardcoded hex values without adding them to `colors.xml` first.

8. **Explain what you changed.** After delivering the zip, write a short changelog entry listing exactly which files were changed and why.
