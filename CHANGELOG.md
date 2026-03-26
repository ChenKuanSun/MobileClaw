# Changelog

All notable changes to MobileClaw will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [1.0.0] - 2026-03-25

### Added
- 25 Android tools (SMS, calls, contacts, calendar, UI automation, web, files, photos, etc.)
- 19 built-in skills (morning routine, messaging, email, navigation, weather, etc.)
- 15 slash commands (/help, /model, /bypass, /install, /create, etc.)
- Multi-provider AI support (Anthropic, OpenAI, Google, OpenRouter, + 8 more)
- Anthropic Setup Token (OAuth) support matching OpenClaw's implementation
- AccessibilityService for controlling any app on the phone
- Permission system with Default, Allowlist, and Bypass modes
- Collapsible tool execution UI (Claude Code style)
- Skill installer with security scanner (blocked/suspicious pattern detection)
- AI-guided skill creation (/create command)
- Message queue (messages sent during processing are queued)
- Stop button to cancel agent runs
- File/image/video attachments with camera support
- Voice input (Android SpeechRecognizer)
- Text-to-speech for AI responses
- Multi-session conversation management
- Markdown rendering in chat bubbles
- Per-provider encrypted token storage
- Onboarding flow (5 steps)
- Scheduled tasks via WorkManager
