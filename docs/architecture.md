# Architecture

MobileClaw is a single-activity Android app built with Jetpack Compose, Hilt, and Room.

## Layers

```
┌─────────────────────────────────────────┐
│  UI (Jetpack Compose + Material 3)      │
│  Chat, Settings, Skills, Schedule       │
├─────────────────────────────────────────┤
│  Agent (AgentRuntime + ClaudeApiClient) │
│  Tool use loop, permission manager      │
├─────────────────────────────────────────┤
│  Tools (25 AndroidTool implementations) │
│  SMS, UI automation, web, files, etc.   │
├─────────────────────────────────────────┤
│  Data (Room + EncryptedPrefs + DataStore)│
│  Conversations, messages, tokens        │
├─────────────────────────────────────────┤
│  Skills (SKILL.md files + SkillsManager)│
│  19 built-in + user-installed           │
└─────────────────────────────────────────┘
```

## Key Components

- **AgentRuntime** — The core tool-use loop. Sends messages to Claude API, processes tool calls, handles confirmations. Max 200 iterations.
- **ClaudeApiClient** — Uses the official Anthropic Java SDK. Supports OAuth (setup token) and API key auth. Adaptive thinking for Claude 4.6 models.
- **PermissionManager** — Three modes: Default (confirm dangerous actions), Allowlist (auto-approve selected tools), Bypass (auto-approve all).
- **ClawAccessibilityService** — Android AccessibilityService that reads and interacts with any app's UI tree.
- **SkillInstaller** — Downloads skills from URLs, scans for security issues, saves to internal storage.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.1 |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt |
| Database | Room |
| Preferences | DataStore + EncryptedSharedPreferences |
| AI API | Anthropic Java SDK (com.anthropic:anthropic-java) |
| HTTP | Ktor Client (OkHttp engine) |
| Scheduling | WorkManager |
| Min SDK | 29 (Android 10) |
| Target SDK | 34 (Android 14) |
