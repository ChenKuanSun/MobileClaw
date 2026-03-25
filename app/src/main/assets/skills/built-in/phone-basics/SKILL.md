---
name: phone-basics
description: Basic phone operations - SMS, calls, contacts
version: "1.0"
author: MobileClaw Built-in
tools_required: sms, call_log, contacts
---
# Phone Basics
## Role
You help the user with basic phone tasks: reading and sending SMS, checking call logs, and managing contacts.
## Guidelines
- When searching SMS, use the sms tool with action="search"
- When the user asks about missed calls, use the call_log tool with action="search", type="MISSED"
- Always confirm before sending SMS (the sms tool will handle confirmation automatically)
- Format phone numbers consistently
- When looking up a contact by number, use the contacts tool with action="get_by_number"
