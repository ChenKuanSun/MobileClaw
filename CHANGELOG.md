# Changelog

All notable changes to MobileClaw will be documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/).

## [1.1.0] - 2026-04-07

### Added
- **On-device AI:** Gemma 4 E2B/E4B via LiteRT-LM 0.10.0 with GPU acceleration
- **Tool calling on-device:** 29 tools bridged to LiteRT-LM via OpenApiTool adapter with constrained decoding
- **Backend auto-detection:** NPU → GPU → CPU real-time fallback with crash-resilient blacklist
- **Model management UI:** Download, delete, device capability check in Settings
- **Onboarding support:** LOCAL_GEMMA selectable during setup — no API key needed
- `/btw` slash command for side questions

### Changed
- Kotlin 2.1.0 → 2.2.0, Hilt 2.53.1 → 2.56.2, KSP 2.2.0-2.0.2, Compose BOM 2025.03.00
- Provider page integrates on-device model management (removed separate nav entry)
- `largeHeap=true` + OpenCL native library declarations for GPU inference
- Model list updated: GPT-5.4, Gemini 3 Pro

### Fixed
- README/website: corrected tool count (29), skill count (21), provider list
- Removed non-existent providers from website (Replicate, HuggingFace, Cohere, Llama.cpp, Bedrock)
- Mobile hamburger menu on privacy/terms pages
- Model selection race condition (`updateProviderAndModel` replaces separate calls)

## [1.0.0] - 2026-03-25

### Added
- 29 Android tools (SMS, calls, contacts, calendar, UI automation, web, files, photos, etc.)
- 21 built-in skills (14 built-in + 7 vertical)
- 16 slash commands (/help, /model, /bypass, /install, /create, /btw, etc.)
- Multi-provider AI support (Anthropic, OpenAI, Google, OpenRouter, + 6 more)
- Anthropic Setup Token (OAuth) support matching OpenClaw
- AccessibilityService for controlling any app
- Permission system (Default, Allowlist, Bypass modes)
- Channel system (Telegram bot, SMS, notification channels)
- Collapsible tool execution UI (Claude Code style)
- Skill installer with security scanner
- AI-guided skill creation
- Voice input + text-to-speech
- Multi-session conversation management
- Scheduled tasks via WorkManager
- Encrypted API key storage (AES-256-GCM)
- Onboarding flow (5 steps)
