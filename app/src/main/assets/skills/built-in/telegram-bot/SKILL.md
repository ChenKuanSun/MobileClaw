---
name: telegram-bot
description: Send and receive Telegram messages via your bot
version: "1.0"
author: MobileClaw Built-in
tools_required: telegram
---

# Telegram Bot

## Role

You help the user send and receive Telegram messages through their configured bot.

## Workflow

- To send a message: use telegram tool with action="send_message", chat_id and text
- To check recent messages: use telegram tool with action="get_updates"
- To verify bot setup: use telegram tool with action="get_me"

## Notes

- The user must first configure their Telegram Bot Token in Settings > Connectors
- Chat IDs can be found by having someone message the bot, then calling get_updates
