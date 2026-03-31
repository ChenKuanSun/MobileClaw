---
name: ui-fallback
description: When no dedicated tool exists for a task, use the ui tool with AccessibilityService to control phone apps directly
version: "1.0"
author: MobileClaw Built-in
tools_required: ui, app, screen
---
# UI Fallback — Do Anything Via Accessibility

## Role
When the user asks to do something and NO dedicated tool exists for it, use the `ui` (AccessibilityService) tool to control the phone's apps directly. This is your universal fallback — if you can do it by tapping the screen, you can do it with the `ui` tool.

## When to Use This Skill
- User asks to "take a photo" → NO camera tool exists → use ui to operate the Camera app
- User asks to "record a video" → use ui to operate Camera app
- User asks to "scan a QR code" → use ui to open Camera/Lens app
- User asks to "reply on LINE/WhatsApp" → use ui to type in the messaging app
- User asks to "post on Instagram" → use ui to navigate Instagram
- User asks to "change WiFi settings" → use ui to open Settings app
- User asks to "check bank balance" → use ui to open banking app
- ANY task that involves operating a specific app on the phone

## Key Principle
**NEVER say "I can't do that" or "I don't have a camera tool".** Instead, use the `ui` tool to operate the relevant app. You can control ANY app on the phone.

## Common Workflows

### Take a Photo
1. `app` action="launch" package_name="com.google.android.GoogleCamera" (or com.android.camera2)
2. Wait 2 seconds for camera to load: `ui` action="wait" seconds=2
3. Read the screen: `ui` action="read_screen"
4. Click the shutter button: `ui` action="click" text="Shutter" (or look for the capture button by index)
5. Wait for photo to save: `ui` action="wait" seconds=2
6. Return to MobileClaw: `ui` action="launch_and_wait" package_name="ai.affiora.mobileclaw.debug"
7. Report: "Photo taken and saved to gallery."

### Record a Video
1. Launch camera app
2. Switch to video mode if needed: `ui` action="click" text="Video"
3. Click record button
4. Wait desired duration
5. Click stop button
6. Return to MobileClaw

### Reply to a Message in WhatsApp/LINE/Telegram
1. `app` action="launch" package_name="com.whatsapp" (or jp.naver.line.android, org.telegram.messenger)
2. `ui` action="read_screen" — find the conversation
3. `ui` action="click" text="<contact name>" — open the conversation
4. `ui` action="type" text="<reply message>" — type the reply
5. `ui` action="click" text="Send" — send it
6. Return to MobileClaw

### Open Settings and Change Something
1. `app` action="launch" package_name="com.android.settings"
2. `ui` action="read_screen" — see what's on screen
3. Navigate by clicking items and reading screen until you reach the target setting
4. Make the change
5. Return to MobileClaw

### Check Something in a Specific App
1. Launch the app
2. Read the screen to find the information
3. Return to MobileClaw with the result

## Tips
- Always `read_screen` BEFORE clicking anything — know what's on screen first
- If you can't find a button by text, use index from the read_screen output
- Wait 1-2 seconds after actions for the UI to update
- Always return to MobileClaw when done
- If the camera app name varies by device, try common ones: com.google.android.GoogleCamera, com.sec.android.app.camera (Samsung), com.android.camera2
