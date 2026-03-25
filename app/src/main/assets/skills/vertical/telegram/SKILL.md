---
name: telegram
description: Send and receive Telegram messages via Bot API
version: "1.0"
author: MobileClaw Built-in
tools_required: http
---
# Telegram Bot Integration
## Role
You help the user send and receive Telegram messages through a Telegram Bot. All API calls go through the http tool using the Telegram Bot API.
Keywords: "telegram", "send telegram", "TG", "電報", "傳訊息到Telegram"
## Setup
The user needs a Telegram Bot token:
1. Open Telegram and message @BotFather
2. Send `/newbot` and follow the prompts to create a bot
3. Copy the bot token (format: `123456789:ABCdefGhIjKlMnOpQrStUvWxYz`)
4. Configure the token in MobileClaw Settings > API Keys > Telegram
5. The user must start a conversation with their bot (send `/start`) before the bot can message them
6. To find the user's chat_id, use the getUpdates workflow below after sending `/start` to the bot
## API Configuration
- Base URL: `https://api.telegram.org/bot{BOT_TOKEN}`
- Most read operations use GET with query parameters
- Send operations can use GET (simple) or POST with JSON body (complex)
- No extra auth headers needed — the token is in the URL
## Standard Workflows
### Get Chat ID (First-Time Setup)
The user needs their chat_id to receive messages.
1. Ask the user to open Telegram and send any message to their bot
2. Fetch updates:
   ```
   http GET https://api.telegram.org/bot{TOKEN}/getUpdates
   ```
3. Parse the response: `result[0].message.chat.id` is the chat_id
4. Store this chat_id for future use (tell user to note it in Settings)
### Send a Text Message
1. Simple message via GET:
   ```
   http GET https://api.telegram.org/bot{TOKEN}/sendMessage?chat_id={CHAT_ID}&text=Hello%20from%20MobileClaw&parse_mode=Markdown
   ```
2. For longer or formatted messages, use POST:
   ```
   http POST https://api.telegram.org/bot{TOKEN}/sendMessage
   Headers: Content-Type: application/json
   Body: {
     "chat_id": "{CHAT_ID}",
     "text": "*Bold title*\n\nMessage body with _italic_ and `code`",
     "parse_mode": "Markdown",
     "disable_web_page_preview": true
   }
   ```
3. Confirm success: response contains `ok: true` and the sent message object
### Send a Message with Buttons
Interactive inline keyboard for quick replies.
1. Use http tool:
   ```
   http POST https://api.telegram.org/bot{TOKEN}/sendMessage
   Headers: Content-Type: application/json
   Body: {
     "chat_id": "{CHAT_ID}",
     "text": "Choose an option:",
     "reply_markup": {
       "inline_keyboard": [
         [
           {"text": "Option A", "callback_data": "opt_a"},
           {"text": "Option B", "callback_data": "opt_b"}
         ],
         [
           {"text": "Open Link", "url": "https://example.com"}
         ]
       ]
     }
   }
   ```
### Get Recent Messages (Updates)
Check for new incoming messages.
1. Basic fetch:
   ```
   http GET https://api.telegram.org/bot{TOKEN}/getUpdates?limit=10
   ```
2. To get only new messages since last check, use offset:
   ```
   http GET https://api.telegram.org/bot{TOKEN}/getUpdates?offset={last_update_id + 1}&limit=10
   ```
3. Parse each update: `update.message.text` for text, `update.message.from.first_name` for sender
4. Present messages to user in chronological order
### Send a Photo
1. Send photo by URL:
   ```
   http POST https://api.telegram.org/bot{TOKEN}/sendPhoto
   Headers: Content-Type: application/json
   Body: {
     "chat_id": "{CHAT_ID}",
     "photo": "https://example.com/image.jpg",
     "caption": "Check out this photo!"
   }
   ```
2. For local files, use multipart form data:
   ```
   http POST https://api.telegram.org/bot{TOKEN}/sendPhoto
   Content-Type: multipart/form-data
   Form fields: chat_id={CHAT_ID}, photo=@/path/to/photo.jpg, caption=Photo caption
   ```
### Send a Document/File
1. By URL:
   ```
   http POST https://api.telegram.org/bot{TOKEN}/sendDocument
   Headers: Content-Type: application/json
   Body: {
     "chat_id": "{CHAT_ID}",
     "document": "https://example.com/report.pdf",
     "caption": "Here is the report"
   }
   ```
### Send Location
1. Share a GPS location:
   ```
   http POST https://api.telegram.org/bot{TOKEN}/sendLocation
   Headers: Content-Type: application/json
   Body: {
     "chat_id": "{CHAT_ID}",
     "latitude": 25.0330,
     "longitude": 121.5654
   }
   ```
### Forward a Message
1. Forward from one chat to another:
   ```
   http POST https://api.telegram.org/bot{TOKEN}/forwardMessage
   Headers: Content-Type: application/json
   Body: {
     "chat_id": "{TARGET_CHAT_ID}",
     "from_chat_id": "{SOURCE_CHAT_ID}",
     "message_id": {MESSAGE_ID}
   }
   ```
## Guidelines
- Always URL-encode text in GET query parameters (spaces as %20, newlines as %0A)
- Markdown parse_mode supports: *bold*, _italic_, `code`, ```pre```, [link](url)
- For MarkdownV2, escape special chars: _ * [ ] ( ) ~ ` > # + - = | { } . !
- Bot can only message users who have started a conversation with it first
- Rate limit: ~30 messages/second to the same chat, ~20 messages/minute to the same group
- Messages over 4096 characters must be split into multiple messages
- If sending fails with 403, the user likely blocked the bot or hasn't started it
- Always confirm before sending messages on behalf of the user
