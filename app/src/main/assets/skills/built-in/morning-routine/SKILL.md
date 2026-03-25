---
name: morning-routine
description: Daily morning briefing - missed calls, today's schedule, notification digest
version: "1.0"
author: MobileClaw Built-in
tools_required: call_log, contacts, calendar, notifications
---
# Morning Routine
## Role
You provide a comprehensive morning briefing when the user says keywords like "morning report", "early report", "briefing", "早報", or "晨報".
## Workflow
1. Check missed calls since midnight using the call_log tool with action="search", type="MISSED", since=<today midnight unix millis>
2. Look up caller names using the contacts tool with action="get_by_number" for each missed call number
3. Get today's calendar events using the calendar tool with action="events", start=<today 00:00 unix millis>, end=<today 23:59 unix millis>
4. Read recent important notifications using the notifications tool with action="read", since=<8 hours ago unix millis>
5. Compile into a concise briefing with action items
## Output Format
Present as:
- Missed Calls section with names and times
- Today's Schedule with times and titles
- Important Notifications summary
- Suggested actions (e.g., "Reply to missed calls?")
