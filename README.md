<p align="center">
  <img src="docs/images/logo.png" width="120" alt="MobileClaw">
  <h1 align="center">MobileClaw</h1>
  <p align="center">Android AI Agent — Your Phone, Your Agent</p>
</p>

<p align="center">
  <a href="#"><img src="https://img.shields.io/badge/Android-10%2B-green" alt="Min SDK"></a>
  <a href="#"><img src="https://img.shields.io/badge/Kotlin-2.1-purple" alt="Kotlin"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue" alt="License"></a>
</p>

---

**MobileClaw** is an Android-native AI agent that can control your phone. It is an [OpenClaw](https://github.com/AidenYangX/OpenClaw) mobile port that runs entirely on-device with no server required. Give it a task in natural language and it will use your phone's apps, sensors, and data to get it done.

## Highlights

- **25 native tools** — SMS, calls, contacts, calendar, camera, file system, web browser, UI automation, and more
- **19 built-in skills** — pre-configured workflows for morning routines, email triage, web research, navigation, and beyond
- **Accessibility-powered automation** — tap, swipe, type, and read any screen through Android's AccessibilityService

<p align="center">
  <img src="docs/images/screenshot_chat.png" width="270" alt="Chat screen">
  &nbsp;&nbsp;
  <img src="docs/images/screenshot_skills.png" width="270" alt="Skills screen">
  &nbsp;&nbsp;
  <img src="docs/images/screenshot_settings.png" width="270" alt="Settings screen">
</p>

## Key Capabilities

| Category | Details |
|---|---|
| **Tools** | 25 Android-native tools exposed as Claude tool definitions |
| **Skills** | 19 composable skill files (12 built-in + 7 vertical) |
| **AI Providers** | Anthropic Claude (primary), OpenAI (secondary) |
| **Automation** | Full UI automation via AccessibilityService |
| **Scheduling** | Cron-style scheduled agent tasks |
| **Voice** | Voice input and text-to-speech output |
| **Privacy** | All data stored on-device, API keys encrypted with Tink |

## Tools

| Tool | Description |
|---|---|
| `sms` | Read and send SMS messages |
| `call_log` | Query call history |
| `contacts` | Look up and search contacts |
| `phone_call` | Initiate phone calls |
| `calendar` | Read and create calendar events |
| `alarm_timer` | Set alarms and timers |
| `notification` | Read and interact with notifications |
| `app_launcher` | Open any installed app |
| `navigation` | Android navigation (back, home, recents) |
| `ui_automation` | Tap, swipe, type, read screen elements |
| `screen_capture` | Take and analyze screenshots |
| `web_browser` | Open URLs, search the web |
| `http` | Make HTTP requests (REST APIs) |
| `file_system` | Read, write, list files |
| `photo` | Access photos and camera |
| `clipboard` | Read and write clipboard |
| `media_control` | Play, pause, skip media |
| `volume` | Adjust system volume |
| `brightness` | Adjust screen brightness |
| `flashlight` | Toggle flashlight |
| `system_info` | Battery, storage, connectivity info |
| `schedule` | Create and manage scheduled agent tasks |
| `skill_author` | Create new skills at runtime |
| `openai` | Call OpenAI models as a sub-agent |

## Skills

**Built-in:** morning-routine, email, messaging-apps, navigation, notification-digest, phone-basics, photos, self-learning, social-media, translation, weather, web-research

**Vertical:** finance, health, notion, real-estate, shopping, smart-home, telegram

Skills are composable Markdown files with YAML frontmatter. You can create your own skills from the app or by adding a `SKILL.md` file to `app/src/main/assets/skills/user/`.

## Quick Start

### Prerequisites

- Android Studio Ladybug (2024.2.1) or later
- JDK 17
- An Anthropic API key (get one at [console.anthropic.com](https://console.anthropic.com))

### Build from Source

```bash
git clone https://github.com/AidenYangX/MobileClaw.git
cd MobileClaw
cp local.properties.example local.properties   # add your SDK path
./gradlew assembleDebug
```

Install the debug APK on your device or emulator, then:

1. Open MobileClaw and enter your Anthropic API key
2. Grant requested permissions (SMS, Contacts, etc.) as needed
3. Enable the Accessibility Service when prompted
4. Start chatting — ask it to do anything on your phone

## Architecture

```
┌──────────────────────────────────────────┐
│              Jetpack Compose UI          │
│  ChatScreen · SkillsScreen · Settings   │
├──────────────────────────────────────────┤
│             ChatViewModel                │
│        (Hilt-injected ViewModels)        │
├──────────────────────────────────────────┤
│             AgentRuntime                 │
│   Tool-use loop: Claude ↔ AndroidTools  │
├──────────────────┬───────────────────────┤
│   25 AndroidTools│    SkillsManager      │
│   (native APIs)  │  (SKILL.md loader)    │
├──────────────────┴───────────────────────┤
│  Room DB · DataStore · Tink Encryption   │
├──────────────────────────────────────────┤
│  ClawAccessibilityService                │
│  ClawNotificationListener                │
│  AgentService (foreground)               │
└──────────────────────────────────────────┘
```

- **Jetpack Compose** — declarative UI with Material 3
- **Hilt** — dependency injection
- **Room** — conversation and tool activity persistence
- **AgentRuntime** — agentic tool-use loop that sends messages to Claude, executes tool calls, and feeds results back
- **AccessibilityService** — enables UI automation (tap, swipe, read screen)
- **Anthropic Java SDK + Ktor** — API communication
- **Tink** — AES-GCM encryption for API keys at rest

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, code style, and PR guidelines.

## License

```
Copyright 2026 Affiora, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

See [LICENSE](LICENSE) for the full text.

## Acknowledgments

- [OpenClaw](https://github.com/AidenYangX/OpenClaw) — the original desktop AI agent this project ports to Android
- [Anthropic](https://www.anthropic.com/) — Claude AI and the tool-use API
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — modern Android UI toolkit
- [Hilt](https://dagger.dev/hilt/) — dependency injection for Android
