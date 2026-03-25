---
name: social-media
description: Post to Twitter/X, Instagram, Facebook via UI automation
version: "1.0"
author: MobileClaw Built-in
tools_required: ui, app, clipboard
---
# Social Media

## Role
You help the user post to and browse social media platforms (Twitter/X, Instagram, Facebook) using UI automation through the Accessibility Service.

## Trigger Keywords
"post", "tweet", "share on", "publish", "發文", "貼文", "share to Twitter", "post on Instagram", "Facebook post"

## Prerequisites
- Accessibility Service must be enabled for MobileClaw
- Target social media app must be installed and logged in

## Workflow: Post to Twitter/X

1. Launch Twitter/X: `app launch com.twitter.android`
2. `ui read_screen` — confirm the home timeline is visible
3. Tap the compose button (floating "+" or feather icon): `ui click`
4. `ui read_screen` — confirm compose tweet screen is open
5. `ui type text="<tweet_content>"` — type the tweet (280 char limit)
6. Tap "Post" button: `ui click`
7. `ui read_screen` — confirm the tweet was posted (compose screen dismissed)

## Workflow: Post to Facebook

1. Launch Facebook: `app launch com.facebook.katana`
2. `ui read_screen` — confirm News Feed is visible
3. Tap "What's on your mind?" compose area: `ui click`
4. `ui read_screen` — confirm compose screen
5. `ui type text="<post_content>"`
6. Tap "Post" button: `ui click`
7. `ui read_screen` — confirm post published

## Workflow: Share to Instagram (Text Post / Story)

1. For text-based stories or captions:
   - `clipboard write text="<caption_text>"` — prepare the caption
   - Launch Instagram: `app launch com.instagram.android`
   - `ui read_screen` — confirm home feed
   - Tap the "+" create button: `ui click`
   - Follow the UI flow to select photo/create content
   - Paste caption from clipboard when caption field is active
   - Tap "Share": `ui click`

2. For sharing an existing image:
   - Use share intent: `app launch_intent intent="android.intent.action.SEND" type="image/*" package="com.instagram.android"`
   - Follow the Instagram share flow via UI automation

## Workflow: Browse Feed / Check Updates

1. Launch the target app: `app launch <package_name>`
2. `ui read_screen` — read the visible feed items
3. Summarize the top posts: author, content preview, engagement stats
4. If user wants to interact: `ui click` on like, comment, or share buttons

## Workflow: Post Text to Multiple Platforms

1. `clipboard write text="<post_content>"` — save the content once
2. For each platform, launch the app → compose → paste from clipboard → post
3. Report which platforms were posted to successfully

## Package Names Reference
- Twitter/X: `com.twitter.android`
- Facebook: `com.facebook.katana`
- Instagram: `com.instagram.android`
- Threads: `com.instagram.barcelona`
- LinkedIn: `com.linkedin.android`

## Tips
- Always read back the post content to the user before hitting Post/Tweet for confirmation
- Twitter has a 280 character limit — warn the user if content is too long
- For long posts, use `clipboard write` + paste instead of `ui type` for reliability
- Instagram posts require an image — pure text posts are only possible via Stories
- If posting fails (e.g., network error), `ui read_screen` to diagnose and report the error
