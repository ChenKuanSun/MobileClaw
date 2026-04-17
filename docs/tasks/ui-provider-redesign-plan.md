# AI Provider Settings UX Redesign Plan

Status: DRAFT (not committed to git)
Created: 2026-04-16

## Problem
Current AI Provider settings is too engineering-oriented for regular users.
17 providers flat-listed, API key required, technical model IDs, self-hosted
requires manual URL/token entry.

## Design Principles
- Frame layers by **user intent**, not technical sophistication
- Free-first: first AI response within 2 taps from install
- Group providers by brand (alphabetical), tag by traits (not categorize by use case)
- Use `strings.xml` i18n (6 languages already supported: EN/zh-TW/ja/ko/fr/de)
- Provider logos for visual recognition

## Three-Layer Progressive Disclosure

### Layer 1: "Start Now" (馬上開始)
- Zero config, zero API keys
- On-device Gemma 4 (offline, free) with download prompt
- Cloud free tier (z.ai Flash, OpenRouter free) — one-tap
- Target: 2 taps from install to first AI response
- This should be the DEFAULT first-launch experience (no Settings visit needed)

### Layer 2: "Choose a Model" (選擇模型)
- Providers listed alphabetically with brand logos
- Each provider card: logo + name + 1-2 tags (快速/高品質/超值/免費)
- "推薦" badge on the overall best default
- Auto-select default model per provider (no forced dropdown)
- API key field with format placeholder hints (sk-ant-..., nvapi-..., sk-...)
- "Enter custom model ID..." option per provider (already implemented v1.2.5)

### Layer 3: "Self-Hosted" (自架伺服器)
- Simplified labels via strings.xml (Server Address, Model, Password)
- Auto-detect and append /v1 if user omits it
- "Scan LAN" button: NsdManager + port probe (11434/1234/8000)
- One-tap connect from discovered services

## Competitor Patterns to Adopt
- Model selector in chat header (Claude app) — switch models per-conversation
- "Suggested" label on default model (ChatGPT)
- Per-message model switching (Poe) — plan data model, implement later

## Ship Order

### Week 1: Quick Wins (minimal risk, maximum friction reduction)
1. Add Key dialog: provider group headers + trait tags
2. Auto-select default model on provider selection
3. API key placeholder format hints
4. Base URL auto-append /v1 if missing
5. Extract hardcoded UI strings to strings.xml (6 languages)

### Week 2: Free-First Default Experience
- First-launch → chat screen directly (skip onboarding settings)
- System message "Preparing AI model..." → Gemma 4 download or cloud free
- Settings becomes "change from default" not "configure from scratch"

### Week 3-4: Full Three-Layer Redesign
- New ProviderPage with Layer 1/2/3 tabs or sections
- Provider logos + tags
- "推薦" badge system

### Week 4: LAN Scan
- NsdManager + port probe for Ollama/LM Studio/vLLM
- android.permission.CHANGE_WIFI_MULTICAST_STATE
- 2s aggressive timeout per port
- One-tap connect from results

## i18n Notes
- Technical terms (API Key, Ollama, Tailscale) are proper nouns — don't translate
- UI labels use strings.xml: "API Key" (EN), "API 金鑰" (zh-TW), "APIキー" (ja)
- Currently ~25 hardcoded strings in SettingsScreen.kt need extraction
