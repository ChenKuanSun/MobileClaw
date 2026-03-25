---
name: notification-digest
description: Summarize and organize recent notifications by app and priority
version: "1.0"
author: MobileClaw Built-in
tools_required: notifications
---
# Notification Digest

## Role
You summarize and organize the user's recent notifications into a clear, prioritized digest. Help the user catch up on what they missed without scrolling through a messy notification shade.

## Trigger Keywords
"notifications", "what did I miss", "any new notifications", "notification summary", "通知摘要", "有什麼通知", "我錯過什麼"

## Workflow: Full Notification Digest

1. Read all recent notifications: `notifications read`
2. Group notifications by app/category:
   - **Messages** — WhatsApp, LINE, Telegram, SMS
   - **Email** — Gmail, Outlook
   - **Calls** — missed calls
   - **Calendar** — upcoming events, reminders
   - **Social** — Instagram, Twitter/X, Facebook
   - **System** — battery, updates, storage
   - **Other** — everything else
3. Within each group, sort by time (newest first)
4. Summarize each group concisely:
   - Message notifications: who sent what (combine multiple from same sender)
   - Email: sender + subject line
   - Calls: who called, how many times
   - Calendar: event name + time
5. Highlight urgent items (multiple messages from same person, missed calls, calendar within 1 hour)
6. Present the digest to the user

## Workflow: Filtered Digest

1. User asks about specific app: "any WhatsApp messages?" or "did anyone email me?"
2. `notifications read` — filter results for the requested app package
3. Summarize only matching notifications

## Workflow: Actionable Digest

1. After presenting the digest, suggest next actions:
   - "You have 3 unread WhatsApp messages from Alice. Want me to open that chat?"
   - "You missed 2 calls from Bob. Want me to call back?"
   - "You have a meeting in 30 minutes: Team Standup. Want directions to the office?"
2. Execute the action if the user confirms, using the appropriate skill

## Output Format

Present as a structured summary:

- **Messages (5)**: Alice sent 3 WhatsApp messages about dinner plans. Bob sent 1 LINE message. Carol sent 1 Telegram voice message.
- **Email (2)**: Invoice from AWS. Newsletter from TechCrunch.
- **Missed Calls (1)**: Mom called at 2:30 PM.
- **Calendar**: Team standup at 3:00 PM (in 25 minutes).
- **System**: Battery at 23%. Android update available.

## Tips
- Collapse low-priority notifications (promotions, system updates) into a single line
- Always mention the count per category so the user knows the volume
- If there are no notifications, say so clearly instead of producing an empty digest
