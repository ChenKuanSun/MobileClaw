---
name: email
description: Read, search, compose, and reply to emails via Gmail app
version: "1.0"
author: MobileClaw Built-in
tools_required: ui, app, notifications
---
# Email

## Role
You help the user read, search, compose, and reply to emails using the Gmail app via UI automation.

## Trigger Keywords
"email", "Gmail", "mail", "inbox", "compose email", "reply to email", "收信", "寄信", "寫信", "電子郵件"

## Prerequisites
- Gmail app must be installed and signed in
- Accessibility Service must be enabled for MobileClaw

## Workflow: Read Inbox

1. Launch Gmail: `app launch com.google.android.gm`
2. `ui read_screen` — read the inbox list showing sender, subject, and snippet
3. Summarize unread emails to the user with sender, subject, and time
4. If the user wants to read a specific email, tap it: `ui click` on the email row
5. `ui read_screen` — read the full email body

## Workflow: Compose a New Email

1. Launch Gmail: `app launch com.google.android.gm`
2. `ui read_screen` — confirm inbox is showing
3. Tap the compose FAB (floating action button, pencil icon): `ui click`
4. `ui read_screen` — confirm compose screen is open
5. Tap the "To" field: `ui click` → `ui type text="<recipient_email>"`
6. Tap the "Subject" field: `ui click` → `ui type text="<subject>"`
7. Tap the body field: `ui click` → `ui type text="<email_body>"`
8. Tap the send button (paper plane icon): `ui click`
9. Confirm: `ui read_screen` — verify compose screen dismissed (back to inbox)

## Workflow: Search Emails

1. Launch Gmail: `app launch com.google.android.gm`
2. Tap the search bar/icon: `ui click`
3. `ui type text="<search_query>"` — supports Gmail search operators (from:, subject:, etc.)
4. Submit search: `ui press_key key="ENTER"`
5. `ui read_screen` — read search results
6. Summarize matching emails to the user

## Workflow: Reply to an Email

1. Open the email (via inbox or search, see above)
2. `ui read_screen` — read the email content
3. Tap the reply button: `ui click` on "Reply" (or "Reply all" if user requests)
4. `ui read_screen` — confirm reply compose view is open
5. `ui type text="<reply_body>"` in the compose field
6. Tap send: `ui click` on the send button

## Workflow: Quick Check via Notifications

1. `notifications read` — filter for package `com.google.android.gm`
2. Summarize new emails from notifications without opening the app
3. If user wants to act on one, open it: `ui click` on the notification or launch Gmail

## Tips
- For long email bodies, use `clipboard write` then paste to avoid typing issues
- Gmail search operators: `from:alice`, `subject:invoice`, `is:unread`, `newer_than:2d`
- Always read back the recipient and subject before sending to confirm with the user
- If multiple Gmail accounts are signed in, check which account is active on the inbox screen
