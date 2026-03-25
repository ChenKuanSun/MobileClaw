---
name: translation
description: Translate text between languages, copy results to clipboard
version: "1.0"
author: MobileClaw Built-in
tools_required: clipboard
---
# Translation

## Role
You translate text between any languages using the LLM's built-in multilingual capabilities. No external API or app is needed — translation is done purely through the language model, with clipboard integration for input and output.

## Trigger Keywords
"translate", "翻譯", "what does ... mean in", "say ... in", "how do you say", "翻成英文", "翻成中文", "translate what I copied"

## Supported Languages
All languages the LLM understands, including but not limited to:
English, 繁體中文, 简体中文, 日本語, 한국어, Español, Français, Deutsch, Português, Italiano, Tiếng Việt, ภาษาไทย, Bahasa Indonesia, العربية, हिन्दी, Русский

## Workflow: Translate Given Text

1. User provides text and target language (e.g., "translate 'hello world' to Chinese")
2. Translate using the LLM — produce natural, contextually appropriate translation
3. Present the translation to the user
4. `clipboard write text="<translated_text>"` — copy result to clipboard
5. Inform the user the translation has been copied

## Workflow: Translate Clipboard Contents

1. User says "translate what I copied" or "翻譯剪貼簿的內容"
2. `clipboard read` — get the current clipboard text
3. Auto-detect the source language
4. Translate to the user's preferred language (infer from conversation context, default to English or 繁體中文)
5. Present the translation
6. `clipboard write text="<translated_text>"` — replace clipboard with translation

## Workflow: Explain a Phrase or Word

1. User asks "what does <phrase> mean" or "這是什麼意思"
2. Detect the language of the phrase
3. Provide: translation, pronunciation guide (if applicable), and brief usage context
4. `clipboard write text="<translation>"` — copy the translation

## Guidelines
- Always auto-detect the source language unless the user specifies it
- Default target language: match the user's conversation language
- For ambiguous phrases, provide multiple possible translations with context
- Preserve formatting (line breaks, bullet points) when translating longer text
- For proper nouns, keep the original alongside the translation
- If the text is already in the target language, tell the user instead of "translating" it
