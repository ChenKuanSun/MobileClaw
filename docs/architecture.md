# Architecture

MobileClaw is a single-activity Android app built with Jetpack Compose, Hilt, and Room.

## Layers

```
┌──────────────────────────────────────────┐
│  UI (Jetpack Compose + Material 3)       │
│  Chat, Settings, Skills, Schedule        │
├──────────────────────────────────────────┤
│  Agent (AgentRuntime + ClaudeApiClient)  │
│  Tool-use loop, permission manager       │
├────────────────┬─────────────────────────┤
│  Tools (29     │  LocalInferenceEngine   │
│  AndroidTool)  │  (LiteRT-LM / Gemma 4) │
├────────────────┴─────────────────────────┤
│  Data (Room + EncryptedPrefs + DataStore)│
│  Conversations, messages, tokens         │
├──────────────────────────────────────────┤
│  Skills (SKILL.md files + SkillsManager) │
│  14 built-in + 7 vertical = 21 total    │
└──────────────────────────────────────────┘
```

## Key Components

- **AgentRuntime** — Core tool-use loop. Sends messages to AI, processes tool calls, handles confirmations. Max 200 iterations.
- **ClaudeApiClient** — Routes to Anthropic SDK, OpenAI-compatible, Google, or local LiteRT-LM depending on selected provider.
- **LocalInferenceEngine** — On-device Gemma 4 inference via LiteRT-LM 0.10.0. GPU-first with NPU/CPU fallback. Constrained decoding for tool calling.
- **LocalToolAdapter** — Bridges AndroidTool instances to LiteRT-LM's OpenApiTool interface for on-device tool calling.
- **LocalModelManager** — Downloads models from HuggingFace, checks device capability (RAM/storage/API level), manages model lifecycle.
- **PermissionManager** — Three modes: Default (confirm dangerous actions), Allowlist (auto-approve selected tools), Bypass (auto-approve all).
- **ClawAccessibilityService** — Android AccessibilityService that reads and interacts with any app's UI tree.
- **SkillInstaller** — Downloads skills from URLs, scans for security issues, saves to internal storage.
- **ChannelManager** — Remote control via Telegram bot, SMS, and notification channels with request/approve pairing.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.2 |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt 2.56 |
| Database | Room |
| Preferences | DataStore + EncryptedSharedPreferences |
| Cloud AI | Anthropic Java SDK + Ktor (10 providers) |
| On-Device AI | LiteRT-LM 0.10.0 (Gemma 4 E2B/E4B) |
| HTTP | Ktor Client (OkHttp engine) |
| Scheduling | WorkManager |
| Min SDK | 29 (Android 10) |
| Target SDK | 34 (Android 14) |

## Inference Flow

```
User message
  │
  ▼
AgentRuntime.run()
  │
  ├─ Cloud provider? → ClaudeApiClient.sendMessage()
  │   ├─ Anthropic → sendAnthropicSdk() (streaming via official SDK)
  │   ├─ Google    → sendGoogle() (Ktor HTTP)
  │   └─ Others    → sendOpenAiCompatible() (Ktor HTTP)
  │
  └─ Local provider? → ClaudeApiClient.sendLocal()
      └─ LocalInferenceEngine.generateResponse()
          ├─ Engine init: NPU → GPU → CPU (real-time fallback)
          ├─ Tool calling via OpenApiTool adapter + constrained decoding
          └─ Streaming via MessageCallback
```
